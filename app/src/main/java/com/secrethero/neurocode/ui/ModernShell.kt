package com.secrethero.neurocode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.ChatSession

/**
 * Шапка в стиле приложения Gemini: меню слева, выбор модели («Модель ▾») по центру-слева
 * и кнопка нового чата справа. Чаты, проекты и инструменты живут в боковом меню.
 */
@Composable
internal fun ModernTopBar(
    state: TopBarState,
    onMenu: () -> Unit,
    onPickModel: () -> Unit,
    onNewChat: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_menu_cd))
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onPickModel)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.modelLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    state.projectName ?: stringResource(R.string.project_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.drawer_new_chat))
            }
        }
    }
}

/** Боковое меню в стиле Gemini: новый чат, проект, история чатов, инструменты и настройки. */
@Suppress("LongMethod", "LongParameterList")
@Composable
internal fun ModernDrawerContent(
    topBar: TopBarState,
    actions: ShellActions,
    sessions: List<ChatSession>,
    activeSessionId: String?,
    tab: MainTab,
    onSelectChat: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onCopyChat: () -> Unit,
    onTab: (MainTab) -> Unit,
    onNewChat: () -> Unit,
) {
    var projectMenu by remember { mutableStateOf(false) }
    val itemColors = NavigationDrawerItemDefaults.colors(
        unselectedContainerColor = Color.Transparent,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    )
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        LazyColumn(
            Modifier
                .weight(1f)
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            item {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        "NeuroCode",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Medium,
                            brush = Brush.linearGradient(ModernBrandGradientColors),
                        ),
                    )
                }
            }
            item {
                Surface(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.drawer_new_chat), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Box {
                    NavigationDrawerItem(
                        label = {
                            Text(
                                stringResource(
                                    R.string.project_label,
                                    topBar.projectName ?: stringResource(R.string.project_none),
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                        badge = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                        selected = false,
                        onClick = { projectMenu = true },
                        colors = itemColors,
                    )
                    DropdownMenu(
                        expanded = projectMenu,
                        onDismissRequest = { projectMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        ProjectMenuItems(
                            state = topBar,
                            actions = actions,
                            onDismiss = { projectMenu = false },
                        )
                    }
                }
            }
            item { DrawerSection(stringResource(R.string.drawer_tools)) }
            items(ModernDevTabs) { item ->
                NavigationDrawerItem(
                    label = { Text(stringResource(item.titleRes)) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    selected = item == tab,
                    onClick = { onTab(item) },
                    colors = itemColors,
                )
            }
            item { DrawerSection(stringResource(R.string.drawer_chats)) }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.drawer_no_chats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(sessions, key = { it.id }) { session ->
                val selected = session.id == activeSessionId
                NavigationDrawerItem(
                    label = {
                        Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    selected = selected,
                    onClick = { onSelectChat(session.id) },
                    badge = if (selected) {
                        {
                            Row {
                                IconButton(onClick = onCopyChat, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.drawer_copy_chat),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteChat(session.id) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete_chat_cd),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    colors = itemColors,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.tab_settings)) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = false,
            onClick = actions.onOpenSettings,
            colors = itemColors,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}
