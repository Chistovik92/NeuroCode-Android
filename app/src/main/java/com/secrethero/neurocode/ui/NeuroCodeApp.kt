package com.secrethero.neurocode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeveloperMode
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.ui.screens.ChatScreen
import com.secrethero.neurocode.ui.screens.EditorScreen
import com.secrethero.neurocode.ui.screens.GitScreen
import com.secrethero.neurocode.ui.screens.ModelSwitcherDialog
import com.secrethero.neurocode.ui.screens.SettingsScreen
import com.secrethero.neurocode.ui.screens.TerminalScreen

private enum class MainTab(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    CHAT(R.string.tab_chat, Icons.AutoMirrored.Filled.Chat),
    EDITOR(R.string.tab_editor, Icons.Default.Code),
    TERMINAL(R.string.tab_terminal, Icons.Default.Terminal),
    GIT(R.string.tab_git, Icons.Default.Source),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings),
}

/** Вкладки инструментов разработчика в современном дизайне (настройки — отдельная шторка). */
private val ModernDevTabs = listOf(MainTab.CHAT, MainTab.EDITOR, MainTab.TERMINAL, MainTab.GIT)

/** Gemini-style brand gradient used by the modern design (0.7.0 UI reference). */
private val ModernBrandGradientColors = listOf(
    Color(0xFF7DACFA),
    Color(0xFFC58AF9),
    Color(0xFFE995BB),
)

/** Данные шапки, общие для обоих дизайнов. */
private class TopBarState(
    val projectName: String?,
    val projects: List<Project>,
    val hasProject: Boolean,
    val syncEnabled: Boolean,
    val modelLabel: String,
)

