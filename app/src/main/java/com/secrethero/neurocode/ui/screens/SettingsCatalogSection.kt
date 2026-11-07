package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.secrethero.neurocode.R
import com.secrethero.neurocode.data.SettingsCatalog
import com.secrethero.neurocode.model.AppSettings

/** Поиск по всем типизированным настройкам, включая значения по умолчанию. */
@Composable
fun SettingsCatalogSection(settings: AppSettings) {
    var query by remember { mutableStateOf("") }
    val entries = remember(settings, query) { SettingsCatalog.search(settings, query) }
    SectionTitle(stringResource(R.string.settings_catalog_title))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_catalog_search)) },
        singleLine = true,
    )
    entries.forEach { entry ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${entry.section} · ${entry.value}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    entry.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (entries.isEmpty()) {
        Text(
            stringResource(R.string.settings_catalog_empty),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
