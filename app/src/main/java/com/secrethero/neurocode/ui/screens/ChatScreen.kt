package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.ui.ChatViewModel
import com.secrethero.neurocode.ui.EditorViewModel

@Composable
fun ChatScreen(chat: ChatViewModel, editor: EditorViewModel) {
    val settings by chat.settings.collectAsStateWithLifecycle()
    val sessions by chat.sessions.collectAsStateWithLifecycle()
    val activeId by chat.activeSessionId.collectAsStateWithLifecycle()
    val runState by chat.chatRunState.collectAsStateWithLifecycle()
    val streaming by chat.streamingResponse.collectAsStateWithLifecycle()
    val agentLog by chat.agentLog.collectAsStateWithLifecycle()
    val switcherModels by chat.switcherModels.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val active = sessions.firstOrNull { it.id == activeId }
    val messages = active?.messages.orEmpty()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    val busy = runState is ChatRunState.Working
    val provider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }

    LaunchedEffect(messages.size, streaming, agentLog.size) {
        val extra = if (streaming.isNotEmpty() || agentLog.isNotEmpty()) 1 else 0
        if (messages.size + extra > 0) {
            listState.animateScrollToItem(messages.size + extra - 1)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                AssistChip(
                    onClick = chat::newChat,
                    label = { Text("Новый") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
            }
            items(sessions, key = { it.id }) { session ->
                FilterChip(
                    selected = session.id == activeId,
                    onClick = { chat.selectChat(session.id) },
                    label = {
                        Text(
                            session.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(130.dp),
                        )
                    },
                    trailingIcon = if (session.id == activeId) {
                        {
                            IconButton(
                                onClick = { chat.deleteChat(session.id) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Удалить диалог",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = { showSwitcher = true },
                label = {
                    Text(
                        if (settings.useLocalModel) {
                            "Локально · ${settings.localModelName ?: "GGUF"}"
                        } else {
                            "${provider?.name ?: "API"} · ${provider?.model ?: "модель"}"
                        },
                        maxLines = 1,
                    )
                },
            )
            if (!settings.useLocalModel) {
                Text(
                    if (settings.agentMode) "Агент" else "Только чат",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val text = messages.joinToString("\n\n") { message ->
                    when (message.role) {
                        MessageRole.USER -> "Вы: ${message.content}"
                        MessageRole.ASSISTANT -> "Агент: ${message.content}"
                        MessageRole.TOOL -> "[инструмент ${message.toolName ?: ""}] ${message.content}"
                        MessageRole.SYSTEM -> message.content
                    }
                }
                clipboard.setText(AnnotatedString(text))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать диалог")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && streaming.isEmpty()) {
                item {
                    EmptyChatHint(local = settings.useLocalModel)
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    message = message,
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.content))
                    },
                )
            }
            if (agentLog.isNotEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            TextButton(onClick = { showLog = !showLog }) {
                                Text(if (showLog) "Скрыть действия агента" else "Показать действия агента")
                            }
                            if (showLog) {
                                agentLog.forEach {
                                    Text(
                                        it,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (streaming.isNotEmpty()) {
                item {
                    MessageBubble(
                        ChatMessage(
                            id = "streaming",
                            role = MessageRole.ASSISTANT,
                            content = streaming,
                        ),
                        onCopy = { clipboard.setText(AnnotatedString(streaming)) },
                    )
                }
            }
            if (busy && streaming.isEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text((runState as ChatRunState.Working).status)
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (settings.agentMode && !settings.useLocalModel) {
                            "Опишите задачу для проекта…"
                        } else {
                            "Сообщение…"
                        },
                    )
                },
                minLines = 1,
                maxLines = 6,
            )
            FilledIconButton(
                onClick = {
                    if (busy) {
                        chat.cancelChat()
                    } else if (input.isNotBlank()) {
                        chat.sendMessage(input, editor.currentContext())
                        input = ""
                    }
                },
                enabled = busy || input.isNotBlank(),
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (busy) "Остановить" else "Отправить",
                )
            }
        }
    }

    if (showSwitcher) {
        AlertDialog(
            onDismissRequest = {
                showSwitcher = false
                chat.clearSwitcherModels()
            },
            title = { Text("Модель для следующего запроса") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Провайдер",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(settings.providers, key = { it.id }) { p ->
                            FilterChip(
                                selected = p.id == settings.selectedProviderId,
                                onClick = {
                                    chat.switchProvider(p.id)
                                    chat.loadSwitcherModels(p)
                                },
                                label = { Text(p.name) },
                            )
                        }
                    }
                    val current = settings.providers.firstOrNull {
                        it.id == settings.selectedProviderId
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Модели", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { current?.let(chat::loadSwitcherModels) }) {
                            Text(if (switcherModels?.loading == true) "Загрузка…" else "Показать")
                        }
                    }
                    switcherModels?.error?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    switcherModels?.models?.take(40)?.forEach { candidate ->
                        Text(
                            candidate,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (current != null) {
                                        chat.setProviderModel(current, candidate)
                                    }
                                    showSwitcher = false
                                    chat.clearSwitcherModels()
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                    Text(
                        "История диалога и скиллы сохранятся при смене модели.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showSwitcher = false
                    chat.clearSwitcherModels()
                }) { Text("Закрыть") }
            },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onCopy: () -> Unit) {
    val user = message.role == MessageRole.USER
    Column(
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Копировать сообщение",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(
                        color = when {
                            user -> MaterialTheme.colorScheme.primaryContainer
                            message.role == MessageRole.TOOL ->
                                MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
            ) {
                Text(
                    message.content,
                    style = if (message.role == MessageRole.TOOL) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant.takeIf { !user }
                        ?: MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun EmptyChatHint(local: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (local) "Локальная модель готова к диалогу" else "Агент готов работать с проектом",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (local) {
                "Текущий открытый файл автоматически добавляется в контекст. Локальный режим не использует интернет."
            } else {
                "Он может читать файлы, предлагать изменения, запускать команды и проверять Git diff. Опасные действия требуют подтверждения."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}