/** Действия шапки, общие для обоих дизайнов. */
@Suppress("LongParameterList")
private class ShellActions(
    val onSelectProject: (String) -> Unit,
    val onNewProject: () -> Unit,
    val onImportFolder: () -> Unit,
    val onExportFolder: () -> Unit,
    val onExportZip: () -> Unit,
    val onLinkFolder: () -> Unit,
    val onSyncTo: () -> Unit,
    val onSyncFrom: () -> Unit,
    val onDeleteProject: () -> Unit,
    val onPickModel: () -> Unit,
    val onNewChat: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Suppress("LongMethod", "CyclomaticComplexMethod")
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
    val modern = settings.appDesign == AppDesign.MODERN
    var tabName by rememberSaveable { mutableStateOf(MainTab.CHAT.name) }
    val storedTab = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.CHAT
    // В современном дизайне настройки живут в шторке, а не во вкладке.
    val tab = if (modern && storedTab == MainTab.SETTINGS) MainTab.CHAT else storedTab
    var createProjectDialog by remember { mutableStateOf(false) }
    var deleteProjectDialog by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var settingsDrawer by rememberSaveable { mutableStateOf(false) }
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
    val syncEnabled = !syncBusy && viewModel.projects.linkedFolder() != null

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

    val actions = ShellActions(
        onSelectProject = viewModel.projects::selectProject,
        onNewProject = { createProjectDialog = true },
        onImportFolder = { importFolder.launch(null) },
        onExportFolder = { exportFolder.launch(null) },
        onExportZip = { exportZip.launch("neurocode-project.zip") },
        onLinkFolder = { linkFolder.launch(null) },
        onSyncTo = viewModel.projects::syncToLinkedFolder,
        onSyncFrom = viewModel.projects::syncFromLinkedFolder,
        onDeleteProject = { deleteProjectDialog = true },
        onPickModel = { showSwitcher = true },
        onNewChat = { viewModel.chat.newChat() },
        onOpenSettings = { settingsDrawer = true },
    )
    val topBar = TopBarState(
        projectName = currentProject?.name,
        projects = projects,
        hasProject = currentProject != null,
        syncEnabled = syncEnabled,
        modelLabel = when {
            settings.useLocalModel -> settings.localModelName ?: "GGUF"
            currentProvider != null -> currentProvider.model
            else -> stringResource(R.string.select_model)
        },
    )

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (modern) {
                    ModernTopBar(
                        state = topBar,
                        tab = tab,
                        onTab = { tabName = it.name },
                        actions = actions,
                    )
                } else {
                    ClassicTopBar(state = topBar, actions = actions)
                }
            },
            bottomBar = {
                if (!modern && !expanded) {
                    ClassicNavigationBar(tab = tab, onTab = { tabName = it.name })
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
                if (expanded && !modern) {
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        NavigationRail {
                            MainTab.entries.forEach { item ->
                                val label = stringResource(item.titleRes)
                                NavigationRailItem(
                                    selected = item == tab,
                                    onClick = { tabName = item.name },
                                    icon = { Icon(item.icon, contentDescription = label) },
                                    label = { Text(label) },
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

        AnimatedVisibility(
            visible = modern && settingsDrawer,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            SettingsDrawer(onClose = { settingsDrawer = false }) {
                SettingsScreen(viewModel.settingsScreen)
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
                    Text(stringResource(R.string.action_allow))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.chat.approveTool(false) }) {
                    Text(stringResource(R.string.action_deny))
                }
            },
        )
    }

    exportProgress?.let { (copied, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { copied.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.files_progress, copied, total))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.preparing))
                    }
                }
            },
            confirmButton = {},
        )
    }

    zipProgress?.let { (written, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.zip_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (written.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.mb_progress, written / 1024 / 1024, total / 1024 / 1024))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.preparing))
                    }
                }
            },
            confirmButton = {},
        )
    }

    syncProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.sync_from_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.files_progress, done, total))
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
            title = stringResource(R.string.new_project_title),
            label = stringResource(R.string.label_name),
            initialValue = "",
            confirmLabel = stringResource(R.string.action_create),
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
            title = { Text(stringResource(R.string.delete_project_title)) },
            text = {
                Text(stringResource(R.string.delete_project_text, currentProject.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.projects.deleteProject(currentProject.id)
                        deleteProjectDialog = false
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProjectDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
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

/**
 * Классическая шапка в духе GitHub-dark: заголовок с проектом, монохромный бейдж модели
 * и разделитель под панелью.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassicTopBar(state: TopBarState, actions: ShellActions) {
    var projectMenu by remember { mutableStateOf(false) }
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            title = {
                Column {
                    Text(
                        "NeuroCode",
                        style = MaterialTheme.typography.titleMedium
                            .copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    Text(
                        stringResource(
                            R.string.project_label,
                            state.projectName ?: stringResource(R.string.project_none),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                ModelBadge(label = state.modelLabel, onClick = actions.onPickModel)
                Box {
                    IconButton(onClick = { projectMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu_projects),
                        )
                    }
                    ProjectMenu(
                        expanded = projectMenu,
                        state = state,
                        actions = actions,
                        onDismiss = { projectMenu = false },
                    )
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** Бейдж активной модели: полупрозрачная заливка акцентом и рамка, как в эталонном UI. */
@Composable
private fun ModelBadge(label: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Нижняя навигация классического дизайна: без «таблетки», акцент цветом иконки и подписи. */
@Composable
private fun ClassicNavigationBar(tab: MainTab, onTab: (MainTab) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = NavigationBarDefaults.Elevation,
        ) {
            MainTab.entries.forEach { item ->
                val label = stringResource(item.titleRes)
                NavigationBarItem(
                    selected = item == tab,
                    onClick = { onTab(item) },
                    icon = { Icon(item.icon, contentDescription = label) },
                    label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/**
 * Современная шапка (Gemini): градиентный бренд слева, справа — новый диалог, выбор модели
 * и проекта, меню инструментов разработчика и настройки.
 */
@Suppress("LongMethod")
@Composable
private fun ModernTopBar(
    state: TopBarState,
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    actions: ShellActions,
) {
    var projectMenu by remember { mutableStateOf(false) }
    var toolsMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            "NeuroCode",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Medium,
                                brush = Brush.linearGradient(ModernBrandGradientColors),
                            ),
                            maxLines = 1,
                        )
                        Text(
                            state.projectName ?: stringResource(R.string.project_none),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.onNewChat) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = stringResource(R.string.chip_new),
                        )
                    }
                    Box {
                        IconButton(onClick = { projectMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.menu_projects),
                            )
                        }
                        DropdownMenu(
                            expanded = projectMenu,
                            onDismissRequest = { projectMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            DropdownMenuItem(
                                text = { Text(state.modelLabel, fontFamily = FontFamily.Monospace) },
                                onClick = {
                                    projectMenu = false
                                    actions.onPickModel()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            ProjectMenuItems(
                                state = state,
                                actions = actions,
                                onDismiss = { projectMenu = false },
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { toolsMenu = true }) {
                            Icon(
                                Icons.Default.DeveloperMode,
                                contentDescription = stringResource(R.string.tab_terminal),
                            )
                        }
                        DropdownMenu(
                            expanded = toolsMenu,
                            onDismissRequest = { toolsMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            ModernDevTabs.forEach { item ->
                                val selected = item == tab
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(item.titleRes),
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (selected) {
                                                FontWeight.Medium
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                    },
                                    onClick = {
                                        toolsMenu = false
                                        onTab(item)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            item.icon,
                                            contentDescription = null,
                                            tint = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                    trailingIcon = {
                                        if (selected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.tab_settings),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
        }
    }
}

/** Полноэкранная шторка настроек современного дизайна. */
@Composable
private fun SettingsDrawer(onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
                Text(
                    stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.titleMedium
                        .copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun ProjectMenu(
    expanded: Boolean,
    state: TopBarState,
    actions: ShellActions,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ProjectMenuItems(state = state, actions = actions, onDismiss = onDismiss)
    }
}

@Suppress("LongMethod")
@Composable
private fun ProjectMenuItems(
    state: TopBarState,
    actions: ShellActions,
    onDismiss: () -> Unit,
) {
    state.projects.forEach { project ->
        DropdownMenuItem(
            text = { Text(project.name) },
            onClick = {
                actions.onSelectProject(project.id)
                onDismiss()
            },
            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_new_project)) },
        onClick = {
            onDismiss()
            actions.onNewProject()
        },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_import_folder)) },
        onClick = {
            onDismiss()
            actions.onImportFolder()
        },
        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
    )
    if (!state.hasProject) return
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_export_project)) },
        onClick = {
            onDismiss()
            actions.onExportFolder()
        },
        leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_export_zip)) },
        onClick = {
            onDismiss()
            actions.onExportZip()
        },
        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_link_folder)) },
        onClick = {
            onDismiss()
            actions.onLinkFolder()
        },
        leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_sync_to)) },
        onClick = {
            onDismiss()
            actions.onSyncTo()
        },
        enabled = state.syncEnabled,
        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_sync_from)) },
        onClick = {
            onDismiss()
            actions.onSyncFrom()
        },
        enabled = state.syncEnabled,
        leadingIcon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_delete_project)) },
        onClick = {
            onDismiss()
            actions.onDeleteProject()
        },
    )
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
            Text(stringResource(R.string.empty_project_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.empty_project_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onCreate) { Text(stringResource(R.string.menu_new_project)) }
            FilledTonalButton(onClick = onImport) { Text(stringResource(R.string.empty_project_import)) }
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
