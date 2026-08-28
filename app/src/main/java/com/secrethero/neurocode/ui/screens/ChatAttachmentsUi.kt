package com.secrethero.neurocode.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ModelLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Компактная строка с окном контекста, расходом токенов и остатком лимитов провайдера. */
@Composable
internal fun ModelLimitsBar(limits: ModelLimits) {
    val parts = buildList {
        limits.contextWindow?.let { add(stringResource(R.string.limits_context, formatTokens(it))) }
        limits.totalTokens?.let { add(stringResource(R.string.limits_last_request, formatTokens(it))) }
        limits.requestsRemaining?.let { remaining ->
            val limit = limits.requestsLimit
            add(
                if (limit != null) {
                    stringResource(R.string.limits_requests_of, remaining, limit)
                } else {
                    stringResource(R.string.limits_requests, remaining)
                },
            )
        }
        limits.tokensRemaining?.let { add(stringResource(R.string.limits_tokens_left, it)) }
    }
    if (parts.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Файлы, выбранные для следующего сообщения; каждый можно снять до отправки. */
@Composable
internal fun PendingAttachments(
    attachments: List<ChatAttachment>,
    attachmentFile: (ChatAttachment) -> File?,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AttachmentPreview(attachment, attachmentFile(attachment), size = 28.dp)
                    Column {
                        Text(
                            attachment.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp),
                        )
                        Text(
                            formatSize(attachment.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onRemove(attachment.id) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.remove_attachment_cd),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Превью картинки или иконка типа файла. */
@Composable
private fun AttachmentPreview(attachment: ChatAttachment, file: File?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.id, file?.path) {
        value = if (attachment.kind == AttachmentKind.IMAGE && file != null) {
            withContext(Dispatchers.IO) { decodeThumbnail(file) }
        } else {
            null
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surface, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (attachment.kind) {
                    AttachmentKind.IMAGE -> Icons.Default.Image
                    AttachmentKind.TEXT -> Icons.Default.Description
                    AttachmentKind.BINARY -> Icons.Default.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * THUMB_ICON_RATIO),
            )
        }
    }
}

/** Вложения внутри отправленного сообщения. */
@Composable
internal fun MessageAttachments(
    attachments: List<ChatAttachment>,
    attachmentFile: (ChatAttachment) -> File?,
) {
    Column(
        Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AttachmentPreview(
                    attachment = attachment,
                    file = attachmentFile(attachment),
                    size = if (attachment.kind == AttachmentKind.IMAGE) 96.dp else 32.dp,
                )
                Column {
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                    Text(
                        formatSize(attachment.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Размышления модели: свёрнуты по умолчанию, разворачиваются по нажатию. */
@Composable
internal fun ReasoningBlock(text: String, initiallyExpanded: Boolean = false) {
    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    stringResource(R.string.reasoning_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Text(
                    text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun decodeThumbnail(file: File): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val largest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (largest / sample > THUMB_TARGET_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}.getOrNull()

private fun formatSize(bytes: Long): String = when {
    bytes >= BYTES_IN_MB -> "${bytes / BYTES_IN_MB} МБ"
    bytes >= BYTES_IN_KB -> "${bytes / BYTES_IN_KB} КБ"
    else -> "$bytes Б"
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= TOKENS_IN_MILLION -> "${tokens / TOKENS_IN_MILLION}M"
    tokens >= TOKENS_IN_THOUSAND -> "${tokens / TOKENS_IN_THOUSAND}k"
    else -> tokens.toString()
}

private const val BYTES_IN_KB = 1024L
private const val BYTES_IN_MB = 1024L * 1024
private const val THUMB_TARGET_PX = 256
private const val THUMB_ICON_RATIO = 0.6f
private const val TOKENS_IN_THOUSAND = 1_000
private const val TOKENS_IN_MILLION = 1_000_000
