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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.HuggingFaceModel
import com.secrethero.neurocode.ai.ImportedModel
import com.secrethero.neurocode.ai.ModelSpec
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
    localDocState: com.secrethero.neurocode.ui.LocalDocState,
    activeLocalDocId: String?,
    activeLocalDocAuthority: String?,
    onSelect: (ImportedModel) -> Unit,
    onDelete: (ImportedModel) -> Unit,
    onUseLocalDoc: (com.secrethero.neurocode.ai.LocalDocModelsClient.LocalDocModel) -> Unit,
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
            if (freeBytes >= 0) {
                Text(
                    stringResource(R.string.models_memory_free, freeBytes / DeviceProfile.MB),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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

    LocalDocModelsSection(
        state = localDocState,
        activeLocalDocId = activeLocalDocId,
        activeLocalDocAuthority = activeLocalDocAuthority,
        onUse = onUseLocalDoc,
    )
}

/** Группа «LocalDoc AI»: модели, уже скачанные соседним приложением, — прямое использование без копии. */
@Composable
private fun LocalDocModelsSection(
    state: com.secrethero.neurocode.ui.LocalDocState,
    activeLocalDocId: String?,
    activeLocalDocAuthority: String?,
    onUse: (com.secrethero.neurocode.ai.LocalDocModelsClient.LocalDocModel) -> Unit,
) {
    SectionTitle(stringResource(R.string.localdoc_group_title))
    when (state) {
        com.secrethero.neurocode.ui.LocalDocState.Loading -> Unit
        com.secrethero.neurocode.ui.LocalDocState.NotInstalled -> Text(
            stringResource(R.string.localdoc_not_installed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is com.secrethero.neurocode.ui.LocalDocState.Ready -> {
            if (state.models.isEmpty()) {
                Text(
                    stringResource(R.string.localdoc_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.models.forEach { model ->
                    LocalDocModelRow(
                        model = model,
                        active = model.id == activeLocalDocId && model.authority == activeLocalDocAuthority,
                        onUse = { onUse(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalDocModelRow(
    model: com.secrethero.neurocode.ai.LocalDocModelsClient.LocalDocModel,
    active: Boolean,
    onUse: () -> Unit,
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
                        R.string.models_memory_used,
                        model.sizeBytes / DeviceProfile.MB,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) {
                FilledTonalButton(onClick = onUse) {
                    Text(stringResource(R.string.localdoc_use))
                }
            }
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

/**
 * «Рекомендовано для этого телефона» (0.9.1): модели встроенного каталога, помещающиеся
 * в память устройства, с кнопкой «Скачать» в один тап.
 */
@Composable
internal fun CatalogRecommendationsSection(
    models: List<ModelSpec>,
    downloadingId: String?,
    progress: Pair<Long, Long>?,
    onDownload: (ModelSpec) -> Unit,
) {
    SectionTitle(stringResource(R.string.catalog_recommended_title))
    if (models.isEmpty()) {
        Text(
            stringResource(R.string.catalog_recommended_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    models.forEach { spec ->
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            R.string.catalog_params_format,
                            spec.family.label,
                            spec.parameters,
                            spec.sizeMb,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        spec.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (downloadingId == spec.id) {
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.first.toFloat() / progress.second).coerceIn(0f, 1f) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.catalog_downloading),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    FilledTonalButton(onClick = { onDownload(spec) }) {
                        Text(stringResource(R.string.catalog_download))
                    }
                }
            }
        }
    }
}

/**
 * Живой поиск моделей на Hugging Face (0.9.1). [query] — уже введённый текст; [state] —
 * результат. Результат — список репозиториев, по каждому можно запросить файлы.
 */
@Composable
internal fun HuggingFaceSearchSection(
    query: String,
    onQueryChange: (String) -> Unit,
    state: com.secrethero.neurocode.ui.HfSearchState,
    onSearch: () -> Unit,
    onPickRepo: (HuggingFaceModel) -> Unit,
) {
    SectionTitle(stringResource(R.string.catalog_search_title))
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.catalog_search_hint)) },
        singleLine = true,
    )
    androidx.compose.material3.OutlinedButton(
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth(),
        enabled = query.isNotBlank(),
    ) {
        Text(stringResource(R.string.catalog_search_title))
    }
    when (state) {
        com.secrethero.neurocode.ui.HfSearchState.Idle -> Unit
        com.secrethero.neurocode.ui.HfSearchState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is com.secrethero.neurocode.ui.HfSearchState.Failed -> Text(
            stringResource(R.string.catalog_search_error_format, state.message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is com.secrethero.neurocode.ui.HfSearchState.Ready -> {
            if (state.models.isEmpty()) {
                Text(
                    stringResource(R.string.catalog_search_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.models.forEach { model ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.id, fontWeight = FontWeight.SemiBold)
                                if (model.gated) {
                                    Text(
                                        "gated",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            TextButton(onClick = { onPickRepo(model) }) {
                                Text(stringResource(R.string.catalog_download))
                            }
                        }
                    }
                }
            }
        }
    }
}
