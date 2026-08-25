package com.secrethero.neurocode.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.BuildConfig
import com.secrethero.neurocode.R
import com.secrethero.neurocode.device.DeviceSnapshot
import com.secrethero.neurocode.device.ModelTier
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ThemeMode
import com.secrethero.neurocode.ui.ProviderModelsState
import com.secrethero.neurocode.ui.SettingsViewModel
import com.secrethero.neurocode.terminal.ProotState
import java.util.UUID

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.modelImportProgress.collectAsStateWithLifecycle()
    val providerModels by vm.providerModels.collectAsStateWithLifecycle()
    val prootState by vm.prootState.collectAsStateWithLifecycle()
    val deviceSnapshot by vm.deviceSnapshot.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var skillDialog by remember { mutableStateOf(false) }
    var fallbackMenu by remember { mutableStateOf(false) }
    var commandsText by remember(settings.postCheckCommands) {
        mutableStateOf(settings.postCheckCommands.joinToString("\n"))
    }
    var lspCommandText by remember(settings.lspCommand) {
        mutableStateOf(settings.lspCommand)
    }
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
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { vm.setThemeMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                            },
                        )
                    },
                )
            }
        }

        Text(stringResource(R.string.work_mode), style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = stringResource(R.string.local_gguf_model),
            description = if (settings.localModelPath == null) {
                stringResource(R.string.model_not_imported)
            } else {
                settings.localModelName ?: stringResource(R.string.local_model)
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
            Text(
                if (settings.localModelPath == null) {
                    stringResource(R.string.import_gguf)
                } else {
                    stringResource(R.string.replace_gguf)
                },
            )
        }
        progress?.let { (copied, total) ->
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (copied.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.mb_progress, copied / 1024 / 1024, total / 1024 / 1024))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.copying_model))
            }
        }
        Text(
            stringResource(R.string.gguf_recommendation),
            style = MaterialTheme.typography.bodySmall,
        )
        deviceSnapshot?.let { snapshot ->
            DeviceRecommendationCard(snapshot)
        }

        SettingSwitch(
            title = stringResource(R.string.agent_mode),
            description = stringResource(R.string.agent_mode_desc),
            checked = settings.agentMode,
            enabled = !settings.useLocalModel,
            onChecked = vm::setAgentMode,
        )
        SettingSwitch(
            title = stringResource(R.string.shell_without_ask),
            description = stringResource(R.string.shell_without_ask_desc),
            checked = settings.allowAgentShell,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = vm::setAllowAgentShell,
        )
        Text(stringResource(R.string.max_steps_format, settings.maxAgentSteps))
        Slider(
            value = settings.maxAgentSteps.toFloat(),
            onValueChange = { vm.setMaxAgentSteps(it.toInt()) },
            valueRange = 1f..20f,
            steps = 18,
            enabled = !settings.useLocalModel,
        )

        Text(stringResource(R.string.post_checks_title), style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            title = stringResource(R.string.post_checks_switch),
            description = stringResource(R.string.post_checks_desc),
            checked = settings.postChecksEnabled,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = vm::setPostChecksEnabled,
        )
        if (settings.postChecksEnabled) {
            OutlinedTextField(
                value = commandsText,
                onValueChange = { commandsText = it },
                label = { Text(stringResource(R.string.commands_label)) },
                placeholder = { Text("npm run lint\nnpm test") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                supportingText = { Text(stringResource(R.string.commands_support)) },
            )
            FilledTonalButton(
                onClick = {
                    vm.setPostCheckCommands(commandsText)
                },
                enabled = commandsText.trim() != settings.postCheckCommands.joinToString("\n"),
            ) {
                Text(stringResource(R.string.save_commands))
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.providers),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    editingProvider = null
                    providerDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_provider_cd))
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
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_cd))
                    }
                    IconButton(onClick = { deleteProvider = provider }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_cd))
                    }
                }
            }
        }

        if (!settings.useLocalModel && settings.providers.size > 1) {
            Text(stringResource(R.string.fallback_provider), style = MaterialTheme.typography.titleMedium)
            val fallback = settings.providers.firstOrNull { it.id == settings.fallbackProviderId }
            val fallbackName = fallback?.name ?: stringResource(R.string.not_selected)
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
                        text = { Text(stringResource(R.string.not_selected)) },
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

        RuntimeEnvironmentSection(
            prootState = prootState,
            lspEnabled = settings.lspEnabled,
            lspCommandText = lspCommandText,
            savedLspCommand = settings.lspCommand,
            onInstallLinux = vm::installLinuxEnvironment,
            onResetLinux = vm::resetLinuxEnvironment,
            onSetLspEnabled = vm::setLspEnabled,
            onLspCommandChanged = { lspCommandText = it },
            onSaveLspCommand = { vm.setLspCommand(lspCommandText) },
        )

        Text(stringResource(R.string.skills_title), style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = stringResource(R.string.enable_skills),
            description = stringResource(R.string.enable_skills_desc),
            checked = settings.skillsEnabled,
            onChecked = vm::setSkillsEnabled,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.my_skills_format, settings.skills.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = { exportSkills.launch("neurocode-skills.json") }) {
                Text(stringResource(R.string.export))
            }
            FilledTonalButton(onClick = { importSkills.launch(arrayOf("application/json")) }) {
                Text(stringResource(R.string.import_action))
            }
            IconButton(
                onClick = {
                    editingSkill = null
                    skillDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_skill_cd))
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
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_cd))
                    }
                    IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_cd))
                    }
                }
            }
        }

        Text(stringResource(R.string.security), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.security_text),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.version_format, BuildConfig.VERSION_NAME),
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
            title = { Text(stringResource(R.string.delete_provider_title, provider.name)) },
            text = { Text(stringResource(R.string.delete_provider_text)) },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeProvider(provider.id)
                        deleteProvider = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun DeviceRecommendationCard(snapshot: DeviceSnapshot) {
    val recommendation = snapshot.recommendation()
    val ramGb = remember(snapshot.totalMemoryMb) {
        String.format(java.util.Locale.US, "%.1f", snapshot.totalMemoryMb / 1024f)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.device_title),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.device_info_format,
                    snapshot.abi.abiName,
                    snapshot.cores,
                    ramGb,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.recommend_model_format,
                    recommendation.maxModelSizeMb,
                    tierHint(recommendation.tier),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (recommendation.limitedDevice) {
                Text(
                    stringResource(R.string.device_limited_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun tierHint(tier: ModelTier): String = when (tier) {
    ModelTier.CLOUD_ONLY -> stringResource(R.string.rec_tier_cloud_only)
    ModelTier.TINY -> stringResource(R.string.rec_tier_tiny)
    ModelTier.SMALL -> stringResource(R.string.rec_tier_small)
    ModelTier.MEDIUM -> stringResource(R.string.rec_tier_medium)
    ModelTier.LARGE -> stringResource(R.string.rec_tier_large)
}

@Composable
private fun RuntimeEnvironmentSection(
    prootState: ProotState,
    lspEnabled: Boolean,
    lspCommandText: String,
    savedLspCommand: String,
    onInstallLinux: () -> Unit,
    onResetLinux: () -> Unit,
    onSetLspEnabled: (Boolean) -> Unit,
    onLspCommandChanged: (String) -> Unit,
    onSaveLspCommand: () -> Unit,
) {
    Text(stringResource(R.string.runtime_env), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.linux_env_desc),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = when (prootState) {
            ProotState.IDLE -> stringResource(R.string.linux_status_idle)
            ProotState.PREPARING -> stringResource(R.string.linux_status_preparing)
            ProotState.READY -> stringResource(R.string.linux_status_ready)
            ProotState.UNAVAILABLE -> stringResource(R.string.linux_status_unavailable)
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onInstallLinux,
            enabled = prootState == ProotState.IDLE || prootState == ProotState.UNAVAILABLE,
        ) {
            Text(stringResource(R.string.install_linux))
        }
        OutlinedButton(
            onClick = onResetLinux,
            enabled = prootState != ProotState.PREPARING,
        ) {
            Text(stringResource(R.string.reset_linux))
        }
    }
    SettingSwitch(
        title = stringResource(R.string.lsp_switch),
        description = stringResource(R.string.lsp_desc),
        checked = lspEnabled,
        onChecked = onSetLspEnabled,
    )
    if (lspEnabled) {
        OutlinedTextField(
            value = lspCommandText,
            onValueChange = onLspCommandChanged,
            label = { Text(stringResource(R.string.lsp_command_label)) },
            placeholder = { Text(stringResource(R.string.lsp_command_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = onSaveLspCommand,
            enabled = lspCommandText.trim() != savedLspCommand,
        ) {
            Text(stringResource(R.string.action_save))
        }
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
        title = {
            Text(
                if (current == null) {
                    stringResource(R.string.new_skill_title)
                } else {
                    stringResource(R.string.edit_skill_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skill_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.skill_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.skill_prompt_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                )
                Text(
                    stringResource(R.string.skill_note),
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
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        label = { Text(stringResource(R.string.model_id_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { if (onLoadModels()) onExpandedChange(true) }) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.models_list_cd))
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
        title = {
            Text(
                if (current == null) {
                    stringResource(R.string.provider_dialog_new_title)
                } else {
                    stringResource(R.string.provider_dialog_edit_title)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.skill_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.base_url_label)) },
                    placeholder = { Text("https://api.example.com/v1") },
                    supportingText = { Text(stringResource(R.string.base_url_support)) },
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
                        stringResource(R.string.models_error_format, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(
                            if (current == null) {
                                stringResource(R.string.api_key_label)
                            } else {
                                stringResource(R.string.api_key_new_label)
                            },
                        )
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
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
