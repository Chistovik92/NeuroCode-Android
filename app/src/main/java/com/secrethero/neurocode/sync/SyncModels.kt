package com.secrethero.neurocode.sync

import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import kotlinx.serialization.Serializable

/**
 * Полный payload синхронизации одного устройства: шифруется целиком и кладётся
 * в `sync_blobs.payload`. Секретов здесь нет по построению: API-ключи живут в Keystore,
 * в `AppSettings` их поля не сериализуются, refresh-токен — `CredentialKey.Account`.
 */
@Serializable
data class SyncPayload(
    /** Схема payload: при несовместимой версии — предложение обновить приложение. */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val deviceId: String,
    val deviceName: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val chats: List<ChatSession> = emptyList(),
    val projects: List<Project> = emptyList(),
    /**
     * Настройки без секретов: скиллы, пресеты, внешние инструменты, конфигурация
     * провайдеров (без ключей), выбор модели как [ModelRef], тема и т.п.
     */
    val settings: SyncSettings? = null,
    /** Удалённое на этом устройстве: чтобы не воскресло при мерже (tombstone). */
    val deletedChatIds: List<String> = emptyList(),
    val deletedProjectIds: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Часть [AppSettings], которую безопасно переносить между устройствами.
 * Собирается вручную из AppSettings (не сериализация целиком), поэтому новые
 * секретные поля по умолчанию не попадают в синк.
 */
@Serializable
data class SyncSettings(
    val skills: List<com.secrethero.neurocode.model.AgentSkill> = emptyList(),
    val skillsEnabled: Boolean = true,
    val externalTools: List<com.secrethero.neurocode.model.ExternalAgentTool> = emptyList(),
    /** Конфигурации провайдеров без API-ключей (ключи — только в Keystore устройства). */
    val providers: List<com.secrethero.neurocode.model.ProviderConfig> = emptyList(),
    val presets: List<com.secrethero.neurocode.model.AgentPreset> = emptyList(),
    val selectedProviderId: String? = null,
    /** Модели как ссылки (облако/GGUF/LocalDoc AI): файл локальной модели не переносится. */
    val selectedModel: ModelRef? = null,
    val recentModels: List<ModelRef> = emptyList(),
    val favoriteModels: List<ModelRef> = emptyList(),
    val themeMode: com.secrethero.neurocode.model.ThemeMode? = null,
    val appDesign: com.secrethero.neurocode.model.AppDesign? = null,
    val dynamicColor: Boolean? = null,
    val agentMode: Boolean? = null,
    val allowAgentShell: Boolean? = null,
    val maxAgentSteps: Int? = null,
    val agentPermissionMode: com.secrethero.neurocode.model.AgentPermissionMode? = null,
    val planMode: Boolean? = null,
    val wifiOnlyDownloads: Boolean? = null,
    /** Настройки самой синхронизации не синхронизируются (включение, Wi-Fi-only — локальные). */
)
