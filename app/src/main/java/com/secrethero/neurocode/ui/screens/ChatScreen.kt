package com.secrethero.neurocode.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.ui.ChatViewModel
import com.secrethero.neurocode.ui.EditorViewModel
import com.secrethero.neurocode.ui.ProviderModelsState
import java.io.File

/** Заливка пузыря пользователя в классическом дизайне (GitHub green). */
private val ClassicUserBubble = Color(0xFF238636)

/** Фирменный градиент Gemini для аватара и приветствия. */
private val ModernAvatarGradient = listOf(Color(0xFF7DACFA), Color(0xFFC58AF9))

@Composable
fun ChatScreen(chat: ChatViewModel, editor: EditorViewModel) {
    val settings by chat.settings.collectAsStateWithLifecycle()
    val sessions by chat.sessions.collectAsStateWithLifecycle()
    val activeId by chat.activeSessionId.collectAsStateWithLifecycle()
    val runState by chat.chatRunState.collectAsStateWithLifecycle()
    val streaming by chat.streamingResponse.collectAsStateWithLifecycle()
    val agentLog by chat.agentLog.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val active = sessions.firstOrNull { it.id == activeId }
    val messages = active?.messages.orEmpty()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val busy = runState is ChatRunState.Working
    val modern = settings.appDesign == AppDesign.MODERN
    val pendingAttachments by chat.pendingAttachments.collectAsStateWithLifecycle()
    val streamingReasoning by chat.streamingReasoning.collectAsStateWithLifecycle()
    val limits by chat.modelLimits.collectAsStateWithLifecycle()
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) chat.attachFiles(uris) }

    LaunchedEffect(messages.size, streaming, agentLog.size) {
        val extra = if (streaming.isNotEmpty() || agentLog.isNotEmpty()) 1 else 0
        if (messages.size + extra > 0) {
            listState.animateScrollToItem(messages.size + extra - 1)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter),
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
                        label = { Text(stringResource(R.string.chip_new)) },
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
                                        contentDescription = stringResource(R.string.delete_chat_cd),
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty() && streaming.isEmpty()) {
                    item {
                        if (modern) {
                            ModernGreeting()
                        } else {
                            EmptyChatHint(local = settings.useLocalModel)
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        modern = modern,
                        attachmentFile = chat::attachmentFile,
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
                                    Text(
                                        if (showLog) {
                                            stringResource(R.string.hide_agent_actions)
                                        } else {
                                            stringResource(R.string.show_agent_actions)
                                        },
                                    )
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
                if (streamingReasoning.isNotEmpty()) {
                    item { ReasoningBlock(text = streamingReasoning, initiallyExpanded = true) }
                }
                if (streaming.isNotEmpty()) {
                    item {
                        MessageBubble(
                            ChatMessage(
                                id = "streaming",
                                role = MessageRole.ASSISTANT,
                                content = streaming,
                            ),
                            modern = modern,
                            attachmentFile = { null },
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

            val context = LocalContext.current
            val onCopyDialog = {
                val text = messages.joinToString("\n\n") { message ->
                    when (message.role) {
                        MessageRole.USER ->
                            context.getString(R.string.prefix_user, message.content)
                        MessageRole.ASSISTANT ->
                            context.getString(R.string.prefix_agent, message.content)
                        MessageRole.TOOL -> context.getString(
                            R.string.prefix_tool,
                            message.toolName ?: "",
                            message.content,
                        )
                        MessageRole.SYSTEM -> message.content
                    }
                }
                clipboard.setText(AnnotatedString(text))
            }
            val placeholder = if (settings.agentMode && !settings.useLocalModel) {
                stringResource(R.string.hint_agent_input)
            } else {
                stringResource(R.string.hint_message)
            }
            val onSend = {
                if (busy) {
                    chat.cancelChat()
                } else if (input.isNotBlank()) {
                    chat.sendMessage(input, editor.currentContext())
                    input = ""
                }
            }
            val bar = InputBarState(
                value = input,
                onValue = { input = it },
                placeholder = placeholder,
                busy = busy,
                onSend = onSend,
                onCopyDialog = onCopyDialog,
                copyEnabled = messages.isNotEmpty(),
                onAttach = { pickFiles.launch(arrayOf("*/*")) },
                hasAttachments = pendingAttachments.isNotEmpty(),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                limits?.let { ModelLimitsBar(it) }
                if (pendingAttachments.isNotEmpty()) {
                    PendingAttachments(
                        attachments = pendingAttachments,
                        attachmentFile = chat::attachmentFile,
                        onRemove = chat::removeAttachment,
                    )
                }
                if (modern) {
                    ModernInputBar(bar)
                } else {
                    ClassicInputBar(bar)
                }
            }
        }
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
fun ModelSwitcherDialog(
    settings: AppSettings,
    state: ProviderModelsState?,
    onProvider: (ProviderConfig) -> Unit,
    onRefresh: (ProviderConfig) -> Unit,
    onModel: (ProviderConfig, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val provider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }
    val models = state?.models.orEmpty().filter {
        query.isBlank() || it.contains(query, ignoreCase = true)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.model_picker_title), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.model_picker_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                }
                LazyRow(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(settings.providers, key = { it.id }) { item ->
                        FilterChip(
                            selected = item.id == settings.selectedProviderId,
                            onClick = { onProvider(item) },
                            label = { Text(item.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_model)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        provider?.model ?: stringResource(R.string.model_not_selected),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { provider?.let(onRefresh) }) {
                        Text(
                            if (state?.loading == true) {
                                stringResource(R.string.loading)
                            } else {
                                stringResource(R.string.action_refresh)
                            },
                        )
                    }
                }
                state?.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(models, key = { it }) { model ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    provider?.let { onModel(it, model) }
                                    onDismiss()
                                },
                            color = if (model == provider?.model) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                model,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Параметры панели ввода, общие для обоих дизайнов. */
@Suppress("LongParameterList")
private class InputBarState(
    val value: String,
    val onValue: (String) -> Unit,
    val placeholder: String,
    val busy: Boolean,
    val onSend: () -> Unit,
    val onCopyDialog: () -> Unit,
    val copyEnabled: Boolean,
    val onAttach: () -> Unit,
    val hasAttachments: Boolean,
)

/** Классическая панель ввода: плотная строка на панели с рамкой (GitHub-dark). */
@Composable
private fun ClassicInputBar(state: InputBarState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = state.onAttach) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.attach_files_cd),
                    tint = if (state.hasAttachments) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = state.onCopyDialog, enabled = state.copyEnabled) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_dialog_cd),
                )
            }
            OutlinedTextField(
                value = state.value,
                onValueChange = state.onValue,
                modifier = Modifier.weight(1f),
                placeholder = { Text(state.placeholder) },
                shape = RoundedCornerShape(6.dp),
                minLines = 1,
                maxLines = 6,
            )
            FilledIconButton(
                onClick = state.onSend,
                enabled = state.busy || state.value.isNotBlank(),
            ) {
                Icon(
                    if (state.busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (state.busy) {
                        stringResource(R.string.stop_cd)
                    } else {
                        stringResource(R.string.send_cd)
                    },
                )
            }
        }
    }
}

