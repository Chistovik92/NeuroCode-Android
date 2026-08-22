package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.model.FileNode
import com.secrethero.neurocode.ui.AppViewModel
import com.secrethero.neurocode.ui.TextInputDialog
import com.secrethero.neurocode.ui.components.CodeEditorView
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(viewModel: AppViewModel) {
    val tree by viewModel.fileTree.collectAsStateWithLifecycle()
    val openPath by viewModel.openPath.collectAsStateWithLifecycle()
    val text by viewModel.editorText.collectAsStateWithLifecycle()
    val dirty by viewModel.editorDirty.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var newFileDialog by remember { mutableStateOf(false) }
    var newDirectoryDialog by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxHeight()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Файлы проекта",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { newFileDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Новый файл")
                    }
                    IconButton(onClick = { newDirectoryDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "Новая папка")
                    }
                    IconButton(onClick = viewModel::refreshFileTree) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .width(320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    tree.forEach { node ->
                        FileTreeNode(node, 0) { path ->
                            viewModel.openFile(path)
                            scope.launch { drawer.close() }
                        }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { scope.launch { drawer.open() } }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Открыть файлы")
                }
                Text(
                    buildString {
                        append(openPath ?: "Файл не открыт")
                        if (dirty) append(" •")
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(
                    onClick = viewModel::saveOpenFile,
                    enabled = openPath != null && dirty,
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Сохранить")
                }
            }
            HorizontalDivider()
            if (openPath == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Text("Откройте файл из дерева проекта")
                    }
                }
            } else {
                CodeEditorView(
                    text = text,
                    onTextChange = viewModel::updateEditorText,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (newFileDialog) {
        TextInputDialog(
            title = "Новый файл",
            label = "Путь, например src/main.py",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { newFileDialog = false },
            onConfirm = {
                viewModel.createFile(it)
                newFileDialog = false
            },
        )
    }
    if (newDirectoryDialog) {
        TextInputDialog(
            title = "Новая папка",
            label = "Путь, например src/components",
            initialValue = "",
            confirmLabel = "Создать",
            onDismiss = { newDirectoryDialog = false },
            onConfirm = {
                viewModel.createDirectory(it)
                newDirectoryDialog = false
            },
        )
    }
}

@Composable
private fun FileTreeNode(
    node: FileNode,
    depth: Int,
    onOpen: (String) -> Unit,
) {
    var expanded by remember(node.relativePath) { mutableStateOf(depth < 1) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (node.directory) expanded = !expanded else onOpen(node.relativePath)
            }
            .padding(
                start = (12 + depth * 16).dp,
                end = 10.dp,
                top = 7.dp,
                bottom = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (node.directory) {
                if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder
            } else {
                Icons.Default.Description
            },
            contentDescription = null,
            tint = if (node.directory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            node.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (node.directory && expanded) {
        node.children.forEach { child ->
            FileTreeNode(child, depth + 1, onOpen)
        }
    }
}
