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
import com.secrethero.neurocode.BuildConfig
import com.secrethero.neurocode.ui.SettingsViewModel
import java.util.UUID

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.modelImportProgress.collectAsStateWithLifecycle()
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var providerDialog by remember { mutableStateOf(false) }
    var deleteProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(vm::importLocalModel)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Р РµР¶РёРј СЂР°Р±РѕС‚С‹", style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = "Р›РѕРєР°Р»СЊРЅР°СЏ GGUF-РјРѕРґРµР»СЊ",
            description = if (settings.localModelPath == null) {
                "РњРѕРґРµР»СЊ РµС‰С‘ РЅРµ РёРјРїРѕСЂС‚РёСЂРѕРІР°РЅР°"
            } else {
                settings.localModelName ?: "Р›РѕРєР°Р»СЊРЅР°СЏ РјРѕРґРµР»СЊ"
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
            Text(if (settings.localModelPath == null) " РРјРїРѕСЂС‚РёСЂРѕРІР°С‚СЊ GGUF" else " Р—Р°РјРµРЅРёС‚СЊ GGUF")
        }
        progress?.let { (copied, total) ->
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (copied.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${copied / 1024 / 1024} РёР· ${total / 1024 / 1024} РњР‘")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("РљРѕРїРёСЂРѕРІР°РЅРёРµ РјРѕРґРµР»РёвЂ¦")
            }
        }
        Text(
            "Р РµРєРѕРјРµРЅРґСѓСЋС‚СЃСЏ РєРІР°РЅС‚РѕРІР°РЅРЅС‹Рµ РјРѕРґРµР»Рё 1вЂ“3B. GGUF РєРѕРїРёСЂСѓРµС‚СЃСЏ РІРѕ РІРЅСѓС‚СЂРµРЅРЅРµРµ С…СЂР°РЅРёР»РёС‰Рµ; РґР»СЏ РјРѕРґРµР»Рё 2 Р“Р‘ Р¶РµР»Р°С‚РµР»СЊРЅРѕ РЅРµ РјРµРЅРµРµ 6 Р“Р‘ RAM.",
            style = MaterialTheme.typography.bodySmall,
        )

        SettingSwitch(
            title = "Р РµР¶РёРј Р°РіРµРЅС‚Р°",
            description = "Р Р°Р·СЂРµС€Р°РµС‚ РѕР±Р»Р°С‡РЅРѕР№ РјРѕРґРµР»Рё РёСЃРїРѕР»СЊР·РѕРІР°С‚СЊ С„Р°Р№Р»С‹, С‚РµСЂРјРёРЅР°Р» Рё Git",
            checked = settings.agentMode,
            enabled = !settings.useLocalModel,
            onChecked = vm::setAgentMode,
        )
        SettingSwitch(
            title = "РљРѕРјР°РЅРґС‹ Р±РµР· РїРѕРІС‚РѕСЂРЅРѕРіРѕ РІРѕРїСЂРѕСЃР°",
            description = "РћРїР°СЃРЅС‹Рµ РєРѕРјР°РЅРґС‹ РІСЃС‘ СЂР°РІРЅРѕ РїРѕС‚СЂРµР±СѓСЋС‚ РїРѕРґС‚РІРµСЂР¶РґРµРЅРёСЏ",
            checked = settings.allowAgentShell,
            enabled = settings.agentMode && !settings.useLocalModel,
            onChecked = vm::setAllowAgentShell,
        )
        Text("РњР°РєСЃРёРјСѓРј С€Р°РіРѕРІ Р°РіРµРЅС‚Р°: ${settings.maxAgentSteps}")
        Slider(
            value = settings.maxAgentSteps.toFloat(),
            onValueChange = { vm.setMaxAgentSteps(it.toInt()) },
            valueRange = 1f..20f,
            steps = 18,
            enabled = !settings.useLocalModel,
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "API-РїСЂРѕРІР°Р№РґРµСЂС‹",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    editingProvider = null
                    providerDialog = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Р”РѕР±Р°РІРёС‚СЊ РїСЂРѕРІР°Р№РґРµСЂР°")
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
                        Icon(Icons.Default.Edit, contentDescription = "РР·РјРµРЅРёС‚СЊ")
                    }
                    IconButton(onClick = { deleteProvider = provider }) {
                        Icon(Icons.Default.Delete, contentDescription = "РЈРґР°Р»РёС‚СЊ")
                    }
                }
            }
        }

        Text("Р‘РµР·РѕРїР°СЃРЅРѕСЃС‚СЊ", style = MaterialTheme.typography.titleLarge)
        Text(
            "РљР»СЋС‡Рё API С€РёС„СЂСѓСЋС‚СЃСЏ Android Keystore Рё РЅРµ Р·Р°РїРёСЃС‹РІР°СЋС‚СЃСЏ РІ РЅР°СЃС‚СЂРѕР№РєРё, Р»РѕРіРё РёР»Рё С„Р°Р№Р»С‹ РїСЂРѕРµРєС‚Р°. РЎРµС‚РµРІС‹Рµ Р°РґСЂРµСЃР° РґРѕР»Р¶РЅС‹ РёСЃРїРѕР»СЊР·РѕРІР°С‚СЊ HTTPS.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("NeuroCode Android ${BuildConfig.VERSION_NAME} В· РјРёРЅРёРјР°Р»СЊРЅР°СЏ РІРµСЂСЃРёСЏ Android 13", style = MaterialTheme.typography.labelMedium)
    }

    if (providerDialog) {
        ProviderDialog(
            current = editingProvider,
            onDismiss = { providerDialog = false },
            onSave = { provider, key ->
                vm.saveProvider(provider, key.ifBlank { null })
                providerDialog = false
            },
        )
    }

    deleteProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteProvider = null },
            title = { Text("РЈРґР°Р»РёС‚СЊ ${provider.name}?") },
            text = { Text("РљРѕРЅС„РёРіСѓСЂР°С†РёСЏ Рё СЃРѕС…СЂР°РЅС‘РЅРЅС‹Р№ API-РєР»СЋС‡ Р±СѓРґСѓС‚ СѓРґР°Р»РµРЅС‹.") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeProvider(provider.id)
                        deleteProvider = null
                    },
                ) { Text("РЈРґР°Р»РёС‚СЊ") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProvider = null }) { Text("РћС‚РјРµРЅР°") }
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
        title = { Text(if (current == null) "РќРѕРІС‹Р№ API-РїСЂРѕРІР°Р№РґРµСЂ" else "РР·РјРµРЅРёС‚СЊ РїСЂРѕРІР°Р№РґРµСЂР°") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("РќР°Р·РІР°РЅРёРµ") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL, РІРєР»СЋС‡Р°СЏ /v1") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("РРґРµРЅС‚РёС„РёРєР°С‚РѕСЂ РјРѕРґРµР»Рё") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = {
                        Text(if (current == null) "API-РєР»СЋС‡" else "РќРѕРІС‹Р№ API-РєР»СЋС‡ (РЅРµРѕР±СЏР·Р°С‚РµР»СЊРЅРѕ)")
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
            ) { Text("РЎРѕС…СЂР°РЅРёС‚СЊ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("РћС‚РјРµРЅР°") }
        },
    )
}

