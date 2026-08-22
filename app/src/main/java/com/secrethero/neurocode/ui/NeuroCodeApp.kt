package com.secrethero.neurocode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.ui.screens.ChatScreen
import com.secrethero.neurocode.ui.screens.EditorScreen
import com.secrethero.neurocode.ui.screens.GitScreen
import com.secrethero.neurocode.ui.screens.SettingsScreen
import com.secrethero.neurocode.ui.screens.TerminalScreen

private enum class MainTab(
    val title: String,
    val icon: ImageVector,
) {
    CHAT("Р§Р°С‚", Icons.AutoMirrored.Filled.Chat),
    EDITOR("РљРѕРґ", Icons.Default.Code),
    TERMINAL("РўРµСЂРјРёРЅР°Р»", Icons.Default.Terminal),
    GIT("Git", Icons.Default.Source),
    SETTINGS("РќР°СЃС‚СЂРѕР№РєРё", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuroCodeApp(viewModel: AppViewModel) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val settings by viewModel.settingsScreen.settings.collectAsStateWithLifecycle()
    val projects by viewModel.projects.projects.collectAsStateWithLifecycle()
    val approval by viewModel.chat.approval.collectAsStateWithLifecycle()
    val exportProgress by viewModel.projects.exportProgress.collectAsStateWithLifecycle()
    val currentProject = projects.firstOrNull { it.id == settings.selectedProjectId }
    var tabName by rememberSaveable { mutableStateOf(MainTab.CHAT.name) }
    val tab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.CHAT
    var projectMenu by remember { mutableStateOf(false) }
    var createProjectDialog by remember { mutableStateOf(false) }
    var deleteProjectDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val importFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel.projects::importProject)
    }
    val exportFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel.projects::exportProject)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NeuroCode", fontWeight = FontWeight.SemiBold)
                        Text(
                            currentProject?.name ?: "РџСЂРѕРµРєС‚ РЅРµ РІС‹Р±СЂР°РЅ",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { projectMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "РџСЂРѕРµРєС‚С‹")
                        }
                        DropdownMenu(
                            expanded = projectMenu,
                            onDismissRequest = { projectMenu = false },
                        ) {
                            projects.forEach { project ->
                                DropdownMenuItem(
                                    text = { Text(project.name) },
                                    onClick = {
                                        viewModel.projects.selectProject(project.id)
                                        projectMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("РќРѕРІС‹Р№ РїСЂРѕРµРєС‚") },
                                onClick = {
                                    projectMenu = false
                                    createProjectDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("РРјРїРѕСЂС‚ РїР°РїРєРё") },
                                onClick = {
                                    projectMenu = false
                                    importFolder.launch(null)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                },
                            )
                            if (currentProject != null) {
                                DropdownMenuItem(
                                    text = { Text("Р­РєСЃРїРѕСЂС‚РёСЂРѕРІР°С‚СЊ РїСЂРѕРµРєС‚") },
                                    onClick = {
                                        projectMenu = false
                                        exportFolder.launch(null)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("РЈРґР°Р»РёС‚СЊ С‚РµРєСѓС‰РёР№ РїСЂРѕРµРєС‚") },
                                    onClick = {
                                        projectMenu = false
                                        deleteProjectDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tabName = item.name },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                    )
                }
            }
        },
    ) { padding ->
        if (currentProject == null && tab != MainTab.SETTINGS) {
            EmptyProjectScreen(
                padding = padding,
                onCreate = { createProjectDialog = true },
                onImport = { importFolder.launch(null) },
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (tab) {
                    MainTab.CHAT -> ChatScreen(viewModel.chat, viewModel.editor)
                    MainTab.EDITOR -> EditorScreen(viewModel.editor)
                    MainTab.TERMINAL -> TerminalScreen(viewModel.terminal)
                    MainTab.GIT -> GitScreen(viewModel.git)
                    MainTab.SETTINGS -> SettingsScreen(viewModel.settingsScreen)
                }
            }
        }
    }

    approval?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.chat.approveTool(false) },
            title = { Text(request.title) },
            text = { Text(request.details) },
            confirmButton = {
                Button(onClick = { viewModel.chat.approveTool(true) }) {
                    Text("Р Р°Р·СЂРµС€РёС‚СЊ")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.chat.approveTool(false) }) {
                    Text("Р—Р°РїСЂРµС‚РёС‚СЊ")
                }
            },
        )
    }

    exportProgress?.let { (copied, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Р­РєСЃРїРѕСЂС‚ РїСЂРѕРµРєС‚Р°") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { copied.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$copied РёР· $total С„Р°Р№Р»РѕРІ")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("РџРѕРґРіРѕС‚РѕРІРєР°вЂ¦")
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (createProjectDialog) {
        TextInputDialog(
            title = "РќРѕРІС‹Р№ РїСЂРѕРµРєС‚",
            label = "РќР°Р·РІР°РЅРёРµ",
            initialValue = "",
            confirmLabel = "РЎРѕР·РґР°С‚СЊ",
            onDismiss = { createProjectDialog = false },
            onConfirm = {
                viewModel.projects.createProject(it)
                createProjectDialog = false
            },
        )
    }

    if (deleteProjectDialog && currentProject != null) {
        AlertDialog(
            onDismissRequest = { deleteProjectDialog = false },
            title = { Text("РЈРґР°Р»РёС‚СЊ РїСЂРѕРµРєС‚?") },
            text = {
                Text("РџСЂРѕРµРєС‚ В«${currentProject.name}В» Рё РµРіРѕ РІРЅСѓС‚СЂРµРЅРЅСЏСЏ РєРѕРїРёСЏ С„Р°Р№Р»РѕРІ Р±СѓРґСѓС‚ СѓРґР°Р»РµРЅС‹.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.projects.deleteProject(currentProject.id)
                        deleteProjectDialog = false
                    },
                ) { Text("РЈРґР°Р»РёС‚СЊ") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProjectDialog = false }) {
                    Text("РћС‚РјРµРЅР°")
                }
            },
        )
    }
}

@Composable
private fun EmptyProjectScreen(
    padding: PaddingValues,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("РЎРѕР·РґР°Р№С‚Рµ РїРµСЂРІС‹Р№ РїСЂРѕРµРєС‚", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Р¤Р°Р№Р»С‹ Р±СѓРґСѓС‚ С…СЂР°РЅРёС‚СЊСЃСЏ РІРЅСѓС‚СЂРё РїРµСЃРѕС‡РЅРёС†С‹ NeuroCode. РџР°РїРєСѓ СЃ С‚РµР»РµС„РѕРЅР° РјРѕР¶РЅРѕ РёРјРїРѕСЂС‚РёСЂРѕРІР°С‚СЊ РѕС‚РґРµР»СЊРЅРѕР№ РєРѕРїРёРµР№.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onCreate) { Text("РќРѕРІС‹Р№ РїСЂРѕРµРєС‚") }
            FilledTonalButton(onClick = onImport) { Text("РРјРїРѕСЂС‚РёСЂРѕРІР°С‚СЊ РїР°РїРєСѓ") }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("РћС‚РјРµРЅР°") }
        },
    )
}


