package com.secrethero.neurocode.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.secrethero.neurocode.ui.components.MarkdownText
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import java.io.File

/**
 * Современная панель ввода в стиле Gemini: пока текст короткий — одна строка
 * («+», поле, микрофон, отправка); с длинным или многострочным текстом поле занимает
 * всю ширину «таблетки», а кнопки уходят в строку под ним. Поле одно и то же в обоих
 * вариантах (movableContentOf), поэтому фокус и клавиатура не сбрасываются.
 */
@Composable
internal fun ModernInputBar(state: InputBarState) {
    val current by rememberUpdatedState(state)
    val field = remember {
        movableContentOf<Modifier> { modifier -> ModernInputField(current, modifier) }
    }
    val expanded = state.value.contains('\n') || state.value.length > EXPAND_AFTER_CHARS
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            if (expanded) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    field(Modifier.fillMaxWidth())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AttachButton(state)
                        Spacer(Modifier.weight(1f))
                        SendButton(state)
                    }
                }
            } else {
                Row(
                    Modifier.padding(start = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachButton(state)
                    field(Modifier.weight(1f))
                    if (state.value.isBlank() && !state.busy) {
                        IconButton(onClick = state.onVoice) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.voice_input_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    SendButton(state)
                }
            }
        }
    }
}

private const val EXPAND_AFTER_CHARS = 26

@Composable
private fun ModernInputField(state: InputBarState, modifier: Modifier) {
    TextField(
        value = state.value,
        onValueChange = state.onValue,
        modifier = modifier,
        placeholder = {
            Text(
                state.placeholder,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        minLines = 1,
        maxLines = 6,
    )
}

@Composable
private fun AttachButton(state: InputBarState) {
    IconButton(onClick = state.onAttach) {
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.attach_files_cd),
            tint = if (state.hasAttachments) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SendButton(state: InputBarState) {
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

/**
 * Сообщение в стиле Gemini: пользователь — пузырь справа (копирование долгим нажатием),
 * модель — Markdown-текст без подложки под градиентным значком и со строкой действий.
 */
@Suppress("LongMethod")
@Composable
internal fun ModernMessage(
    message: ChatMessage,
    user: Boolean,
    attachmentFile: (ChatAttachment) -> File?,
    onCopy: () -> Unit,
) {
    if (user) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        bubbleShape(modern = true, user = true),
                    )
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onCopy() }) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column {
                    if (message.content.isNotBlank()) {
                        Text(message.content, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (message.attachments.isNotEmpty()) {
                        MessageAttachments(message.attachments, attachmentFile)
                    }
                }
            }
        }
        return
    }
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GeminiAvatar()
        message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningBlock(text = it) }
        SelectionContainer {
            MarkdownText(
                message.content,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            )
        }
        if (message.attachments.isNotEmpty()) {
            MessageAttachments(message.attachments, attachmentFile)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageAction(Icons.Default.ContentCopy, stringResource(R.string.copy_message_cd), onCopy)
            MessageAction(Icons.Default.Share, stringResource(R.string.share_message_cd)) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, message.content)
                context.startActivity(Intent.createChooser(send, null))
            }
        }
    }
}

@Composable
private fun MessageAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Пояснение под последним ответом, как в Gemini. */
@Composable
internal fun AiDisclaimer() {
    Text(
        stringResource(R.string.ai_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Значок модели, мягко пульсирующий, пока идёт генерация. */
@Composable
internal fun PulsingAvatar() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.alpha(pulse)) { GeminiAvatar() }
}

/** Приветствие пустого диалога в стиле Gemini с карточками-подсказками. */
@Composable
internal fun ModernGreeting(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        Icons.Default.Lightbulb to stringResource(R.string.suggest_explain),
        Icons.Default.BugReport to stringResource(R.string.suggest_bugs),
        Icons.Default.Science to stringResource(R.string.suggest_tests),
        Icons.Default.Source to stringResource(R.string.suggest_diff),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.greeting_hello),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Medium,
                brush = Brush.linearGradient(ModernAvatarGradient),
            ),
        )
        Text(
            stringResource(R.string.greeting_help),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.padding(top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            suggestions.forEach { (icon, text) -> SuggestionCard(icon, text) { onSuggestion(text) } }
        }
    }
}

@Composable
private fun SuggestionCard(icon: ImageVector, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
