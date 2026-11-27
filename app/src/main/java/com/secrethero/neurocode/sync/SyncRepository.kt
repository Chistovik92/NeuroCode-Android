package com.secrethero.neurocode.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.secrethero.neurocode.ai.ModelResolver
import com.secrethero.neurocode.data.ChatRepository
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.data.SettingsRepository
import com.secrethero.neurocode.model.AppSettings
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Результат одной синхронизации — для статусной строки в «Аккаунте». */
data class SyncOutcome(
    val success: Boolean,
    val message: String,
    val chatsApplied: Int = 0,
    val chatsDeleted: Int = 0,
    val projectsApplied: Int = 0,
    val projectsDeleted: Int = 0,
)

sealed interface SyncState {
    data object Idle : SyncState
    data object InProgress : SyncState
    data class Done(val outcome: SyncOutcome) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Движок синхронизации 0.9.5: собирает payload из локальных репозиториев, шифрует через
 * [SyncCrypto] и кладёт в `sync_blobs` Supabase (PostgREST upsert по `(user_id, device_id)`),
 * скачивает свежую запись другого устройства и мержит по правилам [SyncMerge]
 * (LWW + tombstones). Сервер видит только шифртекст и метаданные.
 */
class SyncRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val chats: ChatRepository,
    private val projects: ProjectRepository,
    private val account: AccountRepository,
    private val http: OkHttpClient,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val inFlight = AtomicBoolean(false)

    /** Tombstones между синками: накапливаются, очищаются после успешной выгрузки. */
    private val deletedChatIds = mutableSetOf<String>()
    private val deletedProjectIds = mutableSetOf<String>()

    fun rememberDeletedChat(id: String) = synchronized(deletedChatIds) { deletedChatIds.add(id) }

    fun rememberDeletedProject(id: String) = synchronized(deletedProjectIds) { deletedProjectIds.add(id) }

