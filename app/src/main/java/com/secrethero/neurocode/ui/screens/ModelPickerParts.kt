package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.ModelProblem
import com.secrethero.neurocode.ai.ModelResolver
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.ui.ProviderModelsState

@Composable
internal fun SwitcherHeader(retry: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (retry) R.string.retry_model_title else R.string.model_picker_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.model_picker_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}


internal fun problemText(problem: ModelProblem): Int = when (problem) {
    ModelProblem.NOTHING_SELECTED -> R.string.model_problem_nothing_selected
    ModelProblem.NO_PROVIDER -> R.string.model_problem_no_provider
    ModelProblem.PROVIDER_DISABLED -> R.string.model_problem_provider_disabled
    ModelProblem.NO_KEY -> R.string.model_problem_no_key
    ModelProblem.NO_MODEL_ID -> R.string.model_problem_no_model_id
    ModelProblem.LOCAL_NOT_IMPORTED -> R.string.model_problem_local_not_imported
    ModelProblem.LOCAL_FILE_MISSING -> R.string.model_problem_local_file_missing
}


/** Избранные и последние пять моделей — переключение одним касанием. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickChoices(picker: ModelPickerModel, onChoose: (ModelRef) -> Unit) {
    val favorites = picker.settings.favoriteModels
    val recent = picker.settings.recentModels.filterNot { item -> favorites.any { it.key == item.key } }
    val items = favorites + recent
    if (items.isEmpty()) return
    Text(
        stringResource(R.string.model_recent),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { ref ->
            val favorite = favorites.any { it.key == ref.key }
            FilterChip(
                selected = picker.active.ref?.key == ref.key,
                onClick = { onChoose(ref) },
                label = { Text(ModelResolver.shortLabel(ref).orEmpty(), maxLines = 1) },
                leadingIcon = if (favorite) {
                    { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}


@Composable
internal fun ScopeRow(projectName: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column {
            Text(stringResource(R.string.model_apply_project), style = MaterialTheme.typography.bodyMedium)
            Text(
                projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Suppress("LongParameterList")
@Composable
internal fun ModelRow(
    title: String,
    selected: Boolean,
    free: Boolean,
    favorite: Boolean,
    modern: Boolean,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)?,
    subtitle: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            modern -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(if (modern) 16.dp else 6.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (free) {
                Text(
                    stringResource(R.string.model_free_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.model_selected_cd),
                    modifier = Modifier.size(18.dp),
                )
            }
            onFavorite?.let {
                IconButton(onClick = it) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(R.string.model_favorite_cd),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Ошибка загрузки списка и пустые состояния облачного списка моделей. */
@Composable
internal fun CloudListStatus(state: ProviderModelsState?, loading: Boolean, visibleCount: Int) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    state?.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (!loading && state?.error == null && visibleCount == 0) {
        val noModels = state?.models.orEmpty().isEmpty()
        val emptyText = if (noModels) R.string.model_list_empty else R.string.model_search_empty
        Text(
            stringResource(emptyText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
