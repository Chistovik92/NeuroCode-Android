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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
