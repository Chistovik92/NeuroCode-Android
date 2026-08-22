package com.secrethero.neurocode.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.ui.AppViewModel
import java.util.UUID

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val progress by viewModel.modelImportProgress.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importLocalModel)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Режим работы", style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = "Локальная GGUF-модель",
            description = if (settings.localModelPath == null) {
                "Модель ещё не импортирована"
            } else {
                settings.localModelName ?: "Локальная модель"
            },
            checked = settings.useLocalModel,
            enabled = settings.localModelPath != null,
            onChecked = viewModel::setUseLocalModel,
        )
        FilledTonalButton(
            enabled = progress == null,
            onClick = { modelPicker.launch(arrayOf("*/*")) },
        ) {
            Icon(Icons.Default.Memory, contentDescription = null)
            Text(if (settings.localModelPath == null) " Импортировать GGUF" else " Заменить GGUF")
        }
        progress?.let { (copied, total) ->
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (copied.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${copied / 1024 / 1024} из ${total / 1024 / 1024} МБ")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Копирование модели…")
            }
        }
        Text(
            "Рекомендуются квантованные модели 1–3B. GGUF копируется во внутреннее хранилище; для модели 2 ГБ желательно не менее 6 ГБ RAM.",
            style = MaterialTheme.typography.bodySmall,
        )

        SettingSwitch(
            title = "Режим агента",
            description = "Разрешает облачной модели использовать файлы, терминал и Git",
            checked = settings.agentMode,
            enabled = !settings.useLocalModel,
            onChecked = viewModel::setAgentMode,
        )
        SettingSwitch(
            title = "Команды без повторного вопроса",
            description = "Опасные команды всё равно потребуют подтверждения",
            checked = settings.allowAgentShell,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = viewModel::setAllowAgentShell,
        )
        Text("Максимум шагов агента: ${settings.maxAgentSteps}")
        Slider(
            value = settings.maxAgentSteps.toFloat(),
            onValueChange = { viewModel.setMaxAgentSteps(it.toInt()) },
            valueRange = 1f..20f,
            steps = 18,
            enabled = !settings.useLocalModel,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "API-провайдеры",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    editingProvider = null
                    providerDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить провайдера")
            }
        }

        settings.providers.forEach { provider ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = provider.id == settings.selectedProviderId && !settings.useLocalModel,
                        onClick = { viewModel.selectProvider(provider.id) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, fontWeight = FontWeight.SemiBold)
                        Text(provider.model, style = MaterialTheme.typography.bodySmall)
                        Text(provider.baseUrl, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(
                        onClick = {
                            editingProvider = provider
                            providerDialog = true
                        },
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить")
                    }
                    IconButton(onClick = { deleteProvider = provider }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
            }
        }

        Text("Безопасность", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ключи API шифруются Android Keystore и не записываются в настройки, логи или файлы проекта. Сетевые адреса должны использовать HTTPS.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("NeuroCode Android 0.1.0 · минимальная версия Android 13", style = MaterialTheme.typography.labelMedium)
    }

    if (providerDialog) {
        ProviderDialog(
            current = editingProvider,
            onDismiss = { providerDialog = false },
            onSave = { provider, key ->
                viewModel.saveProvider(provider, key.ifBlank { null })
                providerDialog = false
            },
        )
    }

    deleteProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteProvider = null },
            title = { Text("Удалить ${provider.name}?") },
            text = { Text("Конфигурация и сохранённый API-ключ будут удалены.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeProvider(provider.id)
                        deleteProvider = null
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
        )
    }
}

@Composable
private fun ProviderDialog(
    current: ProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(current?.baseUrl.orEmpty()) }
    var model by remember { mutableStateOf(current?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Новый API-провайдер" else "Изменить провайдера") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL, включая /v1") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Идентификатор модели") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(if (current == null) "API-ключ" else "Новый API-ключ (необязательно)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && baseUrl.startsWith("https://") && model.isNotBlank(),
                onClick = {
                    val id = current?.id ?: UUID.randomUUID().toString()
                    onSave(
                        ProviderConfig(
                            id = id,
                            name = name.trim(),
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            model = model.trim(),
                            apiKeyName = current?.apiKeyName ?: id,
                            extraHeaders = current?.extraHeaders.orEmpty(),
                        ),
                        apiKey,
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
