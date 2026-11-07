package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AgentPermissionMode
import com.secrethero.neurocode.model.AgentPreset
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.ui.SettingsViewModel
import java.util.UUID

@Composable
fun AgentPresetsSection(settings: AppSettings, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf<AgentPreset?>(null) }
    var dialog by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.presets_title), Modifier.weight(1f))
        IconButton(onClick = { editing = null; dialog = true }) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.preset_new_cd))
        }
    }
    settings.presets.forEach { preset ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            preset.model?.let { it.model ?: it.localName }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${preset.skillIds.size} skills · ${preset.externalToolIds.size} tools · " +
                                "${preset.maxAgentSteps} steps · ${preset.permissionMode}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = { editing = preset; dialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_cd))
                    }
                    IconButton(onClick = { vm.deletePreset(preset.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_cd))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { vm.applyGlobalPreset(preset.id) }) {
                        Text(stringResource(R.string.preset_apply_global))
                    }
                    FilledTonalButton(onClick = { vm.applyProjectPreset(preset.id) }) {
                        Text(stringResource(R.string.preset_apply_project))
                    }
                }
                if (settings.selectedPresetId == preset.id) {
                    Text("Default preset", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (dialog) {
        AgentPresetDialog(
            current = editing,
            settings = settings,
            onDismiss = { dialog = false },
            onSave = {
                vm.savePreset(it)
                dialog = false
            },
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun AgentPresetDialog(
    current: AgentPreset?,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AgentPreset) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var model by remember {
        mutableStateOf(current?.model?.let { ref ->
            if (ref.providerId != null) "${ref.providerId}:${ref.model}" else ref.localPath.orEmpty()
        }.orEmpty())
    }
    var skills by remember { mutableStateOf(current?.skillIds.orEmpty().joinToString(", ")) }
    var tools by remember { mutableStateOf(current?.externalToolIds.orEmpty().joinToString(", ")) }
    var agentMode by remember { mutableStateOf(current?.agentMode ?: true) }
    var shell by remember { mutableStateOf(current?.allowAgentShell ?: false) }
    var plan by remember { mutableStateOf(current?.planMode ?: false) }
    var readOnly by remember {
        mutableStateOf(current?.permissionMode == AgentPermissionMode.READ_ONLY)
    }
    var steps by remember { mutableStateOf((current?.maxAgentSteps ?: 8).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (current == null) R.string.preset_new_title else R.string.preset_edit_title))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.preset_name_label)) })
                OutlinedTextField(model, { model = it }, label = { Text(stringResource(R.string.preset_model_label)) })
                OutlinedTextField(
                    skills,
                    { skills = it },
                    label = { Text(stringResource(R.string.preset_skills_label)) },
                )
                OutlinedTextField(
                    tools,
                    { tools = it },
                    label = { Text(stringResource(R.string.preset_tools_label)) },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Agent mode", Modifier.weight(1f)); Switch(agentMode, { agentMode = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shell without re-asking", Modifier.weight(1f)); Switch(shell, { shell = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Plan mode", Modifier.weight(1f)); Switch(plan, { plan = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read only", Modifier.weight(1f)); Switch(readOnly, { readOnly = it })
                }
                Text("Max steps: ${steps.toInt()}")
                Slider(steps, { steps = it }, valueRange = 1f..20f, steps = 18)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val ref = model.trim().takeIf { it.isNotEmpty() }?.let { raw ->
                        if (raw.substringBefore(':', "") in settings.providers.map { it.id }) {
                            ModelRef.cloud(raw.substringBefore(':'), raw.substringAfter(':'))
                        } else {
                            ModelRef.local(raw, raw.substringAfterLast('/'))
                        }
                    }
                    onSave(
                        AgentPreset(
                            id = current?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            model = ref,
                            skillIds = skills.ids(),
                            externalToolIds = tools.ids(),
                            agentMode = agentMode,
                            allowAgentShell = shell,
                            maxAgentSteps = steps.toInt(),
                            permissionMode = if (readOnly) {
                                AgentPermissionMode.READ_ONLY
                            } else {
                                AgentPermissionMode.ASK_ALWAYS
                            },
                            planMode = plan,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun String.ids(): Set<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
