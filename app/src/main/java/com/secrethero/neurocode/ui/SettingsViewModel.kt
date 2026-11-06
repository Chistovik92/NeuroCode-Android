package com.secrethero.neurocode.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.ImportedModel
import com.secrethero.neurocode.ai.LocalDocModelsClient
import com.secrethero.neurocode.ai.HuggingFaceModel
import com.secrethero.neurocode.ai.ModelCatalog
import com.secrethero.neurocode.ai.ModelSpec
import com.secrethero.neurocode.device.DeviceAbi
import com.secrethero.neurocode.device.DeviceProfile
import com.secrethero.neurocode.device.DeviceSnapshot
import com.secrethero.neurocode.device.DeviceSpecs
import com.secrethero.neurocode.device.ModelRecommender
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.model.AppDesign
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ExternalAgentTool
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.text.Charsets

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings

    private val deviceSpecs by lazy { DeviceSpecs(application) }

    private val _deviceSnapshot = MutableStateFlow<DeviceSnapshot?>(null)
    val deviceSnapshot: StateFlow<DeviceSnapshot?> = _deviceSnapshot.asStateFlow()

    /** Лимиты и расход по последнему ответу провайдера. */
    val modelLimits: StateFlow<com.secrethero.neurocode.model.ModelLimits?> =
        container.apiClient.limits

    /** Корни с метаданными (URI и timestamp). */
    data class FsRootMeta(val uri: String, val grantedAt: Long, val displayName: String)

    private val _fsRootsMeta = MutableStateFlow<List<FsRootMeta>>(emptyList())
    val fsRootsMeta: StateFlow<List<FsRootMeta>> = _fsRootsMeta.asStateFlow()

    /** Импортированные GGUF на устройстве — для экрана «Модели на устройстве». */
    private val _localModels = MutableStateFlow<List<ImportedModel>>(emptyList())
    val localModels: StateFlow<List<ImportedModel>> = _localModels.asStateFlow()

    /** Рекомендованные модели из встроенного каталога, помещающиеся в память телефона. */
    private val _catalogRecommendations = MutableStateFlow<List<ModelSpec>>(emptyList())
    val catalogRecommendations: StateFlow<List<ModelSpec>> =
        _catalogRecommendations.asStateFlow()

    /** Токен Hugging Face задан ли (для gated-моделей). */
    private val _hfTokenSet = MutableStateFlow(false)
    val hfTokenSet: StateFlow<Boolean> = _hfTokenSet.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { deviceSpecs.snapshot() }
                .onSuccess { _deviceSnapshot.value = it }
        }
        // Обновление метаданных корней при изменении настроек.
        // ВАЖНО: _fsRootsMeta объявлён выше init — collect на Main.immediate
        // выполняется синхронно и падал с NPE на неинициализированном поле.
        viewModelScope.launch {
            settings.collect { appSettings ->
                val roots = container.filesystem.roots(fsRootsUris(appSettings))
                _fsRootsMeta.value = roots.map { root ->
                    val timestamp = appSettings.fsRootsWithTimestamps[root.uri] ?: System.currentTimeMillis()
                    FsRootMeta(root.uri, timestamp, root.displayName)
                }
            }
        }
        refreshLocalModels()
        refreshCatalogRecommendations()
        refreshHfToken()
    }

    private val _modelImportProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val modelImportProgress: StateFlow<Pair<Long, Long>?> = _modelImportProgress.asStateFlow()

    /** Видимые проекты — для показа, какие проекты используют ту или иную модель. */
    val projects = container.projects.projects

    private val _providerModels =
        MutableStateFlow<ProviderModelsState>(ProviderModelsState(emptyList(), null, false))
    val providerModels: StateFlow<ProviderModelsState> = _providerModels.asStateFlow()

    fun loadProviderModels(provider: ProviderConfig) = viewModelScope.launch {
        _providerModels.value = _providerModels.value.copy(loading = true)
        val key = container.settings.apiKey(provider).orEmpty()
        runCatching {
            container.apiClient.models(provider, key)
        }.onSuccess { models ->
            _providerModels.value = ProviderModelsState(models, null, false)
        }.onFailure { error ->
            _providerModels.value = ProviderModelsState(
                emptyList(),
                error.message ?: error::class.java.simpleName,
                false,
            )
        }
    }

    fun selectProvider(providerId: String) = updateSettings {
        it.copy(selectedProviderId = providerId, useLocalModel = false)
    }

    fun setUseLocalModel(enabled: Boolean) = updateSettings { it.copy(useLocalModel = enabled) }
    fun setAgentMode(enabled: Boolean) = updateSettings { it.copy(agentMode = enabled) }
    fun setAllowAgentShell(enabled: Boolean) = updateSettings { it.copy(allowAgentShell = enabled) }
    fun setMaxAgentSteps(value: Int) = updateSettings { it.copy(maxAgentSteps = value.coerceIn(1, 20)) }
    fun setThemeMode(mode: ThemeMode) = updateSettings { it.copy(themeMode = mode) }
    fun setAppDesign(design: AppDesign) = updateSettings { it.copy(appDesign = design) }
    fun setDynamicColor(enabled: Boolean) = updateSettings { it.copy(dynamicColor = enabled) }

    fun setWifiOnlyDownloads(enabled: Boolean) = updateSettings { it.copy(wifiOnlyDownloads = enabled) }

    fun setSkillsEnabled(enabled: Boolean) = updateSettings { it.copy(skillsEnabled = enabled) }

    fun saveSkill(skill: AgentSkill) = updateSettings { current ->
        current.copy(skills = current.skills.filterNot { it.id == skill.id } + skill)
    }

    fun deleteSkill(skillId: String) = updateSettings { current ->
        current.copy(skills = current.skills.filterNot { it.id == skillId })
    }

    fun toggleSkill(skillId: String, enabled: Boolean) = updateSettings { current ->
        current.copy(
            skills = current.skills.map {
                if (it.id == skillId) it.copy(enabled = enabled) else it
            },
        )
    }

    /** Установка готового скилла из библиотеки (0.9.2) в один тап. */
    fun installLibrarySkill(skillId: String) = updateSettings { current ->
        val skill = com.secrethero.neurocode.ai.SkillLibrary.byId(skillId) ?: return@updateSettings current
        if (current.skills.any { it.id == skill.id }) return@updateSettings current
        current.copy(skills = current.skills + skill)
    }

    /** Импорт скилла из текста `SKILL.md` (0.9.2): заголовок — имя, остальное — инструкция. */
    fun importSkillMarkdown(text: String): AgentSkill? {
        val parsed = com.secrethero.neurocode.ai.SkillMarkdown.parse(text) ?: return null
        updateSettings { current ->
            current.copy(skills = current.skills.filterNot { it.name == parsed.name } + parsed)
        }
        return parsed
    }

    /** Читает `SKILL.md` из выбранного через SAF файла и импортирует (0.9.2). */
    fun importSkillMarkdownFile(uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("Не удалось открыть файл")
            val parsed = importSkillMarkdown(text)
                ?: error("Не удалось разобрать SKILL.md: нужен заголовок «# Имя» и текст")
            container.bus.showNotice(
                getApplication<Application>().getString(R.string.notice_skill_imported_format, parsed.name),
            )
        }.onFailure(container.bus::showError)
    }

    /**
     * Предпросмотр итогового промпта (0.9.2): блок активных навыков для выбранного проекта,
     * как его увидит модель. Пустая строка, если навыков нет или они выключены.
     */
    fun skillsPreview(): String {
        val current = settings.value
        return com.secrethero.neurocode.ai.SkillPrompt.block(
            current.skills,
            current.skillsEnabled,
            current.selectedProjectId,
        ).trim()
    }

    fun saveExternalTool(tool: ExternalAgentTool) = updateSettings { current ->
        current.copy(externalTools = current.externalTools.filterNot { it.id == tool.id } + tool)
    }

    fun deleteExternalTool(toolId: String) = updateSettings { current ->
        current.copy(externalTools = current.externalTools.filterNot { it.id == toolId })
    }

    fun toggleExternalTool(toolId: String, enabled: Boolean) = updateSettings { current ->
        current.copy(
            externalTools = current.externalTools.map {
                if (it.id == toolId) it.copy(enabled = enabled) else it
            },
        )
    }

    fun setPostChecksEnabled(enabled: Boolean) = updateSettings { it.copy(postChecksEnabled = enabled) }

    fun setPostCheckCommands(raw: String) = updateSettings {
        it.copy(postCheckCommands = raw.lines().map { line -> line.trim() }.filter { it.isNotEmpty() })
    }

    fun setFallbackProvider(providerId: String?) = updateSettings { it.copy(fallbackProviderId = providerId) }

    /** Выданные модели корни файловой системы (0.8.2). */
    val fsRoots: StateFlow<List<String>> = fsRootsFlow(container.settings.settings)

    private fun fsRootsFlow(flow: StateFlow<AppSettings>): StateFlow<List<String>> =
        MutableStateFlow(fsRootsUris(flow.value)).also { mapped ->
            viewModelScope.launch {
                flow.collect { mapped.value = fsRootsUris(it) }
            }
        }

    private fun fsRootsUris(settings: AppSettings): List<String> =
        if (settings.fsRootsWithTimestamps.isNotEmpty()) {
            settings.fsRootsWithTimestamps.keys.toList()
        } else {
            @Suppress("DEPRECATION")
            settings.fsRoots
        }

    fun addFsRoot(uri: Uri) = viewModelScope.launch {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            container.settings.update { current ->
                val uriString = uri.toString()
                // Миграция: если старый формат есть, переносим в новый
                @Suppress("DEPRECATION")
                val migrated = if (current.fsRootsWithTimestamps.isEmpty() && current.fsRoots.isNotEmpty()) {
                    current.fsRoots.associateWith { System.currentTimeMillis() }
                } else {
                    current.fsRootsWithTimestamps
                }
                
                if (uriString in migrated) {
                    @Suppress("DEPRECATION")
                    current.copy(
                        fsRootsWithTimestamps = migrated,
                        fsRoots = emptyList()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    current.copy(
                        fsRootsWithTimestamps = (migrated + (uriString to System.currentTimeMillis()))
                            .toList().takeLast(FS_ROOTS_LIMIT).associate { it.first to it.second },
                        fsRoots = emptyList()
                    )
                }
            }
            container.bus.showNotice(
                getApplication<Application>().getString(R.string.fs_root_added)
            )
        }.onFailure(container.bus::showError)
    }

    fun removeFsRoot(uri: String) = viewModelScope.launch {
        runCatching {
            val app = getApplication<Application>()
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            container.settings.update { current ->
                val updated = current.fsRootsWithTimestamps - uri
                @Suppress("DEPRECATION")
                current.copy(
                    fsRootsWithTimestamps = updated,
                    fsRoots = if (updated.isEmpty()) current.fsRoots - uri else emptyList()
                )
            }
        }.onFailure(container.bus::showError)
    }
    
    /** Создаёт Intent для выбора стандартной папки Android. */
    fun createPresetIntent(type: PresetType): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val initialUri = when (type) {
            PresetType.DOWNLOADS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toUri()
            PresetType.DOCUMENTS -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toUri()
            PresetType.PICTURES -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toUri()
            PresetType.ALL -> Environment.getExternalStorageDirectory().toUri()
        }
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        return intent
    }
    
    enum class PresetType {
        DOWNLOADS,
        DOCUMENTS,
        PICTURES,
        ALL
    }

    val prootState = container.prootManager.state

    val prootFailure = container.prootManager.failure

    val prootProgress = container.prootManager.progress

    val lspDiagnostics = container.lsp.diagnostics

    fun setLspEnabled(enabled: Boolean) = updateSettings { it.copy(lspEnabled = enabled) }

    fun setLspCommand(raw: String) = updateSettings {
        it.copy(lspCommand = raw.trim())
    }

    fun installLinuxEnvironment() = viewModelScope.launch {
        runCatching {
            val ok = container.prootManager.initialize()
            if (ok) {
                container.bus.showNotice(
                    getApplication<Application>().getString(R.string.notice_linux_ready),
                )
            } else {
                container.bus.showNotice(
                    getApplication<Application>().getString(R.string.notice_linux_failed),
                )
            }
        }.onFailure(container.bus::showError)
    }

    fun resetLinuxEnvironment() = viewModelScope.launch {
        runCatching { container.prootManager.reset() }
    }

    /** Размер cacheDir (загрузки, временные файлы) — для раздела «Среда выполнения». */
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    fun refreshCacheSize() = viewModelScope.launch {
        _cacheSizeBytes.value = withContext(Dispatchers.IO) {
            getApplication<Application>().cacheDir?.walkBottomUp()?.filter { it.isFile }
                ?.sumOf { it.length() } ?: 0L
        }
    }

    /** Очищает cacheDir (установленный rootfs лежит в filesDir и не трогается). */
    fun clearCache() = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                getApplication<Application>().cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            }
        }.onSuccess {
            container.bus.showNotice(
                getApplication<Application>().getString(R.string.notice_cache_cleared),
            )
        }.onFailure(container.bus::showError)
        refreshCacheSize()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    private data class SkillsBackup(val version: Int, val skills: List<AgentSkill>)

    fun exportSkills(uri: Uri) = viewModelScope.launch {
        runCatching {
            val backup = SkillsBackup(version = 1, skills = settings.value.skills)
            val jsonString = json.encodeToString(SkillsBackup.serializer(), backup)
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(jsonString.toByteArray(Charsets.UTF_8))
            } ?: error("Не удалось открыть файл для записи")
        }.onSuccess { container.bus.showNotice("Скиллы экспортированы") }
            .onFailure(container.bus::showError)
    }

    fun importSkills(uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("Не удалось открыть файл")
            val backup = json.decodeFromString(SkillsBackup.serializer(), text)
            container.settings.update { current ->
                val byId = current.skills.associateBy { it.id }.toMutableMap()
                backup.skills.forEach { byId[it.id] = it.copy(enabled = true) }
                current.copy(skills = byId.values.toList())
            }
        }.onSuccess { container.bus.showNotice("Скиллы импортированы") }
            .onFailure(container.bus::showError)
    }

    fun saveProvider(provider: ProviderConfig, apiKey: String?) = viewModelScope.launch {
        runCatching { container.settings.upsertProvider(provider, apiKey) }
            .onFailure(container.bus::showError)
    }

    fun removeProvider(providerId: String) = viewModelScope.launch {
        runCatching { container.settings.removeProvider(providerId) }
            .onFailure(container.bus::showError)
    }

    fun removeApiKey(providerId: String) = viewModelScope.launch {
        runCatching { container.settings.removeApiKey(providerId) }
            .onFailure(container.bus::showError)
    }

    fun refreshLocalModels() = viewModelScope.launch {
        _localModels.value = runCatching { container.localLlama.listModels() }.getOrDefault(emptyList())
    }

    /** Свободное место в каталоге моделей — для сводки памяти. */
    fun modelsFreeBytes(): Long = container.localLlama.modelsFreeBytes() ?: -1L

    /** Делает модель активной локальной GGUF (переключает глобальную локальную модель). */
    fun selectLocalModel(model: ImportedModel) = updateSettings {
        it.copy(localModelPath = model.path, localModelName = model.name, useLocalModel = true)
    }

    /** Проекты, которые используют эту модель как переопределение модели проекта. */
    fun projectsUsing(model: ImportedModel): List<Project> =
        container.projects.projectsUsingLocalModel(model.path, model.name)

    /**
     * Удаляет модель: сначала файл (неудачное удаление — ошибка, а не молчаливый сброс),
     * затем проекты, диалоги и глобальные настройки. Недавние/избранные чистятся от битой ссылки.
     */
    fun deleteLocalModel(model: ImportedModel) = viewModelScope.launch {
        runCatching {
            val deleted = container.localLlama.deleteModel(model.path)
            if (deleted) {
                container.projects.resetLocalModel(model.path, model.name)
                container.chats.resetLocalModel(model.path, model.name)
                container.settings.update { current ->
                    val isGlobal = current.localModelPath == model.path ||
                        current.localModelName == model.name
                    val favorites = current.favoriteModels.filterNot {
                        it.kind == ModelKind.LOCAL && it.localName == model.name
                    }
                    val recent = current.recentModels.filterNot {
                        it.kind == ModelKind.LOCAL && it.localName == model.name
                    }
                    if (isGlobal || favorites != current.favoriteModels || recent != current.recentModels) {
                        current.copy(
                            localModelPath = if (isGlobal) null else current.localModelPath,
                            localModelName = if (isGlobal) null else current.localModelName,
                            useLocalModel = if (isGlobal) false else current.useLocalModel,
                            favoriteModels = favorites,
                            recentModels = recent,
                        )
                    } else {
                        current
                    }
                }
            }
        }.onFailure(container.bus::showError)
        refreshLocalModels()
    }

    fun importLocalModel(uri: Uri) = viewModelScope.launch {
        runCatching {
            _modelImportProgress.value = 0L to -1L
            val imported = container.localLlama.importModel(uri) { copied, total ->
                _modelImportProgress.value = copied to total
            }
            container.settings.update {
                it.copy(
                    localModelPath = imported.path,
                    localModelName = imported.name,
                    useLocalModel = true,
                )
            }
            _deviceSnapshot.value?.let { snapshot ->
                val recommendation = snapshot.recommendation()
                val sizeMb = imported.size / DeviceProfile.MB
                if (sizeMb > recommendation.maxModelSizeMb) {
                    container.bus.showNotice(
                        getApplication<Application>().getString(
                            R.string.notice_model_oversize_format,
                            sizeMb,
                            recommendation.maxModelSizeMb,
                        ),
                    )
                }
            }
        }.onFailure(container.bus::showError)
        _modelImportProgress.value = null
        refreshLocalModels()
    }

    // --- LocalDoc AI: модели, уже скачанные его приложением, — импорт без повторной загрузки ---

    private val _localDocState = MutableStateFlow<LocalDocState>(LocalDocState.Loading)
    val localDocState: StateFlow<LocalDocState> = _localDocState.asStateFlow()

    // --- 0.9.1: каталог моделей Hugging Face и подбор под устройство ---

    /** Состояние живого поиска по Hugging Face. */
    private val _hfSearchState = MutableStateFlow<HfSearchState>(HfSearchState.Idle)
    val hfSearchState: StateFlow<HfSearchState> = _hfSearchState.asStateFlow()

    /** Прогресс загрузки модели из каталога (скачано, всего). */
    private val _catalogDownloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val catalogDownloadProgress: StateFlow<Pair<Long, Long>?> = _catalogDownloadProgress.asStateFlow()

    /** Скачивается ли модель из каталога прямо сейчас (по id). */
    private val _catalogDownloadingId = MutableStateFlow<String?>(null)
    val catalogDownloadingId: StateFlow<String?> = _catalogDownloadingId.asStateFlow()

    fun refreshCatalogRecommendations() = viewModelScope.launch {
        _catalogRecommendations.value = withContext(Dispatchers.IO) {
            val snapshot = runCatching { deviceSpecs.snapshot() }.getOrNull()
            val profile = snapshot?.recommendation()
                ?: DeviceProfile.recommendModel(
                    totalMemoryMb = DeviceProfile.LOW_RAM_TOTAL_MB,
                    cores = 4,
                    abi = DeviceAbi.ARM64,
                    reportedLowRam = false,
                )
            ModelRecommender.recommendedFor(ModelCatalog.starter, profile)
        }
    }

    fun refreshHfToken() = viewModelScope.launch {
        _hfTokenSet.value = !container.settings.huggingFaceToken().isNullOrBlank()
    }

    fun saveHuggingFaceToken(token: String) = viewModelScope.launch {
        runCatching { container.settings.saveHuggingFaceToken(token) }
            .onFailure(container.bus::showError)
        refreshHfToken()
    }

    fun searchHuggingFace(query: String) = viewModelScope.launch {
        if (query.isBlank()) {
            _hfSearchState.value = HfSearchState.Idle
            return@launch
        }
        _hfSearchState.value = HfSearchState.Loading
        runCatching { container.huggingFace.search(query) }
            .onSuccess { models -> _hfSearchState.value = HfSearchState.Ready(models) }
            .onFailure { error ->
                _hfSearchState.value =
                    HfSearchState.Failed(error.message ?: error::class.java.simpleName)
            }
    }

    /**
     * По репозиторию из результатов поиска: список файлов → выбор квантизации под устройство →
     * загрузка и активация. Для gated-репозитория без токена загрузка вернёт понятную ошибку.
     */
    fun downloadHfRepo(repoId: String) = viewModelScope.launch {
        runCatching {
            val profile = runCatching { deviceSpecs.snapshot() }.getOrNull()
                ?.recommendation()
                ?: DeviceProfile.recommendModel(
                    totalMemoryMb = DeviceProfile.LOW_RAM_TOTAL_MB,
                    cores = 4,
                    abi = DeviceAbi.ARM64,
                    reportedLowRam = false,
                )
            val files = container.huggingFace.listFiles(repoId)
            val file = container.huggingFace.pickFile(files, profile.maxModelSizeMb)
                ?: error("Нет подходящего GGUF в репозитории $repoId")
            _catalogDownloadingId.value = repoId
            _catalogDownloadProgress.value = 0L to file.sizeBytes
            val name = file.path.substringAfterLast('/').let {
                if (it.endsWith(".gguf", true)) it else "$it.gguf"
            }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val target = java.io.File(container.localLlama.modelsDir(), name)
            val url = "https://huggingface.co/$repoId/resolve/main/${file.path}"
            val downloaded = container.huggingFace.download(
                url = url,
                target = target,
                expectedSha256 = file.sha256,
                wifiOnly = settings.value.wifiOnlyDownloads,
            ) { copied, total -> _catalogDownloadProgress.value = copied to total }
            container.settings.update {
                it.copy(
                    localModelPath = downloaded.absolutePath,
                    localModelName = downloaded.name,
                    useLocalModel = true,
                )
            }
        }.onFailure(container.bus::showError)
        _catalogDownloadingId.value = null
        _catalogDownloadProgress.value = null
        refreshLocalModels()
    }

    /**
     * Скачивает модель из встроенного каталога: выбирает файл под устройство (Q4_K_M → …),
     * докачивает по Range, сверяет magic GGUF и переносит в files/models, затем делает активной.
     */
    fun downloadCatalogModel(spec: ModelSpec) = viewModelScope.launch {
        runCatching {
            _catalogDownloadingId.value = spec.id
            _catalogDownloadProgress.value = 0L to spec.sizeBytes
            val target = java.io.File(
                container.localLlama.modelsDir(),
                spec.fileName,
            )
            val downloaded = container.huggingFace.download(
                url = spec.url,
                target = target,
                expectedSha256 = null,
                wifiOnly = settings.value.wifiOnlyDownloads,
            ) { copied, total -> _catalogDownloadProgress.value = copied to total }
            container.settings.update {
                it.copy(
                    localModelPath = downloaded.absolutePath,
                    localModelName = downloaded.name,
                    useLocalModel = true,
                )
            }
        }.onFailure(container.bus::showError)
        _catalogDownloadingId.value = null
        _catalogDownloadProgress.value = null
        refreshLocalModels()
    }

    fun refreshLocalDocModels() = viewModelScope.launch {
        _localDocState.value = if (!container.localDoc.isInstalled()) {
            LocalDocState.NotInstalled
        } else {
            LocalDocState.Ready(container.localDoc.list())
        }
    }

    /** Импорт модели LocalDoc AI копированием в files/models (тот же путь, что и SAF-импорт). */
    fun importLocalDocModel(model: LocalDocModelsClient.LocalDocModel) = viewModelScope.launch {
        runCatching {
            val input = container.localDoc.open(model.id)
                ?: error(getApplication<Application>().getString(R.string.error_localdoc_gone))
            input.use { stream ->
                _modelImportProgress.value = 0L to model.sizeBytes
                val imported = container.localLlama.importStream(
                    stream,
                    model.name,
                    model.sizeBytes,
                ) { copied, total ->
                    _modelImportProgress.value = copied to total
                }
                container.settings.update {
                    it.copy(
                        localModelPath = imported.path,
                        localModelName = imported.name,
                        useLocalModel = true,
                    )
                }
            }
        }.onFailure(container.bus::showError)
        _modelImportProgress.value = null
        refreshLocalModels()
        refreshLocalDocModels()
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch {
            runCatching { container.settings.update(transform) }
                .onFailure(container.bus::showError)
        }
}

data class ProviderModelsState(
    val models: List<String>,
    val error: String?,
    val loading: Boolean,
)

/** Состояние группы «LocalDoc AI» в «Моделях на устройстве». */
sealed interface LocalDocState {
    data object Loading : LocalDocState
    data object NotInstalled : LocalDocState
    data class Ready(val models: List<LocalDocModelsClient.LocalDocModel>) : LocalDocState
}

/** Состояние живого поиска моделей на Hugging Face (0.9.1). */
sealed interface HfSearchState {
    data object Idle : HfSearchState
    data object Loading : HfSearchState
    data class Ready(val models: List<HuggingFaceModel>) : HfSearchState
    data class Failed(val message: String) : HfSearchState
}

private const val FS_ROOTS_LIMIT = 10
