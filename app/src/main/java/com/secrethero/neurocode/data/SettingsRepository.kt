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

class SettingsRepository(context: Context) {
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
    )
    private val secrets = SecureSecretStore(context)
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun initialize() {
        var loaded = store.read()
        if (loaded.providers.isEmpty()) {
            val providers = ProviderCatalog.defaults()
            loaded = loaded.copy(
                providers = providers,
                selectedProviderId = providers.firstOrNull()?.id,
            )
            store.write(loaded)
        }
        _settings.value = loaded
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        store.write(updated)
        _settings.value = updated
    }

    suspend fun upsertProvider(provider: ProviderConfig, apiKey: String?) {
        val providers = _settings.value.providers
            .filterNot { it.id == provider.id } + provider
        apiKey?.let { secrets.put(provider.apiKeyName, it.trim()) }
        update {
            it.copy(
                providers = providers,
                selectedProviderId = it.selectedProviderId ?: provider.id,
            )
        }
    }

    suspend fun removeProvider(providerId: String) {
        val target = _settings.value.providers.firstOrNull { it.id == providerId }
        target?.let { secrets.delete(it.apiKeyName) }
        update {
            val providers = it.providers.filterNot { provider -> provider.id == providerId }
            it.copy(
                providers = providers,
                selectedProviderId = if (it.selectedProviderId == providerId) {
                    providers.firstOrNull()?.id
                } else {
                    it.selectedProviderId
                },
            )
        }
    }

    fun apiKey(provider: ProviderConfig): String? = secrets.get(provider.apiKeyName)

    suspend fun saveGitToken(projectId: String, token: String) = withContext(Dispatchers.IO) {
        val name = "git-token-$projectId"
        if (token.isBlank()) secrets.delete(name) else secrets.put(name, token.trim())
    }

    suspend fun gitToken(projectId: String): String? = withContext(Dispatchers.IO) {
        secrets.get("git-token-$projectId")
    }
}
