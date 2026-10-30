package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R

/** Раздел «Доступ к файлам»: корни SAF, которые модель может читать вне проекта (0.8.2). */
@Composable
internal fun FilesystemAccessSection(
    fsRoots: List<String>,
    onAddRoot: () -> Unit,
    onRemoveRoot: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.fs_access_title))
        Text(
            stringResource(R.string.fs_access_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        if (fsRoots.isEmpty()) {
            Text(
                stringResource(R.string.fs_roots_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fsRoots.forEach { uri ->
            val label = remember(uri) {
                uri.substringAfterLast(':').substringAfterLast('/').ifBlank { uri }
            }
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(uri, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    IconButton(onClick = { onRemoveRoot(uri) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.fs_root_cd, label),
                        )
                    }
                }
            }
        }
        FilledTonalButton(onClick = onAddRoot) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.fs_add_root))
        }
    }
}
