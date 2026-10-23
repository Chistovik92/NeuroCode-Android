package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.ImportedModel
import com.secrethero.neurocode.ai.ModelProblem
import com.secrethero.neurocode.ai.ModelResolver
import com.secrethero.neurocode.ai.ModelSource
import com.secrethero.neurocode.ai.ResolvedModel
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.ui.ChatViewModel.ModelScope
import com.secrethero.neurocode.ui.ProviderModelsState

private const val ZERO_WIDTH_SPACE_CODE = 0x200B
private val ZERO_WIDTH_SPACE = ZERO_WIDTH_SPACE_CODE.toChar().toString()
private const val FREE_SUFFIX = ":free"
private const val CLASSIC_RADIUS_DP = 10
private const val MODERN_RADIUS_DP = 28
private const val DIALOG_HEIGHT_FRACTION = 0.9f
private const val BYTES_IN_MB = 1024L * 1024

/** Добавляет точки переноса после `/` и `:`, чтобы длинный `org/model:tag` не ломался посреди слова. */
internal fun breakableModelId(id: String): String =
    id.replace("/", "/$ZERO_WIDTH_SPACE").replace(":", ":$ZERO_WIDTH_SPACE")

internal fun isFreeModel(id: String): Boolean = id.endsWith(FREE_SUFFIX, ignoreCase = true)

/** Что показывает окно выбора модели. [retry] — выбор модели для повтора ответа, без области «диалог / проект». */
class ModelPickerModel(
    val settings: AppSettings,
    val active: ResolvedModel,
    val project: Project?,
    val cloudModels: ProviderModelsState?,
    val localModels: List<ImportedModel>,
    val retry: Boolean = false,
)

class ModelPickerActions(
    val onBrowseProvider: (ProviderConfig) -> Unit,
    val onRefresh: (ProviderConfig) -> Unit,
    val onSelect: (ModelRef, ModelScope) -> Unit,
    val onClear: (ModelScope) -> Unit,
    val onToggleFavorite: (ModelRef) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Окно выбора модели: облачные провайдеры и локальные GGUF в одном месте, область применения
 * (этот диалог или весь проект), недавние и избранные. Цвета и скругления берутся из активной темы.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
fun ModelSwitcherDialog(picker: ModelPickerModel, actions: ModelPickerActions) {
    val settings = picker.settings
    val modern = settings.appDesign == AppDesign.MODERN
    var query by remember { mutableStateOf("") }
    var forProject by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(picker.active.isLocal) }
    var browsedId by remember {
        mutableStateOf(
            picker.active.provider?.id ?: settings.selectedProviderId ?: settings.providers.firstOrNull()?.id,
        )
    }
    val browsed = settings.providers.firstOrNull { it.id == browsedId }
    val allModels = picker.cloudModels?.models.orEmpty()
    val models = allModels.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    val loading = picker.cloudModels?.loading == true
    val scope = if (forProject && !picker.retry) ModelScope.PROJECT else ModelScope.DIALOG
    val choose: (ModelRef) -> Unit = { ref ->
        actions.onSelect(ref, scope)
        actions.onDismiss()
    }
    Dialog(onDismissRequest = actions.onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(DIALOG_HEIGHT_FRACTION),
            shape = RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp),
            color = if (modern) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.background
            },
            border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                SwitcherHeader(picker.retry, actions.onDismiss)
                CurrentModelCard(picker, browsed, loading, modern, actions)
                QuickChoices(picker, choose)
                if (picker.project != null && !picker.retry) {
                    ScopeRow(picker.project.name, forProject) { forProject = it }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    settings.providers.forEach { item ->
                        FilterChip(
                            selected = !showLocal && item.id == browsedId,
                            onClick = {
                                showLocal = false
                                browsedId = item.id
                                query = ""
                                actions.onBrowseProvider(item)
                            },
                            label = { Text(item.name) },
                        )
                    }
                    FilterChip(
                        selected = showLocal,
                        onClick = { showLocal = true },
                        label = { Text(stringResource(R.string.model_chip_local)) },
                    )
                }
                if (showLocal) {
                    LocalModelList(picker, modern, choose)
                } else {
                    CloudModelList(
                        modifier = Modifier.weight(1f),
                        picker = picker,
                        browsed = browsed,
                        models = models,
                        query = query,
                        onQuery = { query = it },
                        loading = loading,
                        modern = modern,
                        onChoose = choose,
                        onToggleFavorite = actions.onToggleFavorite,
                    )
                }
            }
        }
    }
}

