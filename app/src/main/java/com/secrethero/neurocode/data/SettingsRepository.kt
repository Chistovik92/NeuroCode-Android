package com.secrethero.neurocode.data

import android.content.Context
import com.secrethero.neurocode.ai.ProviderCatalog
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
import java.io.File

class SettingsRepository(context: Context, onCorrupt: (File) -> Unit = {}) {
    private val store = JsonFileStore(
        file = context.filesDir.resolve("state/settings.json"),
        serializer = serializer<AppSettings>(),
        defaultValue = {
            val providers = ProviderCatalog.defaults()
            AppSettings(
                providers = providers,
                selectedProviderId = providers.firstOrNull()?.id,
            )
        },
        onCorrupt = onCorrupt,
    )
    private val secrets = SecureSecretStore(context)
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Провайдеры с непустым API-ключом; пересчитывается на IO, чтобы не трогать Keystore на главном потоке. */
    private val _connectedProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedProviderIds: StateFlow<Set<String>> = _connectedProviderIds.asStateFlow()

    suspend fun initialize() {
        var loaded = store.read()
        val existingIds = loaded.providers.map { it.id }.toSet()
        val missing = ProviderCatalog.defaults().filter {
            it.id !in existingIds && it.id !in loaded.removedDefaultProviderIds
        }
        if (loaded.providers.isEmpty()) {
            val providers = ProviderCatalog.defaults()
            loaded = loaded.copy(
                providers = providers,
                selectedProviderId = providers.firstOrNull()?.id,
            )
            store.write(loaded)
        } else if (missing.isNotEmpty()) {
            loaded = loaded.copy(providers = loaded.providers + missing)
            store.write(loaded)
        }
        _settings.value = loaded
        refreshConnectedProviders()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        store.write(updated)
        _settings.value = updated
        refreshConnectedProviders()
    }

    suspend fun upsertProvider(provider: ProviderConfig, apiKey: String?) {
        val providers = _settings.value.providers
            .filterNot { it.id == provider.id } + provider
        if (apiKey != null) {
            withContext(Dispatchers.IO) { secrets.put(provider.apiKeyName, apiKey.trim()) }
        }
        update {
            it.copy(
                providers = providers,
                selectedProviderId = it.selectedProviderId ?: provider.id,
            )
        }
    }

    suspend fun removeProvider(providerId: String) {
        val target = _settings.value.providers.firstOrNull { it.id == providerId }
        if (target != null) {
            withContext(Dispatchers.IO) { secrets.delete(target.apiKeyName) }
        }
        update {
            val providers = it.providers.filterNot { provider -> provider.id == providerId }
            it.copy(
                providers = providers,
                selectedProviderId = if (it.selectedProviderId == providerId) {
                    providers.firstOrNull()?.id
                } else {
                    it.selectedProviderId
                },
                removedDefaultProviderIds = if (providerId in ProviderCatalog.defaultIds) {
                    (it.removedDefaultProviderIds + providerId).distinct()
                } else {
                    it.removedDefaultProviderIds
                },
            )
        }
    }

    /** Читает ключ вне главного потока — Keystore и AES-GCM выполняются на IO. */
    suspend fun apiKey(provider: ProviderConfig): String? = withContext(Dispatchers.IO) {
        secrets.get(provider.apiKeyName)
    }

    /** Удаляет только API-ключ провайдера, не трогая его настройки. */
    suspend fun removeApiKey(providerId: String) {
        withContext(Dispatchers.IO) {
            _settings.value.providers.firstOrNull { it.id == providerId }
                ?.let { secrets.delete(it.apiKeyName) }
        }
        refreshConnectedProviders()
    }

    private suspend fun refreshConnectedProviders() {
        _connectedProviderIds.value = withContext(Dispatchers.IO) {
            _settings.value.providers.mapNotNull { provider ->
                runCatching { secrets.get(provider.apiKeyName) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { provider.id }
            }.toSet()
        }
    }

    suspend fun saveGitToken(projectId: String, token: String) = withContext(Dispatchers.IO) {
        val name = "git-token-$projectId"
        if (token.isBlank()) secrets.delete(name) else secrets.put(name, token.trim())
    }

    suspend fun gitToken(projectId: String): String? = withContext(Dispatchers.IO) {
        secrets.get("git-token-$projectId")
    }
}
