package com.secrethero.neurocode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.sync.AccountState
import com.secrethero.neurocode.sync.AuthProvider
import com.secrethero.neurocode.sync.SyncState
import com.secrethero.neurocode.ui.SettingsViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Раздел «Аккаунт» (0.9.5): вход Google/GitHub, ключ синхронизации, конфигурация
 * Supabase и ручной/автосинк. Без входа ничего не меняется — анонимный режим
 * остаётся по умолчанию.
 */
@Composable
internal fun AccountSection(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val accountState by vm.accountState.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val syncKeyPhrase by vm.syncKeyPhrase.collectAsStateWithLifecycle()

    SectionTitle(stringResource(R.string.account_section_title))
    Text(
        stringResource(R.string.account_section_text),
        style = MaterialTheme.typography.bodyMedium,
    )

    when (val state = accountState) {
        is AccountState.SignedIn -> {
            val user = state.user
            Text(
                text = stringResource(
                    R.string.account_signed_in_format,
                    user.email ?: user.userId,
                    user.provider ?: "",
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        AccountState.SigningIn -> Text(
            stringResource(R.string.account_signing_in),
            style = MaterialTheme.typography.bodyMedium,
        )
        is AccountState.Error -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        AccountState.SignedOut -> Text(
            stringResource(R.string.account_signed_out),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AuthProvider.entries.forEach { provider ->
            OutlinedButton(onClick = { vm.signIn(provider) }) {
                Text(
                    when (provider) {
                        AuthProvider.GOOGLE -> stringResource(R.string.account_sign_in_google)
                        AuthProvider.GITHUB -> stringResource(R.string.account_sign_in_github)
                    },
                )
            }
        }
        if (accountState is AccountState.SigningIn) {
            TextButton(onClick = { vm.cancelSignIn() }) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }

    HorizontalDivider()

    // ==== Конфигурация Supabase ====
    var urlText by remember(settings.syncSupabaseUrl) { mutableStateOf(settings.syncSupabaseUrl) }
    var anonKeyText by remember(settings.syncSupabaseAnonKey) { mutableStateOf(settings.syncSupabaseAnonKey) }
    OutlinedTextField(
        value = urlText,
        onValueChange = { urlText = it },
        label = { Text(stringResource(R.string.account_supabase_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = anonKeyText,
        onValueChange = { anonKeyText = it },
        label = { Text(stringResource(R.string.account_supabase_anon_key)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.saveSyncConfig(urlText, anonKeyText) }) {
            Text(stringResource(R.string.action_save))
        }
    }

    HorizontalDivider()

    // ==== Ключ синхронизации ====
    Text(
        stringResource(R.string.account_sync_key_text),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (syncKeyPhrase != null) {
        Text(
            syncKeyPhrase.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.showSyncKey() }) {
            Text(stringResource(R.string.account_sync_key_show))
        }
        var importing by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { importing = true }) {
            Text(stringResource(R.string.account_sync_key_import))
        }
        if (importing) {
            ImportKeyDialog(
                onDismiss = { importing = false },
                onImport = {
                    vm.importSyncKey(it)
                    importing = false
                },
            )
        }
    }

    HorizontalDivider()

    // ==== Синхронизация ====
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.syncEnabled,
            onCheckedChange = { vm.setSyncEnabled(it) },
        )
        Text(stringResource(R.string.account_sync_enabled))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = settings.syncWifiOnly,
            onCheckedChange = { vm.setSyncWifiOnly(it) },
        )
        Text(stringResource(R.string.account_sync_wifi_only))
    }

    val lastSync = settings.syncLastSuccessAt
    if (lastSync != null) {
        Text(
            stringResource(
                R.string.account_sync_last_format,
                DateFormat.getDateTimeInstance().format(Date(lastSync)),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    when (val s = syncState) {
        is SyncState.Failed -> Text(
            s.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        is SyncState.Done -> Text(
            s.outcome.message,
            style = MaterialTheme.typography.bodySmall,
        )
        else -> Unit
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.syncNow() },
            enabled = settings.syncEnabled && accountState is AccountState.SignedIn,
        ) {
            Text(stringResource(R.string.account_sync_now))
        }
        OutlinedButton(onClick = { vm.signOut() }) {
            Text(stringResource(R.string.account_sign_out))
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    TextButton(onClick = { confirmDelete = true }) {
        Text(
            stringResource(R.string.account_delete_data),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete_data)) },
            text = { Text(stringResource(R.string.account_delete_data_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAccountData()
                    confirmDelete = false
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_sync_key_import)) },
        text = {
            Column {
                Text(stringResource(R.string.account_sync_key_import_text))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.account_sync_key_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
