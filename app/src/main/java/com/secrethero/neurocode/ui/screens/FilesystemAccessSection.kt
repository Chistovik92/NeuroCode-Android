package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ui.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Раздел «Доступ к файлам»: корни SAF с датой выдачи и пресетами (0.8.3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilesystemAccessSection(
    fsRootsMeta: List<SettingsViewModel.FsRootMeta>,
    onAddRoot: () -> Unit,
    onRemoveRoot: (String) -> Unit,
    onPresetDownloads: () -> Unit,
    onPresetDocuments: () -> Unit,
    onPresetPictures: () -> Unit,
    onPresetAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.fs_access_title))
        Text(
            stringResource(R.string.fs_access_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        
        if (fsRootsMeta.isEmpty()) {
            Text(
                stringResource(R.string.fs_roots_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        
        fsRootsMeta.forEach { meta ->
            FsRootCard(
                displayName = meta.displayName,
                uri = meta.uri,
                grantedAt = meta.grantedAt,
                onRemove = { onRemoveRoot(meta.uri) },
            )
        }
        
        Spacer(Modifier.height(4.dp))
        
        Text(
            stringResource(R.string.fs_presets_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onPresetDownloads) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.fs_preset_downloads))
            }
            OutlinedButton(onClick = onPresetDocuments) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.fs_preset_documents))
            }
            OutlinedButton(onClick = onPresetPictures) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.fs_preset_pictures))
            }
            OutlinedButton(onClick = onPresetAll) {
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.fs_preset_all))
            }
        }
        
        Spacer(Modifier.height(4.dp))
        
        FilledTonalButton(onClick = onAddRoot) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.fs_add_root))
        }
    }
}

@Composable
private fun FsRootCard(
    displayName: String,
    uri: String,
    grantedAt: Long,
    onRemove: () -> Unit,
) {
    val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    val grantedDate = dateFormat.format(Date(grantedAt))
    
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    stringResource(R.string.fs_granted_format, grantedDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    uri,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.fs_root_cd, displayName),
                )
            }
        }
    }
}