    /** Ручной запуск («Синхронизировать сейчас») и автосинк после изменений диалогов. */
    suspend fun synchronize(): SyncOutcome = mutex.withLock {
        if (!inFlight.compareAndSet(false, true)) {
            return SyncOutcome(false, "Синхронизация уже выполняется")
        }
        try {
            _state.value = SyncState.InProgress
            val outcome = runSync()
            _state.value = if (outcome.success) SyncState.Done(outcome) else SyncState.Failed(outcome.message)
            outcome
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runSync(): SyncOutcome {
        val config = settings.settings.value
        if (!config.syncEnabled) return SyncOutcome(false, "Синхронизация выключена")
        val baseUrl = config.syncSupabaseUrl.trimEnd('/')
        val anonKey = config.syncSupabaseAnonKey
        if (baseUrl.isBlank() || anonKey.isBlank()) {
            return SyncOutcome(false, "Синхронизация не настроена")
        }
        if (config.syncWifiOnly && !isWifi()) {
            return SyncOutcome(false, "Только Wi‑Fi: мобильная сеть пропущена")
        }
        val token = account.accessToken() ?: return SyncOutcome(false, "Нет активной сессии")
        val key = account.syncKeyBytes() ?: return SyncOutcome(false, "Нет ключа синхронизации")
        val userId = account.currentUser()?.userId ?: return SyncOutcome(false, "Нет активной сессии")
        val deviceId = ensureDeviceId(config)

        return try {
            val local = buildLocalPayload(deviceId)
            val remote = fetchRemote(baseUrl, anonKey, token, userId, deviceId)
            val outcome = when {
                remote == null -> {
                    // Первая синхронизация этого устройства: выгружаем локальное состояние.
                    upload(baseUrl, anonKey, token, userId, deviceId, local, key)
                    SyncOutcome(true, "Выгружено: ${local.chats.size} диалогов, ${local.projects.size} проектов")
                }
                else -> {
                    val remoteBytes = SyncCrypto.decrypt(key, remote.getString("payload"))
                        ?: return SyncOutcome(false, "Ключ синхронизации не подходит к данным на сервере")
                    val parsed = json.decodeFromString(SyncPayload.serializer(), String(remoteBytes, Charsets.UTF_8))
                    if (parsed.deviceId == deviceId) {
                        // Наша же запись (например, после переустановки): перезаливаем локальное.
                        upload(baseUrl, anonKey, token, userId, deviceId, local, key)
                        SyncOutcome(true, "Локальное состояние выгружено заново")
                    } else {
                        val result = SyncMerge.merge(local, parsed)
                        applyRemote(result)
                        upload(baseUrl, anonKey, token, userId, deviceId, result.merged, key)
                        SyncOutcome(
                            success = true,
                            message = "Синхронизировано: +${result.chatsToApply.size}/−${result.chatsToDelete.size} диалогов",
                            chatsApplied = result.chatsToApply.size,
                            chatsDeleted = result.chatsToDelete.size,
                            projectsApplied = result.projectsToApply.size,
                            projectsDeleted = result.projectsToDelete.size,
                        )
                    }
                }
            }
            if (outcome.success) {
                settings.update { it.copy(syncLastSuccessAt = now()) }
                synchronized(deletedChatIds) { deletedChatIds.clear() }
                synchronized(deletedProjectIds) { deletedProjectIds.clear() }
            }
            outcome
        } catch (e: Exception) {
            // Tombstones сохраняются: неудачный синк не должен «воскресить» удалённое.
            SyncOutcome(false, "Синхронизация не удалась: ${e.message}")
        }
    }

    private suspend fun ensureDeviceId(config: AppSettings): String {
        config.syncDeviceId.takeIf { it.isNotBlank() }?.let { return it }
        val generated = account.newDeviceId()
        settings.update { it.copy(syncDeviceId = generated) }
        return generated
    }

    private suspend fun buildLocalPayload(deviceId: String): SyncPayload = SyncPayload(
        deviceId = deviceId,
        deviceName = android.os.Build.MODEL,
        chats = chats.sessions.value,
        projects = projects.projects.value,
        settings = syncSettings(settings.settings.value),
        deletedChatIds = synchronized(deletedChatIds) { deletedChatIds.toList() },
        deletedProjectIds = synchronized(deletedProjectIds) { deletedProjectIds.toList() },
    )

    private suspend fun applyRemote(result: SyncMerge.Result) {
        result.chatsToDelete.forEach { chats.delete(it) }
        result.projectsToDelete.forEach { projects.delete(it) }
        result.chatsToApply.forEach { chats.upsertRemote(it) }
        result.projectsToApply.forEach { projects.upsertRemote(it) }
        synchronized(deletedChatIds) { deletedChatIds.removeAll(result.chatsToDelete.toSet()) }
        synchronized(deletedProjectIds) { deletedProjectIds.removeAll(result.projectsToDelete.toSet()) }
    }

    private fun isWifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Часть [AppSettings] без секретов, безопасная для переноса между устройствами. */
    private fun syncSettings(source: AppSettings): SyncSettings = SyncSettings(
        skills = source.skills,
        skillsEnabled = source.skillsEnabled,
        externalTools = source.externalTools,
        providers = source.providers,
        presets = source.presets,
        selectedProviderId = source.selectedProviderId,
        selectedModel = ModelResolver.globalRef(source),
        recentModels = source.recentModels,
        favoriteModels = source.favoriteModels,
        themeMode = source.themeMode,
        appDesign = source.appDesign,
        dynamicColor = source.dynamicColor,
        agentMode = source.agentMode,
        allowAgentShell = source.allowAgentShell,
        maxAgentSteps = source.maxAgentSteps,
        agentPermissionMode = source.agentPermissionMode,
        planMode = source.planMode,
        wifiOnlyDownloads = source.wifiOnlyDownloads,
    )

    // ==== PostgREST: таблица sync_blobs ====

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun fetchRemote(
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        userId: String,
        deviceId: String,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rest/v1/sync_blobs" +
            "?select=*&user_id=eq.$userId&device_id=neq.$deviceId&order=updated_at.desc&limit=1"
        val request = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val array = JSONArray(response.body?.string().orEmpty())
            if (array.length() == 0) null else array.getJSONObject(0)
        }
    }

    private suspend fun upload(
        baseUrl: String,
        anonKey: String,
        accessToken: String,
        userId: String,
        deviceId: String,
        payload: SyncPayload,
        key: ByteArray,
    ): Unit = withContext(Dispatchers.IO) {
        val plaintext = json.encodeToString(SyncPayload.serializer(), payload)
        val envelope = SyncCrypto.encrypt(key, plaintext.toByteArray(Charsets.UTF_8))
        val body = JSONObject().apply {
            put("user_id", userId)
            put("device_id", deviceId)
            put("device_name", android.os.Build.MODEL)
            put("payload", envelope)
            put("updated_at", java.time.Instant.ofEpochMilli(now()).toString())
        }.toString()
        val url = "$baseUrl/rest/v1/sync_blobs?on_conflict=user_id,device_id"
        val request = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toRequestBody(MEDIA_JSON))
            .build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Выгрузка не удалась: HTTP ${response.code}" }
        }
    }

    private companion object {
        val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
