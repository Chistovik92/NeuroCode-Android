package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.R
import com.secrethero.neurocode.git.GitStatus
import com.secrethero.neurocode.ui.GitViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun GitScreen(git: GitViewModel) {
    val status by git.gitStatus.collectAsStateWithLifecycle()
    val diff by git.gitDiff.collectAsStateWithLifecycle()
    val log by git.gitLog.collectAsStateWithLifecycle()
    val remoteUrl by git.gitRemoteUrl.collectAsStateWithLifecycle()
    val syncBusy by git.gitSyncBusy.collectAsStateWithLifecycle()
    val settings by git.settings.collectAsStateWithLifecycle()
    val projectId = settings.selectedProjectId
    var commitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { git.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                status?.let { stringResource(R.string.branch_format, it.branch) }
                    ?: stringResource(R.string.git_not_initialized),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { git.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }

        RemoteCard(
            git = git,
            currentUrl = remoteUrl,
            savedUsername = projectId?.let { settings.gitUsernames[it] }.orEmpty(),
            busy = syncBusy,
            gitReady = status != null,
        )

        if (status == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.git_init_hint))
                    Button(onClick = git::initGit) { Text("git init") }
                }
            }
            return@Column
        }

        StatusCard(status!!)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = git::stageAll) {
                Icon(Icons.Default.AddTask, contentDescription = null)
                Text(stringResource(R.string.stage_all))
            }
            Button(onClick = { commitDialog = true }) {
                Text(stringResource(R.string.commit))
            }
            OutlinedButton(onClick = { git.refresh(stagedDiff = true) }) {
                Text(stringResource(R.string.staged_diff))
            }
        }

        Text(stringResource(R.string.changes), style = MaterialTheme.typography.titleMedium)
        Surface(
            color = Color(0xFF0D1117),
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (diff.isBlank()) {
                    Text(stringResource(R.string.no_changes), color = Color(0xFF9AA7B5))
                } else {
                    diff.lineSequence().forEach { line ->
                        Text(
                            line,
                            color = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF7EE787)
                                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF7B72)
                                line.startsWith("@@") -> Color(0xFF79C0FF)
                                else -> Color(0xFFE6EDF3)
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium)
        if (log.isEmpty()) {
            Text(stringResource(R.string.no_commits_yet))
        } else {
            log.forEach { commit ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(commit.message, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${commit.shortHash} · ${commit.author} · ${
                                DateFormat.getDateTimeInstance().format(Date(commit.timestamp))
                            }",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    if (commitDialog) {
        CommitDialog(
            onDismiss = { commitDialog = false },
            onCommit = { message, name, email ->
                git.commit(message, name, email)
                commitDialog = false
            },
        )
    }
}

@Composable
private fun RemoteCard(
    git: GitViewModel,
    currentUrl: String?,
    savedUsername: String,
    busy: Boolean,
    gitReady: Boolean,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl.orEmpty()) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var token by remember { mutableStateOf("") }
    var cloneDialog by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.titleMedium)
            Text(
                currentUrl ?: stringResource(R.string.origin_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.repo_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = {
                    Text(
                        if (currentUrl == null) {
                            stringResource(R.string.token_label)
                        } else {
                            stringResource(R.string.token_new_label)
                        },
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && url.startsWith("https://"),
                    onClick = { git.connectRemote(url.trim(), username.trim(), token) },
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text(stringResource(R.string.connect))
                }
                FilledTonalButton(
                    enabled = !busy && gitReady && currentUrl != null,
                    onClick = git::pullRemote,
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    Text(" Pull")
                }
                FilledTonalButton(
                    enabled = !busy && gitReady && currentUrl != null,
                    onClick = git::pushRemote,
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null)
                    Text(" Push")
                }
            }
            OutlinedButton(onClick = { cloneDialog = true }, enabled = !busy) {
                Text(stringResource(R.string.clone_new_project))
            }
        }
    }

    if (cloneDialog) {
        CloneDialog(
            onDismiss = { cloneDialog = false },
            onClone = { cloneUrl, cloneUsername, cloneToken ->
                cloneDialog = false
                git.cloneProject(cloneUrl, cloneUsername, cloneToken)
            },
        )
    }
}

@Composable
private fun CloneDialog(
    onDismiss: () -> Unit,
    onClone: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clone_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.clone_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.repo_url_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = url.trim().startsWith("https://"),
                onClick = { onClone(url.trim(), username.trim(), token) },
            ) { Text(stringResource(R.string.clone_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun StatusCard(status: GitStatus) {
    val rows = listOf(
        stringResource(R.string.status_new) to status.untracked,
        stringResource(R.string.status_modified) to status.modified,
        stringResource(R.string.status_staged) to (status.added + status.changed),
        stringResource(R.string.status_deleted) to (status.missing + status.removed),
        stringResource(R.string.status_conflicts) to status.conflicting,
    ).filter { it.second.isNotEmpty() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (status.clean) {
                    stringResource(R.string.clean_tree)
                } else {
                    stringResource(R.string.dirty_tree)
                },
                color = if (status.clean) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
            )
            rows.forEach { (label, files) ->
                Text("$label: ${files.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CommitDialog(
    onDismiss: () -> Unit,
    onCommit: (String, String, String) -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("NeuroCode User") }
    var email by remember { mutableStateOf("user@localhost") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.commit_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.message_label)) },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.author_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.author_email_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = message.isNotBlank(),
                onClick = { onCommit(message, name, email) },
            ) { Text(stringResource(R.string.commit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}


