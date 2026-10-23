package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R

/** Что показать под ответом ассистента: какая модель его сгенерировала и можно ли повторить другой. */
class MessageSignature(
    val modelLabel: String?,
    val onRetry: (() -> Unit)?,
)

/** Небольшая подпись под ответом; кнопка повтора в классическом дизайне стоит рядом с подписью. */
@Composable
internal fun ModelSignature(label: String?, onRetry: (() -> Unit)?) {
    if (label == null && onRetry == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        onRetry?.let {
            TextButton(onClick = it) {
                Text(stringResource(R.string.retry_other_model), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
