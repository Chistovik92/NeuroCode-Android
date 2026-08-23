package com.secrethero.neurocode.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.secrethero.neurocode.BuildConfig
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ThemeMode
import com.secrethero.neurocode.ui.ProviderModelsState
import com.secrethero.neurocode.ui.SettingsViewModel
import android.net.Uri
import java.util.UUID

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.modelImportProgress.collectAsStateWithLifecycle()
    val providerModels by vm.providerModels.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var skillDialog by remember { mutableStateOf(false) }
    var fallbackMenu by remember { mutableStateOf(false) }
    var commandsText by remember { mutableStateOf(settings.postCheckCommands.joinToString("\n")) }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::importLocalModel)
    }
    val exportSkills = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(vm::exportSkills)
    }
    val importSkills = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::importSkills)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Оформление", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { vm.setThemeMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "Системная"
                                ThemeMode.LIGHT -> "Светлая"
                                ThemeMode.DARK -> "Тёмная"
                            },
                        )
                    },
                )
            }
        }

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
            onChecked = vm::setUseLocalModel,
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
                LinearProgressIndicator(Modifier.fillMaxWidth())
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
            onChecked = vm::setAgentMode,
        )
        SettingSwitch(
            title = "Команды без повторного вопроса",
            description = "Опасные команды всё равно потребуют подтверждения",
            checked = settings.allowAgentShell,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = vm::setAllowAgentShell,
        )
        Text("Максимум шагов агента: ${settings.maxAgentSteps}")
        Slider(
            value = settings.maxAgentSteps.toFloat(),
            onValueChange = { vm.setMaxAgentSteps(it.toInt()) },
            valueRange = 1f..20f,
            steps = 18,
            enabled = !settings.useLocalModel,
        )

        Text("Пост-проверки агента", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            title = "Запускать проверки после правок",
            description = "Команды выполняются в корне проекта после ответа агента (например: npm run lint, npm test)",
            checked = settings.postChecksEnabled,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = vm::setPostChecksEnabled,
        )
        if (settings.postChecksEnabled) {
            OutlinedTextField(
                value = commandsText,
                onValueChange = { commandsText = it },
                label = { Text("Команды, по одной на строку") },
                placeholder = { Text("npm run lint\nnpm test") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                supportingText = { Text("Выполняются через Android shell; результат попадает в лог агента") },
            )
            FilledTonalButton(
                onClick = {
                    vm.setPostCheckCommands(commandsText)
                },
                enabled = commandsText.trim() != settings.postCheckCommands.joinToString("\n"),
            ) {
                Text("Сохранить команды")
            }
        }

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
                        onClick = { vm.selectProvider(provider.id) },
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

        if (!settings.useLocalModel && settings.providers.size > 1) {
            Text("Резервный провайдер", style = MaterialTheme.typography.titleMedium)
            val fallback = settings.providers.firstOrNull { it.id == settings.fallbackProviderId }
            val fallbackName = fallback?.name ?: "Не выбран"
            Box {
                OutlinedButton(
                    onClick = { fallbackMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(fallbackName)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = fallbackMenu,
                    onDismissRequest = { fallbackMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Не выбран") },
                        onClick = {
                            vm.setFallbackProvider(null)
                            fallbackMenu = false
                        },
                    )
                    settings.providers
                        .filter { it.id != settings.selectedProviderId }
                        .forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.name) },
                                onClick = {
                                    vm.setFallbackProvider(provider.id)
                                    fallbackMenu = false
                                },
                            )
                        }
                }
            }
        }

        Text("Скиллы агента", style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = "Включить скиллы",
            description = "Активные скиллы передаются модели в каждом запросе и сохраняются при смене модели",
            checked = settings.skillsEnabled,
            onChecked = vm::setSkillsEnabled,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Мои скиллы (${settings.skills.size})",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { exportSkills.launch("neurocode-skills.json") }) {
                Text("Экспорт")
            }
            FilledTonalButton(onClick = { importSkills.launch(arrayOf("application/json")) }) {
                Text("Импорт")
            }
            IconButton(
                onClick = {
                    editingSkill = null
                    skillDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Новый скилл")
            }
        }
        settings.skills.forEach { skill ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            skill.prompt.take(80),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { vm.toggleSkill(skill.id, it) },
                    )
                    IconButton(onClick = {
                        editingSkill = skill
                        skillDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить")
                    }
                    IconButton(onClick = { vm.deleteSkill(skill.id) }) {
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
        Text(
            "NeuroCode Android ${BuildConfig.VERSION_NAME} · минимальная версия Android 13",
            style = MaterialTheme.typography.labelMedium,
        )
    }

    if (providerDialog) {
        ProviderDialog(
            current = editingProvider,
            modelsState = providerModels,
            onLoadModels = vm::loadProviderModels,
            onDismiss = { providerDialog = false },
            onSave = { provider, key ->
                vm.saveProvider(provider, key.ifBlank { null })
                providerDialog = false
            },
        )
    }

    if (skillDialog) {
        SkillDialog(
            current = editingSkill,
            onDismiss = { skillDialog = false },
            onSave = { skill ->
                vm.saveSkill(skill)
                skillDialog = false
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
                        vm.removeProvider(provider.id)
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
private fun SkillDialog(
    current: AgentSkill?,
    onDismiss: () -> Unit,
    onSave: (AgentSkill) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(current?.prompt.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Новый скилл" else "Изменить скилл") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Инструкция для модели") },
                    placeholder = { Text("Например: всегда пиши тесты после правок") },
                    minLines = 4,
                    maxLines = 8,
                )
                Text(
                    "Скилл добавляется в системный промпт и действует во всём диалоге, даже если вы смените модель.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && prompt.isNotBlank(),
                onClick = {
                    onSave(
                        AgentSkill(
                            id = current?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            prompt = prompt.trim(),
                            enabled = current?.enabled ?: true,
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
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
private fun ModelSelectorField(
    model: String,
    onModelChange: (String) -> Unit,
    modelsState: ProviderModelsState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLoadModels: () -> Boolean,
) {
    OutlinedTextField(
        value = model,
        onValueChange = onModelChange,
        label = { Text("Идентификатор модели") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { if (onLoadModels()) onExpandedChange(true) }) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Список моделей")
            }
        },
    )
    DropdownMenu(
        expanded = expanded && modelsState.models.isNotEmpty(),
        onDismissRequest = { onExpandedChange(false) },
        modifier = Modifier.heightIn(max = 320.dp),
    ) {
        val query = model.trim()
        val candidates = modelsState.models
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .ifEmpty { modelsState.models }
            .take(60)
        candidates.forEach { candidate ->
            DropdownMenuItem(
                text = { Text(candidate) },
                onClick = {
                    onModelChange(candidate)
                    onExpandedChange(false)
                },
            )
        }
    }
}

@Composable
private fun ProviderDialog(
    current: ProviderConfig?,
    modelsState: ProviderModelsState,
    onLoadModels: (ProviderConfig) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig, String) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(current?.baseUrl.orEmpty()) }
    var model by remember { mutableStateOf(current?.model.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var modelsMenu by remember { mutableStateOf(false) }
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
                    supportingText = { Text("Адрес обычно оканчивается на /v1") },
                    singleLine = true,
                )
                Box {
                    ModelSelectorField(
                        model = model,
                        onModelChange = { model = it },
                        modelsState = modelsState,
                        expanded = modelsMenu,
                        onExpandedChange = { modelsMenu = it },
                        onLoadModels = {
                            if (baseUrl.startsWith("https://") && model.isNotBlank()) {
                                onLoadModels(
                                    ProviderConfig(
                                        id = current?.id ?: "draft",
                                        name = name,
                                        baseUrl = baseUrl.trim().trimEnd('/'),
                                        model = model.trim(),
                                        extraHeaders = current?.extraHeaders.orEmpty(),
                                    ),
                                )
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
                if (modelsState.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                modelsState.error?.let { message ->
                    Text(
                        "Не удалось получить список моделей: $message",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
