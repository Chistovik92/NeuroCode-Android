package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.ImportedModel
import com.secrethero.neurocode.device.DeviceProfile
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.Project
import java.text.DateFormat
import java.util.Date

/** Проекты, у которых переопределение модели — эта локальная GGUF. */
internal fun projectsUsingLocalModel(model: ImportedModel, projects: List<Project>): List<Project> =
    projects.filter { project ->
        val ref = project.model
        ref?.kind == ModelKind.LOCAL &&
            (ref.localPath == model.path || ref.localName == model.name)
    }

private fun formatModelDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** Экран «Модели на устройстве»: список GGUF, активная модель, память, переключение и удаление. */
@Suppress("LongParameterList")
@Composable
internal fun LocalModelsSection(
    models: List<ImportedModel>,
    projects: List<Project>,
    activePath: String?,
    recommendedMb: Long,
    freeBytes: Long,
    onSelect: (ImportedModel) -> Unit,
    onDelete: (ImportedModel) -> Unit,
) {
    SectionTitle(stringResource(R.string.models_on_device))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.models_memory_title),
                fontWeight = FontWeight.SemiBold,
            )
            val usedMb = models.sumOf { it.size } / DeviceProfile.MB
            Text(
                stringResource(R.string.models_memory_used, usedMb),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.models_memory_free, freeBytes / DeviceProfile.MB),
                style = MaterialTheme.typography.bodySmall,
            )
            if (recommendedMb > 0) {
                Text(
                    stringResource(R.string.models_memory_recommended, recommendedMb),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (models.isEmpty()) {
        Text(
            stringResource(R.string.models_on_device_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        models.forEach { model ->
            LocalModelRow(
                model = model,
                active = model.path == activePath,
                usedBy = projectsUsingLocalModel(model, projects),
                onSelect = { onSelect(model) },
                onDelete = { onDelete(model) },
            )
        }
    }
}

@Composable
private fun LocalModelRow(
    model: ImportedModel,
    active: Boolean,
    usedBy: List<Project>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.name, fontWeight = FontWeight.SemiBold)
                    if (active) {
                        Text(
                            stringResource(R.string.models_active_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    stringResource(
                        R.string.models_size_date_format,
                        model.size / DeviceProfile.MB,
                        formatModelDate(model.lastModified),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (usedBy.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.models_used_by_format,
                            usedBy.joinToString { it.name },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!active) {
                FilledTonalButton(onClick = onSelect) {
                    Text(stringResource(R.string.models_use))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.models_delete_cd),
                )
            }
        }
    }
}
