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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.ui.ProviderModelsState

private const val ZERO_WIDTH_SPACE_CODE = 0x200B
private val ZERO_WIDTH_SPACE = ZERO_WIDTH_SPACE_CODE.toChar().toString()
private const val FREE_SUFFIX = ":free"
private const val CLASSIC_RADIUS_DP = 10
private const val MODERN_RADIUS_DP = 28
private const val DIALOG_HEIGHT_FRACTION = 0.88f

/** Добавляет точки переноса после `/` и `:`, чтобы длинный `org/model:tag` не ломался посреди слова. */
internal fun breakableModelId(id: String): String =
    id.replace("/", "/$ZERO_WIDTH_SPACE").replace(":", ":$ZERO_WIDTH_SPACE")

internal fun isFreeModel(id: String): Boolean = id.endsWith(FREE_SUFFIX, ignoreCase = true)

/**
 * Окно выбора модели. Цвета и скругления берутся из активной темы: в современном дизайне — контейнерные
 * цвета Material 3 без рамок, в классическом — панели с рамкой (GitHub-dark).
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val modern = settings.appDesign == AppDesign.MODERN
    var query by remember { mutableStateOf("") }
    val provider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }
    val allModels = state?.models.orEmpty()
    val models = allModels.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    val loading = state?.loading == true
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(DIALOG_HEIGHT_FRACTION),
            shape = dialogShape(modern),
            color = if (modern) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.background
            },
            border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                SwitcherHeader(onDismiss)
                CurrentModelCard(provider, loading, modern) { provider?.let(onRefresh) }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    settings.providers.forEach { item ->
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
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
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
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                state?.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (!loading && state?.error == null && models.isEmpty()) {
                    val emptyText = if (allModels.isEmpty()) R.string.model_list_empty else R.string.model_search_empty
                    Text(
                        stringResource(emptyText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(models, key = { it }) { model ->
                        ModelRow(
                            model = model,
                            selected = model == provider?.model,
                            modern = modern,
                            onClick = {
                                provider?.let { onModel(it, model) }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun dialogShape(modern: Boolean): Shape =
    RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp)

@Composable
private fun SwitcherHeader(onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.model_picker_title),
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

/** Текущая модель отдельной карточкой; кнопка «Обновить» вынесена в свою строку и не сжимается идентификатором. */
@Composable
private fun CurrentModelCard(
    provider: ProviderConfig?,
    loading: Boolean,
    modern: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(if (modern) 20.dp else CLASSIC_RADIUS_DP.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.model_current),
                style = MaterialTheme.typography.labelMedium,
            )
            provider?.let {
                Text(it.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                provider?.model?.takeIf { it.isNotBlank() }?.let(::breakableModelId)
                    ?: stringResource(R.string.model_not_selected),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRefresh, enabled = provider != null && !loading) {
                    Text(stringResource(if (loading) R.string.loading else R.string.action_refresh))
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: String, selected: Boolean, modern: Boolean, onClick: () -> Unit) {
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
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                breakableModelId(model),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (isFreeModel(model)) {
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
        }
    }
}
