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
    CHAT("Чат", Icons.AutoMirrored.Filled.Chat),
    EDITOR("Код", Icons.Default.Code),
    TERMINAL("Терминал", Icons.Default.Terminal),
    GIT("Git", Icons.Default.Source),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuroCodeApp(viewModel: AppViewModel) {
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val approval by viewModel.approval.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
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
        uri?.let(viewModel::importProject)
    }
    val exportFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let(viewModel::exportProject)
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
                            currentProject?.name ?: "Проект не выбран",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
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
                                        viewModel.selectProject(project.id)
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
                    MainTab.CHAT -> ChatScreen(viewModel)
                    MainTab.EDITOR -> EditorScreen(viewModel)
                    MainTab.TERMINAL -> TerminalScreen(viewModel)
                    MainTab.GIT -> GitScreen(viewModel)
                    MainTab.SETTINGS -> SettingsScreen(viewModel)
                }
            }
        }
    }

    approval?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.approveTool(false) },
            title = { Text(request.title) },
            text = { Text(request.details) },
            confirmButton = {
                Button(onClick = { viewModel.approveTool(true) }) {
                    Text("Разрешить")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.approveTool(false) }) {
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

    if (createProjectDialog) {
        TextInputDialog(
            title = "Новый проект",
            label = "Название",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { createProjectDialog = false },
            onConfirm = {
                viewModel.createProject(it)
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
                        viewModel.deleteProject(currentProject.id)
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
