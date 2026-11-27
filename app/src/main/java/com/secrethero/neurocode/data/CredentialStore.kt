package com.secrethero.neurocode.data

/** Тип секрета и его владелец. Значение никогда не входит в ключ и не сериализуется в настройки. */
sealed interface CredentialKey {
    val storageName: String

    data class Provider(val providerId: String) : CredentialKey {
        // Сохраняем прежнее имя: существующие ключи остаются доступны после обновления.
        override val storageName: String get() = providerId
    }

    data class Mcp(val serverId: String) : CredentialKey {
        override val storageName: String get() = "mcp:$serverId"
    }

    data class Git(val projectId: String) : CredentialKey {
        override val storageName: String get() = "git-token-$projectId"
    }

    data object HuggingFace : CredentialKey {
        override val storageName: String get() = "huggingface-token"
    }

    /** Refresh-токен аккаунта синхронизации (0.9.5). */
    data object Account : CredentialKey {
        override val storageName: String get() = "account:refresh-token"
    }

    /** Ключ E2E-шифрования синхронизации (0.9.5): генерируется на первом устройстве. */
    data object SyncKey : CredentialKey {
        override val storageName: String get() = "account:sync-key"
    }
}

/** Единая точка хранения ключей API, MCP/Git-токенов и Hugging Face в Android Keystore. */
class CredentialStore(private val store: SecureSecretStore) {
    fun put(key: CredentialKey, value: String) = store.put(key.storageName, value.trim())

    fun get(key: CredentialKey): String? = store.get(key.storageName)

    fun delete(key: CredentialKey) = store.delete(key.storageName)
}
