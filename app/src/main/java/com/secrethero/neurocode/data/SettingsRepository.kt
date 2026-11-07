package com.secrethero.neurocode.data

import android.content.Context
import com.secrethero.neurocode.ai.ProviderCatalog
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val secrets = CredentialStore(SecureSecretStore(context))
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Провайдеры с непустым API-ключом; пересчитывается на IO, чтобы не трогать Keystore на главном потоке. */
    private val _connectedProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedProviderIds: StateFlow<Set<String>> = _connectedProviderIds.asStateFlow()

    suspend fun initialize() {
        var loaded = store.read()
        if (loaded.settingsSchemaVersion < SettingsCatalog.CURRENT_SCHEMA_VERSION) {
            @Suppress("DEPRECATION")
            loaded = loaded.copy(
                settingsSchemaVersion = SettingsCatalog.CURRENT_SCHEMA_VERSION,
                fsRootsWithTimestamps = loaded.fsRootsWithTimestamps.ifEmpty {
                    loaded.fsRoots.associateWith { System.currentTimeMillis() }
                },
                fsRoots = emptyList(),
            )
            store.write(loaded)
        }
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

    /**
     * Все обновления — под мьютексом и через атомарный CAS: параллельные `update`
     * из нескольких ViewModel раньше теряли изменения между чтением и записью.
     */
    suspend fun update(transform: suspend (AppSettings) -> AppSettings) = mutex.withLock {
        val updated = transform(_settings.value)
        store.write(updated)
        _settings.value = updated
        refreshConnectedProvidersLocked()
    }

    suspend fun upsertProvider(provider: ProviderConfig, apiKey: String?) {
        if (apiKey != null) {
            withContext(Dispatchers.IO) { secrets.put(CredentialKey.Provider(provider.apiKeyName), apiKey) }
        }
        update {
            val providers = it.providers.filterNot { p -> p.id == provider.id } + provider
            it.copy(
                providers = providers,
                selectedProviderId = it.selectedProviderId ?: provider.id,
            )
        }
    }

    suspend fun removeProvider(providerId: String) {
        val target = _settings.value.providers.firstOrNull { it.id == providerId }
        if (target != null) {
            withContext(Dispatchers.IO) { secrets.delete(CredentialKey.Provider(target.apiKeyName)) }
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
        secrets.get(CredentialKey.Provider(provider.apiKeyName))
    }

    /** Удаляет только API-ключ провайдера, не трогая его настройки. */
    suspend fun removeApiKey(providerId: String) {
        withContext(Dispatchers.IO) {
            _settings.value.providers.firstOrNull { it.id == providerId }
                ?.let { secrets.delete(CredentialKey.Provider(it.apiKeyName)) }
        }
        mutex.withLock { refreshConnectedProvidersLocked() }
    }

    private val mutex = Mutex()

    private suspend fun refreshConnectedProviders() {
        mutex.withLock { refreshConnectedProvidersLocked() }
    }

    private suspend fun refreshConnectedProvidersLocked() {
        _connectedProviderIds.value = withContext(Dispatchers.IO) {
            _settings.value.providers.mapNotNull { provider ->
                runCatching { secrets.get(CredentialKey.Provider(provider.apiKeyName)) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { provider.id }
            }.toSet()
        }
    }

    suspend fun saveGitToken(projectId: String, token: String) = withContext(Dispatchers.IO) {
        val key = CredentialKey.Git(projectId)
        if (token.isBlank()) secrets.delete(key) else secrets.put(key, token)
    }

    suspend fun gitToken(projectId: String): String? = withContext(Dispatchers.IO) {
        secrets.get(CredentialKey.Git(projectId))
    }

    /** Токен Hugging Face (для gated-моделей); хранится в Keystore как остальные секреты. */
    suspend fun saveHuggingFaceToken(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            secrets.delete(CredentialKey.HuggingFace)
        } else {
            secrets.put(CredentialKey.HuggingFace, token)
        }
    }

    suspend fun huggingFaceToken(): String? = withContext(Dispatchers.IO) {
        secrets.get(CredentialKey.HuggingFace)
    }

    /** Общий интерфейс для токенов будущих MCP-серверов (0.10); в JSON они не сохраняются. */
    suspend fun saveMcpToken(serverId: String, token: String) = withContext(Dispatchers.IO) {
        val key = CredentialKey.Mcp(serverId)
        if (token.isBlank()) secrets.delete(key) else secrets.put(key, token)
    }

    suspend fun mcpToken(serverId: String): String? = withContext(Dispatchers.IO) {
        secrets.get(CredentialKey.Mcp(serverId))
    }
}