/** Действующая модель и откуда она взята; кнопки сброса возвращают диалог к проекту, а проект — к умолчанию. */
@Composable
private fun CurrentModelCard(
    picker: ModelPickerModel,
    browsed: ProviderConfig?,
    loading: Boolean,
    modern: Boolean,
    actions: ModelPickerActions,
) {
    val active = picker.active
    val sourceLabel = when (active.source) {
        ModelSource.DIALOG -> R.string.model_source_dialog
        ModelSource.PROJECT -> R.string.model_source_project
        ModelSource.GLOBAL -> R.string.model_source_global
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(if (modern) 20.dp else CLASSIC_RADIUS_DP.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(sourceLabel), style = MaterialTheme.typography.labelMedium)
            active.ref?.let {
                Text(
                    ModelResolver.fullLabel(it, picker.settings.providers),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                ModelResolver.shortLabel(active.ref)?.let(::breakableModelId)
                    ?: stringResource(R.string.model_not_selected),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            active.problem?.let {
                Text(
                    stringResource(R.string.model_unavailable_badge) + ": " + stringResource(problemText(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!picker.retry && active.source == ModelSource.DIALOG) {
                    TextButton(onClick = { actions.onClear(ModelScope.DIALOG) }) {
                        Text(stringResource(R.string.model_reset_dialog))
                    }
                }
                if (!picker.retry && picker.project?.model != null) {
                    TextButton(onClick = { actions.onClear(ModelScope.PROJECT) }) {
                        Text(stringResource(R.string.model_reset_project))
                    }
                }
                TextButton(onClick = { browsed?.let(actions.onRefresh) }, enabled = browsed != null && !loading) {
                    Text(stringResource(if (loading) R.string.loading else R.string.action_refresh))
                }
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun CloudModelList(
    picker: ModelPickerModel,
    browsed: ProviderConfig?,
    models: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    loading: Boolean,
    modern: Boolean,
    onChoose: (ModelRef) -> Unit,
    onToggleFavorite: (ModelRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = picker.cloudModels
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.search_model)) },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.model_search_clear),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        CloudListStatus(state, loading, models.size)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(models, key = { it }) { model ->
                val ref = browsed?.let { ModelRef.cloud(it.id, model) }
                ModelRow(
                    title = breakableModelId(model),
                    selected = ref != null && ref.key == picker.active.ref?.key,
                    free = isFreeModel(model),
                    favorite = ref != null && picker.settings.favoriteModels.any { it.key == ref.key },
                    modern = modern,
                    onClick = { ref?.let(onChoose) },
                    onFavorite = ref?.let { { onToggleFavorite(it) } },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.LocalModelList(
    picker: ModelPickerModel,
    modern: Boolean,
    onChoose: (ModelRef) -> Unit,
) {
    if (picker.localModels.isEmpty()) {
        Text(
            stringResource(R.string.model_local_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(picker.localModels, key = { it.path }) { local ->
            val ref = ModelRef.local(local.path, local.name)
            ModelRow(
                title = breakableModelId(local.name),
                subtitle = "${local.size / BYTES_IN_MB} MB",
                selected = ref.key == picker.active.ref?.key && picker.active.ref.kind == ModelKind.LOCAL,
                free = false,
                favorite = false,
                modern = modern,
                onClick = { onChoose(ref) },
                onFavorite = null,
            )
        }
    }
}