/** Современная панель ввода: «таблетка» на фоне экрана, круглая кнопка отправки. */
@Suppress("LongMethod")
@Composable
private fun ModernInputBar(state: InputBarState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = state.onAttach) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.attach_files_cd),
                        tint = if (state.hasAttachments) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = state.onCopyDialog, enabled = state.copyEnabled) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_dialog_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                TextField(
                    value = state.value,
                    onValueChange = state.onValue,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(state.placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 6,
                )
                FilledIconButton(
                    onClick = state.onSend,
                    enabled = state.busy || state.value.isNotBlank(),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (state.busy) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (state.busy) {
                            stringResource(R.string.stop_cd)
                        } else {
                            stringResource(R.string.send_cd)
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}


@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun MessageBubble(
    message: ChatMessage,
    modern: Boolean,
    attachmentFile: (ChatAttachment) -> File?,
    onCopy: () -> Unit,
) {
    val user = message.role == MessageRole.USER
    val tool = message.role == MessageRole.TOOL
    Column(
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(text = it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (modern && !user) {
                GeminiAvatar()
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_message_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            val shape = bubbleShape(modern = modern, user = user)
            val background = bubbleBackground(modern = modern, user = user, tool = tool)
            val bordered = !modern || tool
            Box(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(background, shape)
                    .then(
                        if (bordered) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                shape,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        horizontal = if (modern && !user && !tool) 2.dp else 12.dp,
                        vertical = if (modern && !user && !tool) 2.dp else 10.dp,
                    ),
            ) {
                Column {
                    if (message.content.isNotBlank()) {
                        Text(
                            message.content,
                            style = if (tool) {
                                MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            color = when {
                                !modern && user -> Color.White
                                tool -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (message.attachments.isNotEmpty()) {
                        MessageAttachments(message.attachments, attachmentFile)
                    }
                }
            }
        }
    }
}

/** Скругления пузыря: компактные в классике, «капля» пользователя в современном дизайне. */
private fun bubbleShape(modern: Boolean, user: Boolean) = when {
    !modern -> RoundedCornerShape(10.dp)
    user -> RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp,
    )
    else -> RoundedCornerShape(20.dp)
}

/**
 * В современном дизайне ответ модели сливается с фоном, как в Gemini,
 * а в классическом это карточка с рамкой в духе GitHub-dark.
 */
@Composable
private fun bubbleBackground(modern: Boolean, user: Boolean, tool: Boolean): Color = when {
    modern && user -> MaterialTheme.colorScheme.surfaceVariant
    modern && tool -> MaterialTheme.colorScheme.surface
    modern -> Color.Transparent
    user -> ClassicUserBubble
    tool -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surface
}

/** Кружок-аватар ассистента с фирменным градиентом (современный дизайн). */
@Composable
private fun GeminiAvatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(Brush.linearGradient(ModernAvatarGradient), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = Color(0xFF041E49),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Приветствие пустого диалога в стиле Gemini. */
@Composable
private fun ModernGreeting() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.greeting_hello),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Medium,
                brush = Brush.linearGradient(ModernAvatarGradient),
            ),
        )
        Text(
            stringResource(R.string.greeting_help),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            if (local) {
                stringResource(R.string.empty_local_title)
            } else {
                stringResource(R.string.empty_agent_title)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (local) {
                stringResource(R.string.empty_local_body)
            } else {
                stringResource(R.string.empty_agent_body)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
