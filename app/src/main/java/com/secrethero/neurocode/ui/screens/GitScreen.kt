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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                status?.let { "Р’РµС‚РєР°: ${it.branch}" } ?: "Git РЅРµ РёРЅРёС†РёР°Р»РёР·РёСЂРѕРІР°РЅ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { git.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "РћР±РЅРѕРІРёС‚СЊ")
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
                    Text("РЎРѕР·РґР°Р№С‚Рµ Р»РѕРєР°Р»СЊРЅС‹Р№ Git-СЂРµРїРѕР·РёС‚РѕСЂРёР№ РґР»СЏ РёСЃС‚РѕСЂРёРё РёР·РјРµРЅРµРЅРёР№ Рё diff.")
                    Button(onClick = git::initGit) { Text("git init") }
                }
            }
            return@Column
        }

        StatusCard(status!!)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = git::stageAll) {
                Icon(Icons.Default.AddTask, contentDescription = null)
                Text(" РРЅРґРµРєСЃРёСЂРѕРІР°С‚СЊ РІСЃС‘")
            }
            Button(onClick = { commitDialog = true }) {
                Text("РљРѕРјРјРёС‚")
            }
            OutlinedButton(onClick = { git.refresh(stagedDiff = true) }) {
                Text("Staged diff")
            }
        }

        Text("РР·РјРµРЅРµРЅРёСЏ", style = MaterialTheme.typography.titleMedium)
        Surface(
            color = Color(0xFF080B10),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (diff.isBlank()) {
                    Text("РР·РјРµРЅРµРЅРёР№ РЅРµС‚", color = Color(0xFF9AA7B5))
                } else {
                    diff.lineSequence().forEach { line ->
                        Text(
                            line,
                            color = when {
                                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF67E8A5)
                                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFFF7B72)
                                line.startsWith("@@") -> Color(0xFFB9AFFF)
                                else -> Color(0xFFD8DEE9)
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Text("РСЃС‚РѕСЂРёСЏ", style = MaterialTheme.typography.titleMedium)
        if (log.isEmpty()) {
            Text("РљРѕРјРјРёС‚РѕРІ РїРѕРєР° РЅРµС‚")
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
                            "${commit.shortHash} В· ${commit.author} В· ${
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
            Text("Remote", style = MaterialTheme.typography.titleMedium)
            Text(
                currentUrl ?: "origin РЅРµ РЅР°СЃС‚СЂРѕРµРЅ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("HTTPS URL СЂРµРїРѕР·РёС‚РѕСЂРёСЏ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("РРјСЏ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (currentUrl == null) "РўРѕРєРµРЅ РґРѕСЃС‚СѓРїР°" else "РќРѕРІС‹Р№ С‚РѕРєРµРЅ (РїСѓСЃС‚Рѕ вЂ” РЅРµ РјРµРЅСЏС‚СЊ)") },
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
                    Text(" РџРѕРґРєР»СЋС‡РёС‚СЊ")
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
                Text("РљР»РѕРЅРёСЂРѕРІР°С‚СЊ РІ РЅРѕРІС‹Р№ РїСЂРѕРµРєС‚вЂ¦")
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
        title = { Text("РљР»РѕРЅРёСЂРѕРІР°С‚СЊ СЂРµРїРѕР·РёС‚РѕСЂРёР№") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "РЎРѕР·РґР°СЃС‚ РЅРѕРІС‹Р№ РїСЂРѕРµРєС‚ РІ РїРµСЃРѕС‡РЅРёС†Рµ NeuroCode. РўРѕРєРµРЅ СЃРѕС…СЂР°РЅСЏРµС‚СЃСЏ РІ Р·Р°С€РёС„СЂРѕРІР°РЅРЅРѕРј С…СЂР°РЅРёР»РёС‰Рµ.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS URL СЂРµРїРѕР·РёС‚РѕСЂРёСЏ") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("РРјСЏ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("РўРѕРєРµРЅ РґРѕСЃС‚СѓРїР°") },
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
            ) { Text("РљР»РѕРЅРёСЂРѕРІР°С‚СЊ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("РћС‚РјРµРЅР°") }
        },
    )
}

@Composable
private fun StatusCard(status: GitStatus) {
    val rows = listOf(
        "РќРѕРІС‹Рµ" to status.untracked,
        "РР·РјРµРЅРµРЅС‹" to status.modified,
        "Р’ РёРЅРґРµРєСЃРµ" to (status.added + status.changed),
        "РЈРґР°Р»РµРЅС‹" to (status.missing + status.removed),
        "РљРѕРЅС„Р»РёРєС‚С‹" to status.conflicting,
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
                if (status.clean) "Р Р°Р±РѕС‡РµРµ РґРµСЂРµРІРѕ С‡РёСЃС‚РѕРµ" else "Р•СЃС‚СЊ РёР·РјРµРЅРµРЅРёСЏ",
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
        title = { Text("РЎРѕР·РґР°С‚СЊ РєРѕРјРјРёС‚") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("РЎРѕРѕР±С‰РµРЅРёРµ") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("РРјСЏ Р°РІС‚РѕСЂР°") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Р°РІС‚РѕСЂР°") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = message.isNotBlank(),
                onClick = { onCommit(message, name, email) },
            ) { Text("РљРѕРјРјРёС‚") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("РћС‚РјРµРЅР°") }
        },
    )
}


