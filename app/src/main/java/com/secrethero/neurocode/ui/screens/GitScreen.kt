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
import com.secrethero.neurocode.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun GitScreen(viewModel: AppViewModel) {
    val status by viewModel.gitStatus.collectAsStateWithLifecycle()
    val diff by viewModel.gitDiff.collectAsStateWithLifecycle()
    val log by viewModel.gitLog.collectAsStateWithLifecycle()
    val remoteUrl by viewModel.gitRemoteUrl.collectAsStateWithLifecycle()
    val syncBusy by viewModel.gitSyncBusy.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val projectId = settings.selectedProjectId
    var commitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshGit() }

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
                status?.let { "Ветка: ${it.branch}" } ?: "Git не инициализирован",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.refreshGit() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
            }
        }

        RemoteCard(
            viewModel = viewModel,
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
                    Text("Создайте локальный Git-репозиторий для истории изменений и diff.")
                    Button(onClick = viewModel::initGit) { Text("git init") }
                }
            }
            return@Column
        }

        StatusCard(status!!)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = viewModel::stageAll) {
                Icon(Icons.Default.AddTask, contentDescription = null)
                Text(" Индексировать всё")
            }
            Button(onClick = { commitDialog = true }) {
                Text("Коммит")
            }
            OutlinedButton(onClick = { viewModel.refreshGit(stagedDiff = true) }) {
                Text("Staged diff")
            }
        }

        Text("Изменения", style = MaterialTheme.typography.titleMedium)
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
                    Text("Изменений нет", color = Color(0xFF9AA7B5))
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

        Text("История", style = MaterialTheme.typography.titleMedium)
        if (log.isEmpty()) {
            Text("Коммитов пока нет")
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
                viewModel.commit(message, name, email)
                commitDialog = false
            },
        )
    }
}

@Composable
private fun RemoteCard(
    viewModel: AppViewModel,
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
                currentUrl ?: "origin не настроен",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("HTTPS URL репозитория") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Имя пользователя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(if (currentUrl == null) "Токен доступа" else "Новый токен (пусто — не менять)") },
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
                    onClick = { viewModel.connectRemote(url.trim(), username.trim(), token) },
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Text(" Подключить")
                }
                FilledTonalButton(
                    enabled = !busy && gitReady && currentUrl != null,
                    onClick = viewModel::pullRemote,
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    Text(" Pull")
                }
                FilledTonalButton(
                    enabled = !busy && gitReady && currentUrl != null,
                    onClick = viewModel::pushRemote,
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null)
                    Text(" Push")
                }
            }
            OutlinedButton(onClick = { cloneDialog = true }, enabled = !busy) {
                Text("Клонировать в новый проект…")
            }
        }
    }

    if (cloneDialog) {
        CloneDialog(
            onDismiss = { cloneDialog = false },
            onClone = { cloneUrl, cloneUsername, cloneToken ->
                cloneDialog = false
                viewModel.cloneProject(cloneUrl, cloneUsername, cloneToken)
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
        title = { Text("Клонировать репозиторий") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Создаст новый проект в песочнице NeuroCode. Токен сохраняется в зашифрованном хранилище.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS URL репозитория") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Токен доступа") },
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
            ) { Text("Клонировать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun StatusCard(status: GitStatus) {
    val rows = listOf(
        "Новые" to status.untracked,
        "Изменены" to status.modified,
        "В индексе" to (status.added + status.changed),
        "Удалены" to (status.missing + status.removed),
        "Конфликты" to status.conflicting,
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
                if (status.clean) "Рабочее дерево чистое" else "Есть изменения",
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
        title = { Text("Создать коммит") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Сообщение") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя автора") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email автора") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = message.isNotBlank(),
                onClick = { onCommit(message, name, email) },
            ) { Text("Коммит") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
