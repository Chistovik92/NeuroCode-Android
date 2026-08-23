package com.secrethero.neurocode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.ui.screens.ChatScreen
import com.secrethero.neurocode.ui.screens.EditorScreen
import com.secrethero.neurocode.ui.screens.GitScreen
import com.secrethero.neurocode.ui.screens.ModelSwitcherDialog
import com.secrethero.neurocode.ui.screens.SettingsScreen
import com.secrethero.neurocode.ui.screens.TerminalScreen

private enum class MainTab(
    val title: String,
    val icon: ImageVector,
) {
    CHAT("Чат", Icons.AutoMirrored.Filled.Chat),
    EDITOR("Код", Icons.Default.Code),
    TERMINAL("Терминал", Icons.Default.Terminal),
    GIT("Git", Icons.Default.Source),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuroCodeApp(
    viewModel: AppViewModel,
    expanded: Boolean,
) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val settings by viewModel.settingsScreen.settings.collectAsStateWithLifecycle()
    val projects by viewModel.projects.projects.collectAsStateWithLifecycle()
    val approval by viewModel.chat.approval.collectAsStateWithLifecycle()
    val exportProgress by viewModel.projects.exportProgress.collectAsStateWithLifecycle()
    val zipProgress by viewModel.projects.zipProgress.collectAsStateWithLifecycle()
    val currentProject = projects.firstOrNull { it.id == settings.selectedProjectId }
    val currentProvider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }
    var tabName by rememberSaveable { mutableStateOf(MainTab.CHAT.name) }
    val tab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.CHAT
    var projectMenu by remember { mutableStateOf(false) }
    var createProjectDialog by remember { mutableStateOf(false) }
    var deleteProjectDialog by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
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
    val exportZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let(viewModel.projects::exportProjectZip)
    }
    val linkFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel.projects::linkFolder)
    }
    val syncBusy by viewModel.projects.syncBusy.collectAsStateWithLifecycle()
    val syncProgress by viewModel.projects.syncProgress.collectAsStateWithLifecycle()

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

    LaunchedEffect(showSwitcher, currentProvider?.id) {
        if (showSwitcher && !settings.useLocalModel && currentProvider != null) {
            viewModel.chat.loadSwitcherModels(currentProvider)
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text(
                            "NeuroCode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "Проект: ${currentProject?.name ?: "не выбран"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    val switcherProvider = if (settings.useLocalModel) {
                        null
                    } else {
                        currentProvider
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { showSwitcher = true },
                    ) {
                        Text(
                            when {
                                settings.useLocalModel ->
                                    settings.localModelName ?: "GGUF"
                                switcherProvider != null -> switcherProvider.model
                                else -> "Выбрать модель"
                            },
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .widthIn(max = 160.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { projectMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Проекты")
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
                                text = { Text("Новый проект") },
                                onClick = {
                                    projectMenu = false
                                    createProjectDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Импорт папки") },
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
                                    text = { Text("Экспортировать проект") },
                                    onClick = {
                                        projectMenu = false
                                        exportFolder.launch(null)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Экспорт в ZIP-архив") },
                                    onClick = {
                                        projectMenu = false
                                        exportZip.launch("neurocode-project.zip")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Archive, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Привязать папку синхронизации") },
                                    onClick = {
                                        projectMenu = false
                                        linkFolder.launch(null)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Синхронизировать в папку") },
                                    onClick = {
                                        projectMenu = false
                                        viewModel.projects.syncToLinkedFolder()
                                    },
                                    enabled = !syncBusy &&
                                        viewModel.projects.linkedFolder() != null,
                                    leadingIcon = {
                                        Icon(Icons.Default.Sync, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Синхронизировать из папки") },
                                    onClick = {
                                        projectMenu = false
                                        viewModel.projects.syncFromLinkedFolder()
                                    },
                                    enabled = !syncBusy &&
                                        viewModel.projects.linkedFolder() != null,
                                    leadingIcon = {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Удалить текущий проект") },
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
            if (!expanded) {
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
            val screens: @Composable () -> Unit = {
                when (tab) {
                    MainTab.CHAT -> ChatScreen(viewModel.chat, viewModel.editor)
                    MainTab.EDITOR -> EditorScreen(viewModel.editor)
                    MainTab.TERMINAL -> TerminalScreen(viewModel.terminal)
                    MainTab.GIT -> GitScreen(viewModel.git)
                    MainTab.SETTINGS -> SettingsScreen(viewModel.settingsScreen)
                }
            }
            if (expanded) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    NavigationRail {
                        MainTab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = item == tab,
                                onClick = { tabName = item.name },
                                icon = { Icon(item.icon, contentDescription = item.title) },
                                label = { Text(item.title) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxSize()) { screens() }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) { screens() }
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
                    Text("Разрешить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.chat.approveTool(false) }) {
                    Text("Запретить")
                }
            },
        )
    }

    exportProgress?.let { (copied, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Экспорт проекта") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { copied.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$copied из $total файлов")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Подготовка…")
                    }
                }
            },
            confirmButton = {},
        )
    }

    zipProgress?.let { (written, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Создание ZIP-архива") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (written.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${written / 1024 / 1024} из ${total / 1024 / 1024} МБ")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Подготовка…")
                    }
                }
            },
            confirmButton = {},
        )
    }

    syncProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Синхронизация из папки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("$done из $total файлов")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (createProjectDialog) {
        TextInputDialog(
            title = "Новый проект",
            label = "Название",
            initialValue = "",
            confirmLabel = "Создать",
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
            title = { Text("Удалить проект?") },
            text = {
                Text("Проект «${currentProject.name}» и его внутренняя копия файлов будут удалены.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.projects.deleteProject(currentProject.id)
                        deleteProjectDialog = false
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProjectDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }

    if (showSwitcher) {
        ModelSwitcherDialog(
            settings = settings,
            state = viewModel.chat.switcherModels.collectAsStateWithLifecycle().value,
            onProvider = {
                viewModel.chat.switchProvider(it.id)
                viewModel.chat.loadSwitcherModels(it)
            },
            onRefresh = viewModel.chat::loadSwitcherModels,
            onModel = { provider, model -> viewModel.chat.setProviderModel(provider, model) },
            onDismiss = {
                showSwitcher = false
                viewModel.chat.clearSwitcherModels()
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
            Text("Создайте первый проект", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Файлы будут храниться внутри песочницы NeuroCode. Папку с телефона можно импортировать отдельной копией.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onCreate) { Text("Новый проект") }
            FilledTonalButton(onClick = onImport) { Text("Импортировать папку") }
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
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}


