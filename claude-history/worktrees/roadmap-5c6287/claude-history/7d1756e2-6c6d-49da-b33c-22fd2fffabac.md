# Session 7d1756e2-6c6d-49da-b33c-22fd2fffabac
- Project: `E:\Git\NeuroCode-Android--claude-worktrees-roadmap-5c6287`
- Source: `C:\Users\Chist\.claude\projects\E--Git-NeuroCode-Android--claude-worktrees-roadmap-5c6287\7d1756e2-6c6d-49da-b33c-22fd2fffabac.jsonl`

## User 2026-10-23T21:25:29.374Z
Review this change for security vulnerabilities.

Changed files (you may Read these and any other file in the repo):
  - app/src/main/java/com/secrethero/neurocode/ai/HistoryAdapter.kt
  - app/src/main/java/com/secrethero/neurocode/ai/LocalLlamaClient.kt
  - app/src/main/java/com/secrethero/neurocode/ai/ModelResolver.kt
  - app/src/main/java/com/secrethero/neurocode/ai/OpenAiCompatibleClient.kt
  - app/src/main/java/com/secrethero/neurocode/data/ChatRepository.kt
  - app/src/main/java/com/secrethero/neurocode/data/ProjectManifest.kt
  - app/src/main/java/com/secrethero/neurocode/data/ProjectRepository.kt
  - app/src/main/java/com/secrethero/neurocode/model/Models.kt
  - app/src/main/java/com/secrethero/neurocode/ui/ChatViewModel.kt
  - app/src/main/java/com/secrethero/neurocode/ui/NeuroCodeApp.kt
  - app/src/main/java/com/secrethero/neurocode/ui/screens/ChatModernUi.kt
  - app/src/main/java/com/secrethero/neurocode/ui/screens/ChatScreen.kt
  - app/src/main/java/com/secrethero/neurocode/ui/screens/MessageSignature.kt
  - app/src/main/java/com/secrethero/neurocode/ui/screens/ModelPickerParts.kt
  - app/src/main/java/com/secrethero/neurocode/ui/screens/ModelSwitcherDialog.kt
  - app/src/test/java/com/secrethero/neurocode/HistoryAdapterTest.kt
  - app/src/test/java/com/secrethero/neurocode/ModelResolverTest.kt

Unified diff (only + lines are new):

=== DIFF: app/src/main/java/com/secrethero/neurocode/ai/HistoryAdapter.kt ===
@@ -0,0 +1,103 @@
+package com.secrethero.neurocode.ai
+
+import com.secrethero.neurocode.model.AttachmentKind
+import com.secrethero.neurocode.model.ChatMessage
+import com.secrethero.neurocode.model.MessageRole
+
+/** Что умеет модель, для которой готовится история. */
+data class TargetCapabilities(
+    val supportsTools: Boolean,
+    val supportsImages: Boolean,
+    /** Окно контекста в токенах; null — неизвестно, история не сокращается. */
+    val contextTokens: Int?,
+)
+
+data class AdaptedHistory(
+    val messages: List<ChatMessage>,
+    /** Сколько старых сообщений не поместилось в окно контекста. */
+    val droppedMessages: Int,
+    /** Картинки убраны, потому что модель их не принимает. */
+    val imagesRemoved: Boolean,
+)
+
+/**
+ * Приводит историю диалога к виду, который понимает новая модель после переключения посреди диалога:
+ * размышления не повторяются, результаты инструментов предыдущего провайдера сворачиваются в
+ * текстовую сводку (если модель не умеет вызывать инструменты), история сокращается под окно контекста.
+ */
+object HistoryAdapter {
+    private const val CHARS_PER_TOKEN = 3
+    private const val MESSAGE_OVERHEAD_TOKENS = 4
+    private const val CONTEXT_SHARE_PERCENT = 75
+    private const val TOOL_SUMMARY_CHARS = 600
+
+    fun estimateTokens(message: ChatMessage): Int =
+        message.content.length / CHARS_PER_TOKEN + MESSAGE_OVERHEAD_TOKENS
+
+    fun adapt(history: List<ChatMessage>, target: TargetCapabilities): AdaptedHistory {
+        var imagesRemoved = false
+        val converted = history.map { message ->
+            var item = message.copy(reasoning = null, generatedBy = null)
+            if (item.role == MessageRole.TOOL && !target.supportsTools) {
+                item = item.copy(
+                    role = MessageRole.USER,
+                    content = "[сводка: результат инструмента ${item.toolName ?: "?"}] " +
+                        item.content.take(TOOL_SUMMARY_CHARS),
+                    toolName = null,
+                    toolCallId = null,
+                )
+            }
+            if (!target.supportsImages && item.attachments.any { it.kind == AttachmentKind.IMAGE }) {
+                imagesRemoved = true
+                val names = item.attachments.filter { it.kind == AttachmentKind.IMAGE }.joinToString { it.name }
+                item = item.copy(
+                    attachments = item.attachments.filterNot { it.kind == AttachmentKind.IMAGE },
+                    content = item.content + "\n[изображения недоступны для этой модели: $names]",
+                )
+            }
+            item
+        }
+        val fitted = fit(converted, target.contextTokens)
+        return AdaptedHistory(fitted, converted.size - fitted.size, imagesRemoved)
+    }
+
+    /** Оставляет самые свежие сообщения, которые помещаются в долю окна контекста; последнее — всегда. */
+    private fun fit(messages: List<ChatMessage>, contextTokens: Int?): List<ChatMessage> {
+        if (contextTokens == null || messages.isEmpty()) return messages
+        val budget = contextTokens * CONTEXT_SHARE_PERCENT / PERCENT
+        var used = 0
+        var start = messages.size
+        for (index in messages.indices.reversed()) {
+            used += estimateTokens(messages[index])
+            if (used > budget && index < messages.size - 1) break
+            start = index
+        }
+        return messages.subList(start, messages.size)
+    }
+
+    /**
+     * Прежний диалог одним текстом — для локальной модели, у которой своего контекста ещё нет
+     * (например, после перехода с облачной модели). Берутся самые свежие сообщения в пределах [maxChars].
+     */
+    fun transcript(messages: List<ChatMessage>, maxChars: Int): String {
+        val lines = messages.mapNotNull { message ->
+            when (message.role) {
+                MessageRole.USER -> "Пользователь: ${message.content}"
+                MessageRole.ASSISTANT -> "Ассистент: ${message.content}"
+                MessageRole.TOOL -> "Результат инструмента ${message.toolName ?: "?"}: " +
+                    message.content.take(TOOL_SUMMARY_CHARS)
+                MessageRole.SYSTEM -> null
+            }
+        }
+        val kept = ArrayDeque<String>()
+        var total = 0
+        for (line in lines.asReversed()) {
+            if (total + line.length > maxChars && kept.isNotEmpty()) break
+            kept.addFirst(line.take(maxChars))
+            total += line.length
+        }
+        return kept.joinToString("\n")
+    }
+
+    private const val PERCENT = 100
+}


=== DIFF: app/src/main/java/com/secrethero/neurocode/ai/LocalLlamaClient.kt ===
@@ -75,6 +75,20 @@ class LocalLlamaClient(
         ImportedModel(target.name, target.absolutePath, target.length())
     }
 
+    /** GGUF-модели, импортированные в `files/models`, — для окна выбора модели. */
+    suspend fun listModels(): List<ImportedModel> = withContext(Dispatchers.IO) {
+        modelsDirectory.listFiles { file -> file.isFile && file.extension.equals("gguf", ignoreCase = true) }
+            .orEmpty()
+            .sortedBy { it.name.lowercase() }
+            .map { ImportedModel(it.name, it.absolutePath, it.length()) }
+    }
+
+    /** true, если модель уже загружена именно для этого диалога и контекст в памяти движка актуален. */
+    fun isLoadedFor(path: String, conversationKey: String): Boolean =
+        loadedPath == File(path).canonicalPath &&
+            loadedConversationKey == conversationKey &&
+            engine.state.value.isModelLoaded
+
     suspend fun load(path: String, systemPrompt: String, conversationKey: String) {
         val canonical = File(path).canonicalPath
         if (


=== DIFF: app/src/main/java/com/secrethero/neurocode/ai/ModelResolver.kt ===
@@ -0,0 +1,129 @@
+package com.secrethero.neurocode.ai
+
+import com.secrethero.neurocode.model.AppSettings
+import com.secrethero.neurocode.model.ChatSession
+import com.secrethero.neurocode.model.ModelKind
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.model.Project
+import com.secrethero.neurocode.model.ProviderConfig
+import java.io.File
+
+/** Откуда взята действующая модель: цепочка «диалог → проект → глобально». */
+enum class ModelSource {
+    DIALOG,
+    PROJECT,
+    GLOBAL,
+}
+
+/** Почему выбранную модель нельзя использовать; молчаливой подмены чужой моделью нет. */
+enum class ModelProblem {
+    NOTHING_SELECTED,
+    NO_PROVIDER,
+    PROVIDER_DISABLED,
+    NO_KEY,
+    NO_MODEL_ID,
+    LOCAL_NOT_IMPORTED,
+    LOCAL_FILE_MISSING,
+}
+
+/**
+ * Итог выбора модели для запроса. [provider] уже содержит идентификатор модели из [ref],
+ * поэтому глобальная настройка провайдера не меняется.
+ */
+data class ResolvedModel(
+    val ref: ModelRef?,
+    val source: ModelSource,
+    val provider: ProviderConfig? = null,
+    val problem: ModelProblem? = null,
+) {
+    val ready: Boolean get() = ref != null && problem == null
+    val isLocal: Boolean get() = ref?.kind == ModelKind.LOCAL
+}
+
+object ModelResolver {
+    /** Глобальная модель по умолчанию из настроек (то, что было единственным выбором до 0.7.7). */
+    fun globalRef(settings: AppSettings): ModelRef? {
+        if (settings.useLocalModel) {
+            return settings.localModelPath?.let { ModelRef.local(it, settings.localModelName ?: File(it).name) }
+        }
+        return settings.providers.firstOrNull { it.id == settings.selectedProviderId }
+            ?.let { ModelRef.cloud(it.id, it.model) }
+    }
+
+    @Suppress("ReturnCount")
+    fun resolve(
+        session: ChatSession?,
+        project: Project?,
+        settings: AppSettings,
+        hasKey: (ProviderConfig) -> Boolean,
+        fileExists: (String) -> Boolean = { File(it).isFile },
+    ): ResolvedModel {
+        val (source, ref) = when {
+            session?.model != null -> ModelSource.DIALOG to session.model
+            project?.model != null -> ModelSource.PROJECT to project.model
+            else -> ModelSource.GLOBAL to globalRef(settings)
+        }
+        if (ref == null) {
+            val problem = if (settings.useLocalModel) ModelProblem.LOCAL_NOT_IMPORTED else ModelProblem.NOTHING_SELECTED
+            return ResolvedModel(null, source, problem = problem)
+        }
+        return when (ref.kind) {
+            ModelKind.LOCAL -> resolveLocal(ref, source, fileExists)
+            ModelKind.CLOUD -> resolveCloud(ref, source, settings, hasKey)
+        }
+    }
+
+    private fun resolveLocal(ref: ModelRef, source: ModelSource, fileExists: (String) -> Boolean): ResolvedModel {
+        val path = ref.localPath
+        val problem = when {
+            path.isNullOrBlank() -> ModelProblem.LOCAL_NOT_IMPORTED
+            !fileExists(path) -> ModelProblem.LOCAL_FILE_MISSING
+            else -> null
+        }
+        return ResolvedModel(ref, source, problem = problem)
+    }
+
+    private fun resolveCloud(
+        ref: ModelRef,
+        source: ModelSource,
+        settings: AppSettings,
+        hasKey: (ProviderConfig) -> Boolean,
+    ): ResolvedModel {
+        val base = settings.providers.firstOrNull { it.id == ref.providerId }
+            ?: return ResolvedModel(ref, source, problem = ModelProblem.NO_PROVIDER)
+        val provider = base.copy(model = ref.model?.takeIf { it.isNotBlank() } ?: base.model)
+        val problem = when {
+            !base.enabled -> ModelProblem.PROVIDER_DISABLED
+            provider.model.isBlank() -> ModelProblem.NO_MODEL_ID
+            !hasKey(base) -> ModelProblem.NO_KEY
+            else -> null
+        }
+        return ResolvedModel(ref, source, provider, problem)
+    }
+
+    /** Подпись для шапки и сообщений: у облачной — идентификатор модели, у локальной — имя файла. */
+    fun shortLabel(ref: ModelRef?): String? = when (ref?.kind) {
+        ModelKind.CLOUD -> ref.model
+        ModelKind.LOCAL -> ref.localName
+        null -> null
+    }
+
+    /** Полная подпись «провайдер · модель» для сообщения ассистента. */
+    fun fullLabel(ref: ModelRef, providers: List<ProviderConfig>): String = when (ref.kind) {
+        ModelKind.CLOUD -> {
+            val name = providers.firstOrNull { it.id == ref.providerId }?.name
+            listOfNotNull(name, ref.model).joinToString(" · ")
+        }
+        ModelKind.LOCAL -> "GGUF · ${ref.localName ?: ref.localPath.orEmpty()}"
+    }
+
+    /** Новый список «недавних»: выбранная модель первой, без повторов, не длиннее [limit]. */
+    fun pushRecent(
+        recent: List<ModelRef>,
+        ref: ModelRef,
+        limit: Int = AppSettings.RECENT_MODELS_LIMIT,
+    ): List<ModelRef> = (listOf(ref) + recent.filterNot { it.key == ref.key }).take(limit)
+
+    fun toggleFavorite(favorites: List<ModelRef>, ref: ModelRef): List<ModelRef> =
+        if (favorites.any { it.key == ref.key }) favorites.filterNot { it.key == ref.key } else favorites + ref
+}


=== DIFF: app/src/main/java/com/secrethero/neurocode/ai/OpenAiCompatibleClient.kt ===
@@ -60,6 +60,9 @@ class OpenAiCompatibleClient {
     private val contextWindows = mutableMapOf<String, Int>()
     private val maxOutputTokens = mutableMapOf<String, Int>()
 
+    /** Окно контекста модели из каталога провайдера; null, если провайдер его не отдавал. */
+    fun contextWindow(model: String): Int? = contextWindows[model]
+
     private val http = OkHttpClient.Builder()
         .connectTimeout(30, TimeUnit.SECONDS)
         .readTimeout(120, TimeUnit.SECONDS)


=== DIFF: app/src/main/java/com/secrethero/neurocode/data/ChatRepository.kt ===
@@ -4,6 +4,7 @@ import android.content.Context
 import com.secrethero.neurocode.R
 import com.secrethero.neurocode.model.ChatMessage
 import com.secrethero.neurocode.model.ChatSession
+import com.secrethero.neurocode.model.ModelRef
 import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
@@ -67,6 +68,11 @@ class ChatRepository(private val context: Context) {
         }
     }
 
+    /** Переопределение модели диалога; null возвращает диалог к модели проекта. */
+    suspend fun setModel(sessionId: String, model: ModelRef?) {
+        update(sessionId) { it.copy(model = model) }
+    }
+
     suspend fun replaceMessages(sessionId: String, messages: List<ChatMessage>) {
         update(sessionId) {
             it.copy(messages = messages, updatedAt = System.currentTimeMillis())


=== DIFF: app/src/main/java/com/secrethero/neurocode/data/ProjectManifest.kt ===
@@ -0,0 +1,34 @@
+package com.secrethero.neurocode.data
+
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.model.Project
+import kotlinx.serialization.Serializable
+import kotlinx.serialization.json.Json
+
+/**
+ * Паспорт проекта в экспорте: имя и выбранная модель. Без секретов и локальных путей —
+ * у локальной модели остаётся только имя файла GGUF, путь на другом устройстве другой.
+ */
+@Serializable
+data class ProjectManifest(
+    val version: Int = 1,
+    val name: String,
+    val model: ModelRef? = null,
+) {
+    fun encode(): String = json.encodeToString(serializer(), this)
+
+    companion object {
+        const val FILE_NAME = "neurocode-project.json"
+
+        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
+
+        fun forExport(project: Project) = ProjectManifest(
+            name = project.name,
+            model = project.model?.copy(localPath = null),
+        )
+
+        /** Читает паспорт; битый или чужой файл даёт null, а не ошибку импорта. */
+        fun decode(text: String): ProjectManifest? =
+            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
+    }
+}


=== DIFF: app/src/main/java/com/secrethero/neurocode/data/ProjectRepository.kt ===
@@ -7,6 +7,8 @@ import androidx.documentfile.provider.DocumentFile
 import com.secrethero.neurocode.model.AttachmentKind
 import com.secrethero.neurocode.model.ChatAttachment
 import com.secrethero.neurocode.model.FileNode
+import com.secrethero.neurocode.model.ModelKind
+import com.secrethero.neurocode.model.ModelRef
 import com.secrethero.neurocode.model.Project
 import com.secrethero.neurocode.model.SearchHit
 import kotlinx.coroutines.Dispatchers
@@ -58,12 +60,35 @@ class ProjectRepository(private val context: Context) {
         withContext(Dispatchers.IO) {
             val source = DocumentFile.fromTreeUri(context, uri)
                 ?: throw IOException("Не удалось открыть выбранную папку")
-            val project = create(requestedName ?: source.name ?: "Импортированный проект")
+            val manifest = readManifest(source)
+            val project = create(requestedName ?: manifest?.name ?: source.name ?: "Импортированный проект")
             copyDocumentTree(source, File(project.rootPath), 0)
+            manifest?.model?.let { setModel(project.id, relinkLocalModel(it)) }
             touch(project.id)
-            project
+            get(project.id) ?: project
         }
 
+    /** Паспорт проекта из экспорта, если он лежит в корне выбранной папки. */
+    private fun readManifest(root: DocumentFile): ProjectManifest? {
+        val file = root.findFile(ProjectManifest.FILE_NAME) ?: return null
+        val text = runCatching {
+            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().decodeToString() }
+        }.getOrNull()
+        return text?.let(ProjectManifest::decode)
+    }
+
+    /** Локальная модель из паспорта привязывается к одноимённому GGUF на этом устройстве, если он есть. */
+    private fun relinkLocalModel(ref: ModelRef): ModelRef {
+        if (ref.kind != ModelKind.LOCAL) return ref
+        val local = ref.localName?.let { File(File(context.filesDir, "models"), it) }
+        return ref.copy(localPath = local?.takeIf { it.isFile }?.absolutePath)
+    }
+
+    /** Задаёт модель проекта; null возвращает «как в глобальных настройках». */
+    suspend fun setModel(projectId: String, model: ModelRef?) {
+        persist(_projects.value.map { if (it.id == projectId) it.copy(model = model) else it })
+    }
+
     suspend fun exportTree(
         projectId: String,
         target: Uri,
@@ -234,6 +259,11 @@ class ProjectRepository(private val context: Context) {
             ?: throw IOException("Не удалось создать архив в выбранной папке")
         var written = 0L
         ZipOutputStream(BufferedOutputStream(output)).use { zip ->
+            get(projectId)?.let { project ->
+                zip.putNextEntry(ZipEntry(ProjectManifest.FILE_NAME))
+                zip.write(ProjectManifest.forExport(project).encode().toByteArray(Charsets.UTF_8))
+                zip.closeEntry()
+            }
             for (file in entries) {
                 val relative = file.relativeTo(root).invariantSeparatorsPath
                 val entry = ZipEntry(relative).apply {


=== DIFF: app/src/main/java/com/secrethero/neurocode/model/Models.kt ===
@@ -10,8 +10,44 @@ data class Project(
     val rootPath: String,
     val createdAt: Long = System.currentTimeMillis(),
     val updatedAt: Long = System.currentTimeMillis(),
+    /** Модель проекта; null — «как в глобальных настройках». */
+    val model: ModelRef? = null,
 )
 
+@Serializable
+enum class ModelKind {
+    CLOUD,
+    LOCAL,
+}
+
+/**
+ * Выбор модели: облачная (провайдер + идентификатор модели) или локальная GGUF.
+ * Не содержит секретов, поэтому безопасна для хранения в проекте, диалоге и экспорте.
+ */
+@Serializable
+data class ModelRef(
+    val kind: ModelKind,
+    val providerId: String? = null,
+    val model: String? = null,
+    val localPath: String? = null,
+    val localName: String? = null,
+) {
+    /** Ключ для сравнения и списков «недавние / избранные». */
+    val key: String
+        get() = when (kind) {
+            ModelKind.CLOUD -> "cloud:$providerId:$model"
+            ModelKind.LOCAL -> "local:${localName ?: localPath}"
+        }
+
+    companion object {
+        fun cloud(providerId: String, model: String) =
+            ModelRef(ModelKind.CLOUD, providerId = providerId, model = model)
+
+        fun local(path: String, name: String) =
+            ModelRef(ModelKind.LOCAL, localPath = path, localName = name)
+    }
+}
+
 @Serializable
 data class FileNode(
     val name: String,
@@ -100,7 +136,15 @@ data class AppSettings(
 
     /** Material You: в современном дизайне брать палитру из обоев (Android 12+). */
     val dynamicColor: Boolean = true,
-)
+
+    /** Последние выбранные модели (новые первыми, не больше [RECENT_MODELS_LIMIT]). */
+    val recentModels: List<ModelRef> = emptyList(),
+    val favoriteModels: List<ModelRef> = emptyList(),
+) {
+    companion object {
+        const val RECENT_MODELS_LIMIT = 5
+    }
+}
 
 @Serializable
 enum class MessageRole {
@@ -145,6 +189,8 @@ data class ChatMessage(
     val attachments: List<ChatAttachment> = emptyList(),
     /** Размышления модели (reasoning), если провайдер их отдаёт. */
     val reasoning: String? = null,
+    /** Какая модель сгенерировала ответ (для сообщений ассистента). */
+    val generatedBy: ModelRef? = null,
 )
 
 @Serializable
@@ -153,6 +199,8 @@ data class ChatSession(
     val title: String = "Новый диалог",
     val projectId: String? = null,
     val providerId: String? = null,
+    /** Переопределение модели для диалога; null — берётся модель проекта. */
+    val model: ModelRef? = null,
     val createdAt: Long = System.currentTimeMillis(),
     val updatedAt: Long = System.currentTimeMillis(),
     val messages: List<ChatMessage> = emptyList(),


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/ChatViewModel.kt ===
@@ -7,8 +7,19 @@ import androidx.lifecycle.viewModelScope
 import com.secrethero.neurocode.NeuroCodeApplication
 import com.secrethero.neurocode.R
 import com.secrethero.neurocode.ai.AgentEvent
+import com.secrethero.neurocode.ai.HistoryAdapter
+import com.secrethero.neurocode.ai.ImportedModel
+import com.secrethero.neurocode.ai.ModelProblem
+import com.secrethero.neurocode.ai.ModelResolver
+import com.secrethero.neurocode.ai.ModelSource
 import com.secrethero.neurocode.ai.PreparedAttachment
+import com.secrethero.neurocode.ai.ResolvedModel
+import com.secrethero.neurocode.ai.TargetCapabilities
+import com.secrethero.neurocode.device.DeviceProfile
+import com.secrethero.neurocode.device.DeviceSpecs
 import com.secrethero.neurocode.model.AppSettings
+import com.secrethero.neurocode.model.ModelKind
+import com.secrethero.neurocode.model.ModelRef
 import com.secrethero.neurocode.model.AttachmentKind
 import com.secrethero.neurocode.model.ChatAttachment
 import com.secrethero.neurocode.model.ChatMessage
@@ -17,6 +28,7 @@ import com.secrethero.neurocode.model.ChatSession
 import com.secrethero.neurocode.model.ModelLimits
 import com.secrethero.neurocode.model.MessageRole
 import com.secrethero.neurocode.model.ProviderConfig
+import java.io.File
 import kotlinx.coroutines.CancellationException
 import kotlinx.coroutines.Job
 import kotlinx.coroutines.flow.MutableSharedFlow
@@ -67,6 +79,30 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
     /** Лимиты и расход по последнему ответу провайдера. */
     val modelLimits: StateFlow<ModelLimits?> = container.apiClient.limits
 
+    /** Действующая модель активного диалога: диалог → проект → глобальная по умолчанию. */
+    val activeModel: StateFlow<ResolvedModel> = combine(
+        container.settings.settings,
+        container.projects.projects,
+        container.chats.sessions,
+        _activeSessionId,
+    ) { current, projects, all, activeId ->
+        ModelResolver.resolve(
+            session = all.firstOrNull { it.id == activeId },
+            project = projects.firstOrNull { it.id == current.selectedProjectId },
+            settings = current,
+            hasKey = { container.settings.apiKey(it) != null },
+        )
+    }.stateIn(
+        viewModelScope,
+        SharingStarted.Eagerly,
+        ResolvedModel(null, ModelSource.GLOBAL, problem = ModelProblem.NOTHING_SELECTED),
+    )
+
+    private val _localModels = MutableStateFlow<List<ImportedModel>>(emptyList())
+
+    /** Импортированные GGUF для окна выбора модели. */
+    val localModels: StateFlow<List<ImportedModel>> = _localModels.asStateFlow()
+
     private var chatJob: Job? = null
 
     init {
@@ -149,53 +185,72 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
 
     fun sendMessage(text: String, editorContext: EditorContext? = null) {
         val attached = _pendingAttachments.value
-        if ((text.isBlank() && attached.isEmpty()) || chatJob?.isActive == true) return
+        if (text.isBlank() && attached.isEmpty()) return
+        launchTurn {
+            val sessionId = ensureSession()
+            container.chats.append(
+                sessionId,
+                ChatMessage(
+                    role = MessageRole.USER,
+                    content = text.trim(),
+                    attachments = attached,
+                ),
+            )
+            _pendingAttachments.value = emptyList()
+            val session = container.chats.get(sessionId)
+            answerTurn(
+                Turn(
+                    sessionId = sessionId,
+                    // Проект берём из самого диалога, а не из текущего выбора: иначе история
+                    // старого проекта применялась бы к файлам нового.
+                    projectId = session?.projectId ?: settings.value.selectedProjectId,
+                    history = session?.messages.orEmpty(),
+                    request = UserRequest(text.trim(), attached, editorContext),
+                    model = resolveModel(session),
+                ),
+            )
+        }
+    }
+
+    /**
+     * Повторяет ответ [assistantMessageId] другой моделью: запрос того же сообщения пользователя,
+     * прежний ответ остаётся в истории, новый подписывается своей моделью.
+     */
+    fun retryWithModel(assistantMessageId: String, ref: ModelRef) {
+        launchTurn {
+            val session = container.chats.get(_activeSessionId.value) ?: return@launchTurn
+            val answerIndex = session.messages.indexOfFirst { it.id == assistantMessageId }
+            val userIndex = session.messages.take(answerIndex.coerceAtLeast(0))
+                .indexOfLast { it.role == MessageRole.USER }
+            if (answerIndex < 0 || userIndex < 0) return@launchTurn
+            val request = session.messages[userIndex]
+            answerTurn(
+                Turn(
+                    sessionId = session.id,
+                    projectId = session.projectId ?: settings.value.selectedProjectId,
+                    history = session.messages.take(userIndex + 1),
+                    request = UserRequest(request.content, request.attachments, null),
+                    model = resolveModel(ChatSession(model = ref), session.projectId),
+                ),
+            )
+        }
+    }
+
+    private suspend fun ensureSession(): String {
+        _activeSessionId.value?.let { return it }
+        val created = container.chats.create(
+            settings.value.selectedProjectId,
+            settings.value.selectedProviderId,
+        )
+        _activeSessionId.value = created.id
+        return created.id
+    }
+
+    private fun launchTurn(block: suspend () -> Unit) {
+        if (chatJob?.isActive == true) return
         chatJob = viewModelScope.launch {
             try {
-                var sessionId = _activeSessionId.value
-                if (sessionId == null) {
-                    sessionId = container.chats.create(
-                        settings.value.selectedProjectId,
-                        settings.value.selectedProviderId,
-                    ).id
-                    _activeSessionId.value = sessionId
-                }
-                container.chats.append(
-                    sessionId,
-                    ChatMessage(
-                        role = MessageRole.USER,
-                        content = text.trim(),
-                        attachments = attached,
-                    ),
-                )
-                _pendingAttachments.value = emptyList()
-                val session = container.chats.get(sessionId)
-                val history = session?.messages.orEmpty()
-                // Проект берём из самого диалога, а не из текущего выбора: иначе история
-                // старого проекта применялась бы к файлам нового.
-                val projectId = session?.projectId ?: settings.value.selectedProjectId
-                _streamingResponse.value = ""
-                _streamingReasoning.value = ""
-                _agentLog.value = emptyList()
-                _chatRunState.value = ChatRunState.Working(str(R.string.status_thinking))
-
-                val answer = if (settings.value.useLocalModel) {
-                    runLocal(sessionId, text.trim(), editorContext, attached)
-                } else {
-                    runCloud(sessionId, projectId, history)
-                }
-                container.chats.append(
-                    sessionId,
-                    ChatMessage(
-                        role = MessageRole.ASSISTANT,
-                        content = answer,
-                        reasoning = _streamingReasoning.value.takeIf { it.isNotBlank() },
-                    ),
-                )
-                _streamingResponse.value = ""
-                _streamingReasoning.value = ""
-                _chatRunState.value = ChatRunState.Idle
-                projectsMutated.tryEmit(Unit)
+                block()
             } catch (cancelled: CancellationException) {
                 clearStreaming()
                 _chatRunState.value = ChatRunState.Idle
@@ -209,6 +264,143 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         }
     }
 
+    /** Сообщение пользователя с вложениями и контекстом редактора. */
+    private class UserRequest(
+        val text: String,
+        val attachments: List<ChatAttachment>,
+        val editorContext: EditorContext?,
+    )
+
+    /** Один запрос к модели: всё, что нужно, чтобы выполнить его независимо от настроек на момент ответа. */
+    private class Turn(
+        val sessionId: String,
+        val projectId: String?,
+        val history: List<ChatMessage>,
+        val request: UserRequest,
+        val model: ResolvedModel,
+    )
+
+    private val _retryRequest = MutableStateFlow<String?>(null)
+
+    /** Сообщение ассистента, для которого пользователь хочет повторить ответ другой моделью. */
+    val retryRequest: StateFlow<String?> = _retryRequest.asStateFlow()
+
+    fun requestRetry(assistantMessageId: String) {
+        _retryRequest.value = assistantMessageId
+    }
+
+    fun clearRetryRequest() {
+        _retryRequest.value = null
+    }
+
+    private suspend fun answerTurn(turn: Turn) {
+        check(turn.model.ready) { unavailableMessage(turn.model) }
+        clearStreaming()
+        _agentLog.value = emptyList()
+        _chatRunState.value = ChatRunState.Working(str(R.string.status_thinking))
+        val answer = if (turn.model.isLocal) runLocal(turn) else runCloud(turn)
+        container.chats.append(
+            turn.sessionId,
+            ChatMessage(
+                role = MessageRole.ASSISTANT,
+                content = answer,
+                reasoning = _streamingReasoning.value.takeIf { it.isNotBlank() },
+                generatedBy = turn.model.ref,
+            ),
+        )
+        clearStreaming()
+        _chatRunState.value = ChatRunState.Idle
+        projectsMutated.tryEmit(Unit)
+    }
+
+    private fun resolveModel(session: ChatSession?, projectId: String? = session?.projectId): ResolvedModel {
+        val current = settings.value
+        return ModelResolver.resolve(
+            session = session,
+            project = container.projects.get(projectId ?: current.selectedProjectId),
+            settings = current,
+            hasKey = { container.settings.apiKey(it) != null },
+        )
+    }
+
+    private fun unavailableMessage(model: ResolvedModel): String {
+        val source = str(
+            when (model.source) {
+                ModelSource.DIALOG -> R.string.model_source_dialog_genitive
+                ModelSource.PROJECT -> R.string.model_source_project_genitive
+                ModelSource.GLOBAL -> R.string.model_source_global_genitive
+            },
+        )
+        val reason = str(
+            when (model.problem) {
+                ModelProblem.NO_PROVIDER -> R.string.model_problem_no_provider
+                ModelProblem.PROVIDER_DISABLED -> R.string.model_problem_provider_disabled
+                ModelProblem.NO_KEY -> R.string.model_problem_no_key
+                ModelProblem.NO_MODEL_ID -> R.string.model_problem_no_model_id
+                ModelProblem.LOCAL_NOT_IMPORTED -> R.string.model_problem_local_not_imported
+                ModelProblem.LOCAL_FILE_MISSING -> R.string.model_problem_local_file_missing
+                ModelProblem.NOTHING_SELECTED, null -> R.string.model_problem_nothing_selected
+            },
+        )
+        val name = ModelResolver.shortLabel(model.ref) ?: "—"
+        return str(R.string.error_model_unavailable_format, source, name, reason)
+    }
+
+    fun refreshLocalModels() = viewModelScope.launch {
+        _localModels.value = runCatching { container.localLlama.listModels() }.getOrDefault(emptyList())
+    }
+
+    /** Куда применить выбор в окне выбора модели. */
+    enum class ModelScope { DIALOG, PROJECT }
+
+    fun selectModel(ref: ModelRef, scope: ModelScope) = viewModelScope.launch {
+        runCatching {
+            when (scope) {
+                ModelScope.DIALOG -> container.chats.setModel(ensureSession(), ref)
+                ModelScope.PROJECT -> {
+                    val projectId = settings.value.selectedProjectId
+                    if (projectId == null) {
+                        container.bus.showNotice(str(R.string.notice_select_project_first))
+                        return@launch
+                    }
+                    container.projects.setModel(projectId, ref)
+                    // Модель проекта действует сразу: прежнее переопределение диалога снимается.
+                    _activeSessionId.value?.let { container.chats.setModel(it, null) }
+                }
+            }
+            container.settings.update { it.copy(recentModels = ModelResolver.pushRecent(it.recentModels, ref)) }
+            warnIfHeavy(ref)
+        }.onFailure(container.bus::showError)
+    }
+
+    /** Снимает выбор: диалог возвращается к модели проекта, проект — к глобальной модели. */
+    fun clearModel(scope: ModelScope) = viewModelScope.launch {
+        runCatching {
+            when (scope) {
+                ModelScope.DIALOG -> _activeSessionId.value?.let { container.chats.setModel(it, null) }
+                ModelScope.PROJECT -> settings.value.selectedProjectId?.let { container.projects.setModel(it, null) }
+            }
+        }.onFailure(container.bus::showError)
+    }
+
+    fun toggleFavorite(ref: ModelRef) = updateSettings {
+        it.copy(favoriteModels = ModelResolver.toggleFavorite(it.favoriteModels, ref))
+    }
+
+    /** Предупреждает, если выбранная GGUF тяжелее рекомендованного для этого телефона размера. */
+    private fun warnIfHeavy(ref: ModelRef) {
+        val file = ref.localPath?.takeIf { ref.kind == ModelKind.LOCAL }?.let(::File)?.takeIf { it.isFile }
+        val recommendation = file?.let {
+            runCatching { DeviceSpecs(getApplication()).snapshot().recommendation() }.getOrNull()
+        }
+        val sizeMb = (file?.length() ?: 0L) / DeviceProfile.MB
+        if (recommendation != null && sizeMb > recommendation.maxModelSizeMb) {
+            container.bus.showNotice(
+                str(R.string.notice_model_oversize_format, sizeMb, recommendation.maxModelSizeMb),
+            )
+        }
+    }
+
     /** Весь активный диалог одним текстом — для копирования из бокового меню. */
     fun conversationText(): String =
         sessions.value.firstOrNull { it.id == _activeSessionId.value }?.messages.orEmpty()
@@ -238,22 +430,27 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         container.approvals.resolve(approved)
     }
 
-    private suspend fun runCloud(
-        sessionId: String,
-        projectId: String?,
-        history: List<ChatMessage>,
-    ): String {
+    @Suppress("ThrowsCount")
+    private suspend fun runCloud(turn: Turn): String {
         val current = settings.value
-        val provider = current.providers.firstOrNull { it.id == current.selectedProviderId }
-            ?: error(str(R.string.error_select_provider))
+        val provider = checkNotNull(turn.model.provider)
         val key = container.settings.apiKey(provider)
             ?: error(str(R.string.error_add_key_format, provider.name))
-        val project = container.projects.get(projectId)
+        val project = container.projects.get(turn.projectId)
+        val history = adaptHistory(
+            turn.history,
+            TargetCapabilities(
+                supportsTools = true,
+                supportsImages = true,
+                contextTokens = container.apiClient.contextWindow(provider.model),
+            ),
+        )
+        val sessionId = turn.sessionId
         val request = CloudRequest(
             current = current,
             project = project,
             history = history,
-            attachments = prepareAttachments(projectId, history),
+            attachments = prepareAttachments(turn.projectId, history),
             sessionId = sessionId,
         )
 
@@ -281,8 +478,17 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         return answer
     }
 
+    /** Приводит историю к возможностям новой модели и сообщает пользователю, если что-то пришлось сократить. */
+    private fun adaptHistory(history: List<ChatMessage>, target: TargetCapabilities): List<ChatMessage> {
+        val adapted = HistoryAdapter.adapt(history, target)
+        if (adapted.droppedMessages > 0) {
+            container.bus.showNotice(str(R.string.notice_history_trimmed_format, adapted.droppedMessages))
+        }
+        if (adapted.imagesRemoved) container.bus.showNotice(str(R.string.notice_images_removed))
+        return adapted.messages
+    }
+
     private fun fallbackProvider(current: AppSettings, primaryId: String?): ProviderConfig? {
-        if (current.useLocalModel) return null
         val fallbackId = current.fallbackProviderId ?: return null
         if (fallbackId == primaryId) return null
         val fallback = current.providers.firstOrNull { it.id == fallbackId } ?: return null
@@ -420,16 +626,6 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         }
     }
 
-    fun switchProvider(providerId: String) = updateSettings {
-        it.copy(selectedProviderId = providerId, useLocalModel = false)
-    }
-
-    fun setProviderModel(provider: ProviderConfig, model: String) = viewModelScope.launch {
-        runCatching {
-            container.settings.upsertProvider(provider.copy(model = model), null)
-        }.onFailure(container.bus::showError)
-    }
-
     private val _switcherModels =
         MutableStateFlow<ProviderModelsState?>(null)
     val switcherModels: StateFlow<ProviderModelsState?> = _switcherModels.asStateFlow()
@@ -450,15 +646,18 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         _switcherModels.value = null
     }
 
-    private suspend fun runLocal(
-        sessionId: String,
-        userMessage: String,
-        editorContext: EditorContext?,
-        attachments: List<ChatAttachment> = emptyList(),
-    ): String {
-        val current = settings.value
-        val path = current.localModelPath ?: error(str(R.string.error_import_model_first))
+    private suspend fun runLocal(turn: Turn): String {
+        val ref = checkNotNull(turn.model.ref)
+        val path = checkNotNull(ref.localPath)
+        val userMessage = turn.request.text
+        val editorContext = turn.request.editorContext
+        val attachments = turn.request.attachments
         _chatRunState.value = ChatRunState.Working(str(R.string.status_loading_model))
+        // Контекст в памяти движка актуален, только если предыдущий ответ дала эта же модель в этом диалоге.
+        val lastAnswer = turn.history.lastOrNull { it.role == MessageRole.ASSISTANT }
+        val warm = lastAnswer?.generatedBy?.key == ref.key &&
+            container.localLlama.isLoadedFor(path, turn.sessionId)
+        if (!warm) container.localLlama.unload()
         container.localLlama.load(
             path = path,
             systemPrompt = """
@@ -466,10 +665,15 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
                 Отвечай по-русски. Пиши точный код и явно отмечай ограничения.
                 У тебя нет прямого доступа к инструментам и файлам, кроме контекста в сообщении.
             """.trimIndent(),
-            conversationKey = sessionId,
+            conversationKey = turn.sessionId,
         )
-        val projectId = settings.value.selectedProjectId
+        // После перехода с другой модели (например, облачной) локальная видит прежний диалог текстом.
+        val earlier = if (warm) "" else earlierDialog(turn.history.dropLast(1))
+        val projectId = turn.projectId
         val context = buildString {
+            if (earlier.isNotBlank()) {
+                append(str(R.string.local_earlier_dialog_header)).append('\n').append(earlier).append("\n\n")
+            }
             append(userMessage)
             if (editorContext != null && editorContext.text.length <= 20_000) {
                 append("\n\nТекущий файл: ").append(editorContext.path)
@@ -497,6 +701,15 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
         return answer.toString()
     }
 
+    private fun earlierDialog(history: List<ChatMessage>): String {
+        val target = TargetCapabilities(
+            supportsTools = false,
+            supportsImages = false,
+            contextTokens = LOCAL_CONTEXT_TOKENS,
+        )
+        return HistoryAdapter.transcript(adaptHistory(history, target), LOCAL_HISTORY_CHARS)
+    }
+
     private fun onAgentEvent(sessionId: String, event: AgentEvent) {
         when (event) {
             is AgentEvent.Status -> _chatRunState.value = ChatRunState.Working(event.text)
@@ -537,6 +750,10 @@ class ChatViewModel(application: Application) : AndroidViewModel(application) {
     private companion object {
         /** Картинка крупнее уходит не data-URL, а путём в проекте: base64 раздувает запрос. */
         const val MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024
+
+        /** Окно контекста локальной модели по умолчанию: движок его не сообщает, берём осторожное значение. */
+        const val LOCAL_CONTEXT_TOKENS = 4_096
+        const val LOCAL_HISTORY_CHARS = 6_000
     }
 }
 


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/NeuroCodeApp.kt ===
@@ -99,7 +99,10 @@ import com.secrethero.neurocode.model.Project
 import com.secrethero.neurocode.ui.screens.ChatScreen
 import com.secrethero.neurocode.ui.screens.EditorScreen
 import com.secrethero.neurocode.ui.screens.GitScreen
+import com.secrethero.neurocode.ui.screens.ModelPickerActions
+import com.secrethero.neurocode.ui.screens.ModelPickerModel
 import com.secrethero.neurocode.ui.screens.ModelSwitcherDialog
+import com.secrethero.neurocode.ai.ModelResolver
 import com.secrethero.neurocode.ui.screens.SettingsScreen
 import com.secrethero.neurocode.ui.screens.TerminalScreen
 
@@ -175,6 +178,9 @@ fun NeuroCodeApp(
     var createProjectDialog by remember { mutableStateOf(false) }
     var deleteProjectDialog by remember { mutableStateOf(false) }
     var showSwitcher by remember { mutableStateOf(false) }
+    val retryFor by viewModel.chat.retryRequest.collectAsStateWithLifecycle()
+    val activeModel by viewModel.chat.activeModel.collectAsStateWithLifecycle()
+    val localModels by viewModel.chat.localModels.collectAsStateWithLifecycle()
     var settingsDrawer by rememberSaveable { mutableStateOf(false) }
     val snackbar = remember { SnackbarHostState() }
     val drawerState = rememberDrawerState(DrawerValue.Closed)
@@ -220,9 +226,10 @@ fun NeuroCodeApp(
         }
     }
 
-    LaunchedEffect(showSwitcher, currentProvider?.id) {
-        if (showSwitcher && !settings.useLocalModel && currentProvider != null) {
-            viewModel.chat.loadSwitcherModels(currentProvider)
+    LaunchedEffect(showSwitcher || retryFor != null, activeModel.provider?.id) {
+        if (showSwitcher || retryFor != null) {
+            viewModel.chat.refreshLocalModels()
+            (activeModel.provider ?: currentProvider)?.let { viewModel.chat.loadSwitcherModels(it) }
         }
     }
 
@@ -252,11 +259,7 @@ fun NeuroCodeApp(
         projects = projects,
         hasProject = currentProject != null,
         syncEnabled = syncEnabled,
-        modelLabel = when {
-            settings.useLocalModel -> settings.localModelName ?: "GGUF"
-            currentProvider != null -> currentProvider.model
-            else -> stringResource(R.string.select_model)
-        },
+        modelLabel = ModelResolver.shortLabel(activeModel.ref) ?: stringResource(R.string.select_model),
     )
 
     val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }
@@ -513,20 +516,35 @@ fun NeuroCodeApp(
         )
     }
 
-    if (showSwitcher) {
+    if (showSwitcher || retryFor != null) {
+        val retryId = retryFor
         ModelSwitcherDialog(
-            settings = settings,
-            state = viewModel.chat.switcherModels.collectAsStateWithLifecycle().value,
-            onProvider = {
-                viewModel.chat.switchProvider(it.id)
-                viewModel.chat.loadSwitcherModels(it)
-            },
-            onRefresh = viewModel.chat::loadSwitcherModels,
-            onModel = { provider, model -> viewModel.chat.setProviderModel(provider, model) },
-            onDismiss = {
-                showSwitcher = false
-                viewModel.chat.clearSwitcherModels()
-            },
+            picker = ModelPickerModel(
+                settings = settings,
+                active = activeModel,
+                project = currentProject,
+                cloudModels = viewModel.chat.switcherModels.collectAsStateWithLifecycle().value,
+                localModels = localModels,
+                retry = retryId != null,
+            ),
+            actions = ModelPickerActions(
+                onBrowseProvider = viewModel.chat::loadSwitcherModels,
+                onRefresh = viewModel.chat::loadSwitcherModels,
+                onSelect = { ref, scope ->
+                    if (retryId != null) {
+                        viewModel.chat.retryWithModel(retryId, ref)
+                    } else {
+                        viewModel.chat.selectModel(ref, scope)
+                    }
+                },
+                onClear = { viewModel.chat.clearModel(it) },
+                onToggleFavorite = viewModel.chat::toggleFavorite,
+                onDismiss = {
+                    showSwitcher = false
+                    viewModel.chat.clearRetryRequest()
+                    viewModel.chat.clearSwitcherModels()
+                },
+            ),
         )
     }
 }


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/screens/ChatModernUi.kt ===
@@ -48,6 +48,7 @@ import androidx.compose.material3.IconButton
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.Surface
 import androidx.compose.material3.Text
+import androidx.compose.material.icons.filled.Refresh
 import androidx.compose.material3.TextField
 import androidx.compose.material3.TextFieldDefaults
 import androidx.compose.runtime.Composable
@@ -201,6 +202,7 @@ internal fun ModernMessage(
     user: Boolean,
     attachmentFile: (ChatAttachment) -> File?,
     onCopy: () -> Unit,
+    signature: MessageSignature? = null,
 ) {
     if (user) {
         Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
@@ -247,7 +249,11 @@ internal fun ModernMessage(
                     .putExtra(Intent.EXTRA_TEXT, message.content)
                 context.startActivity(Intent.createChooser(send, null))
             }
+            signature?.onRetry?.let {
+                MessageAction(Icons.Default.Refresh, stringResource(R.string.retry_other_model), it)
+            }
         }
+        ModelSignature(signature?.modelLabel, onRetry = null)
     }
 }
 


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/screens/ChatScreen.kt ===
@@ -92,6 +92,7 @@ import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.ui.unit.dp
 import androidx.lifecycle.compose.collectAsStateWithLifecycle
 import com.secrethero.neurocode.R
+import com.secrethero.neurocode.ai.ModelResolver
 import com.secrethero.neurocode.model.AppDesign
 import com.secrethero.neurocode.model.ChatAttachment
 import com.secrethero.neurocode.model.ChatMessage
@@ -267,6 +268,15 @@ fun ChatScreen(chat: ChatViewModel, editor: EditorViewModel) {
                         onCopy = {
                             clipboard.setText(AnnotatedString(message.content))
                         },
+                        signature = if (message.role == MessageRole.ASSISTANT) {
+                            MessageSignature(
+                                modelLabel = message.generatedBy
+                                    ?.let { ModelResolver.fullLabel(it, settings.providers) },
+                                onRetry = if (busy) null else ({ chat.requestRetry(message.id) }),
+                            )
+                        } else {
+                            null
+                        },
                     )
                 }
                 if (modern && showDisclaimer) {
@@ -456,11 +466,12 @@ private fun MessageBubble(
     modern: Boolean,
     attachmentFile: (ChatAttachment) -> File?,
     onCopy: () -> Unit,
+    signature: MessageSignature? = null,
 ) {
     val user = message.role == MessageRole.USER
     val tool = message.role == MessageRole.TOOL
     if (modern && !tool) {
-        ModernMessage(message, user, attachmentFile, onCopy)
+        ModernMessage(message, user, attachmentFile, onCopy, signature)
         return
     }
     Column(
@@ -538,6 +549,7 @@ private fun MessageBubble(
                 }
             }
         }
+        ModelSignature(signature?.modelLabel, signature?.onRetry)
     }
 }
 


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/screens/MessageSignature.kt ===
@@ -0,0 +1,43 @@
+package com.secrethero.neurocode.ui.screens
+
+import androidx.compose.foundation.layout.Arrangement
+import androidx.compose.foundation.layout.Row
+import androidx.compose.material3.MaterialTheme
+import androidx.compose.material3.Text
+import androidx.compose.material3.TextButton
+import androidx.compose.runtime.Composable
+import androidx.compose.ui.Alignment
+import androidx.compose.ui.Modifier
+import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.text.style.TextOverflow
+import androidx.compose.ui.unit.dp
+import com.secrethero.neurocode.R
+
+/** Что показать под ответом ассистента: какая модель его сгенерировала и можно ли повторить другой. */
+class MessageSignature(
+    val modelLabel: String?,
+    val onRetry: (() -> Unit)?,
+)
+
+/** Небольшая подпись под ответом; кнопка повтора в классическом дизайне стоит рядом с подписью. */
+@Composable
+internal fun ModelSignature(label: String?, onRetry: (() -> Unit)?) {
+    if (label == null && onRetry == null) return
+    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
+        label?.let {
+            Text(
+                it,
+                style = MaterialTheme.typography.labelSmall,
+                color = MaterialTheme.colorScheme.onSurfaceVariant,
+                maxLines = 1,
+                overflow = TextOverflow.Ellipsis,
+                modifier = Modifier.weight(1f, fill = false),
+            )
+        }
+        onRetry?.let {
+            TextButton(onClick = it) {
+                Text(stringResource(R.string.retry_other_model), style = MaterialTheme.typography.labelSmall)
+            }
+        }
+    }
+}


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/screens/ModelPickerParts.kt ===
@@ -0,0 +1,221 @@
+package com.secrethero.neurocode.ui.screens
+
+import androidx.compose.foundation.BorderStroke
+import androidx.compose.foundation.clickable
+import androidx.compose.foundation.layout.Arrangement
+import androidx.compose.foundation.layout.Column
+import androidx.compose.foundation.layout.ExperimentalLayoutApi
+import androidx.compose.foundation.layout.FlowRow
+import androidx.compose.foundation.layout.Row
+import androidx.compose.foundation.layout.fillMaxWidth
+import androidx.compose.foundation.layout.padding
+import androidx.compose.foundation.layout.size
+import androidx.compose.foundation.shape.RoundedCornerShape
+import androidx.compose.material.icons.Icons
+import androidx.compose.material.icons.filled.Check
+import androidx.compose.material.icons.filled.Star
+import androidx.compose.material.icons.filled.StarBorder
+import androidx.compose.material3.Checkbox
+import androidx.compose.material3.FilterChip
+import androidx.compose.material3.Icon
+import androidx.compose.material3.IconButton
+import androidx.compose.material3.LinearProgressIndicator
+import androidx.compose.material3.MaterialTheme
+import androidx.compose.material3.Surface
+import androidx.compose.material3.Text
+import androidx.compose.material3.TextButton
+import androidx.compose.runtime.Composable
+import androidx.compose.ui.Alignment
+import androidx.compose.ui.Modifier
+import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.text.font.FontFamily
+import androidx.compose.ui.text.font.FontWeight
+import androidx.compose.ui.unit.dp
+import com.secrethero.neurocode.R
+import com.secrethero.neurocode.ai.ModelProblem
+import com.secrethero.neurocode.ai.ModelResolver
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.ui.ProviderModelsState
+
+@Composable
+internal fun SwitcherHeader(retry: Boolean, onDismiss: () -> Unit) {
+    Row(
+        Modifier
+            .fillMaxWidth()
+            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
+        verticalAlignment = Alignment.CenterVertically,
+    ) {
+        Column(Modifier.weight(1f)) {
+            Text(
+                stringResource(if (retry) R.string.retry_model_title else R.string.model_picker_title),
+                style = MaterialTheme.typography.titleMedium,
+                fontWeight = FontWeight.SemiBold,
+            )
+            Text(
+                stringResource(R.string.model_picker_subtitle),
+                style = MaterialTheme.typography.bodySmall,
+                color = MaterialTheme.colorScheme.onSurfaceVariant,
+            )
+        }
+        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
+    }
+}
+
+
+internal fun problemText(problem: ModelProblem): Int = when (problem) {
+    ModelProblem.NOTHING_SELECTED -> R.string.model_problem_nothing_selected
+    ModelProblem.NO_PROVIDER -> R.string.model_problem_no_provider
+    ModelProblem.PROVIDER_DISABLED -> R.string.model_problem_provider_disabled
+    ModelProblem.NO_KEY -> R.string.model_problem_no_key
+    ModelProblem.NO_MODEL_ID -> R.string.model_problem_no_model_id
+    ModelProblem.LOCAL_NOT_IMPORTED -> R.string.model_problem_local_not_imported
+    ModelProblem.LOCAL_FILE_MISSING -> R.string.model_problem_local_file_missing
+}
+
+
+/** Избранные и последние пять моделей — переключение одним касанием. */
+@OptIn(ExperimentalLayoutApi::class)
+@Composable
+internal fun QuickChoices(picker: ModelPickerModel, onChoose: (ModelRef) -> Unit) {
+    val favorites = picker.settings.favoriteModels
+    val recent = picker.settings.recentModels.filterNot { item -> favorites.any { it.key == item.key } }
+    val items = favorites + recent
+    if (items.isEmpty()) return
+    Text(
+        stringResource(R.string.model_recent),
+        style = MaterialTheme.typography.labelMedium,
+        color = MaterialTheme.colorScheme.onSurfaceVariant,
+        modifier = Modifier.padding(horizontal = 16.dp),
+    )
+    FlowRow(
+        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
+        horizontalArrangement = Arrangement.spacedBy(6.dp),
+    ) {
+        items.forEach { ref ->
+            val favorite = favorites.any { it.key == ref.key }
+            FilterChip(
+                selected = picker.active.ref?.key == ref.key,
+                onClick = { onChoose(ref) },
+                label = { Text(ModelResolver.shortLabel(ref).orEmpty(), maxLines = 1) },
+                leadingIcon = if (favorite) {
+                    { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) }
+                } else {
+                    null
+                },
+            )
+        }
+    }
+}
+
+
+@Composable
+internal fun ScopeRow(projectName: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
+    Row(
+        Modifier
+            .fillMaxWidth()
+            .clickable { onChecked(!checked) }
+            .padding(horizontal = 12.dp),
+        verticalAlignment = Alignment.CenterVertically,
+    ) {
+        Checkbox(checked = checked, onCheckedChange = onChecked)
+        Column {
+            Text(stringResource(R.string.model_apply_project), style = MaterialTheme.typography.bodyMedium)
+            Text(
+                projectName,
+                style = MaterialTheme.typography.bodySmall,
+                color = MaterialTheme.colorScheme.onSurfaceVariant,
+            )
+        }
+    }
+}
+
+
+@Suppress("LongParameterList")
+@Composable
+internal fun ModelRow(
+    title: String,
+    selected: Boolean,
+    free: Boolean,
+    favorite: Boolean,
+    modern: Boolean,
+    onClick: () -> Unit,
+    onFavorite: (() -> Unit)?,
+    subtitle: String? = null,
+) {
+    Surface(
+        modifier = Modifier
+            .fillMaxWidth()
+            .clickable(onClick = onClick),
+        color = when {
+            selected -> MaterialTheme.colorScheme.primaryContainer
+            modern -> MaterialTheme.colorScheme.surfaceContainer
+            else -> MaterialTheme.colorScheme.surface
+        },
+        border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
+        shape = RoundedCornerShape(if (modern) 16.dp else 6.dp),
+    ) {
+        Row(
+            Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
+            verticalAlignment = Alignment.CenterVertically,
+            horizontalArrangement = Arrangement.spacedBy(8.dp),
+        ) {
+            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
+                Text(title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
+                subtitle?.let {
+                    Text(
+                        it,
+                        style = MaterialTheme.typography.labelSmall,
+                        color = MaterialTheme.colorScheme.onSurfaceVariant,
+                    )
+                }
+            }
+            if (free) {
+                Text(
+                    stringResource(R.string.model_free_badge),
+                    style = MaterialTheme.typography.labelSmall,
+                    color = MaterialTheme.colorScheme.tertiary,
+                )
+            }
+            if (selected) {
+                Icon(
+                    Icons.Default.Check,
+                    contentDescription = stringResource(R.string.model_selected_cd),
+                    modifier = Modifier.size(18.dp),
+                )
+            }
+            onFavorite?.let {
+                IconButton(onClick = it) {
+                    Icon(
+                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
+                        contentDescription = stringResource(R.string.model_favorite_cd),
+                        modifier = Modifier.size(20.dp),
+                    )
+                }
+            }
+        }
+    }
+}
+
+/** Ошибка загрузки списка и пустые состояния облачного списка моделей. */
+@Composable
+internal fun CloudListStatus(state: ProviderModelsState?, loading: Boolean, visibleCount: Int) {
+    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
+    state?.error?.let {
+        Text(
+            it,
+            color = MaterialTheme.colorScheme.error,
+            style = MaterialTheme.typography.bodySmall,
+            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
+        )
+    }
+    if (!loading && state?.error == null && visibleCount == 0) {
+        val noModels = state?.models.orEmpty().isEmpty()
+        val emptyText = if (noModels) R.string.model_list_empty else R.string.model_search_empty
+        Text(
+            stringResource(emptyText),
+            style = MaterialTheme.typography.bodyMedium,
+            color = MaterialTheme.colorScheme.onSurfaceVariant,
+            modifier = Modifier.padding(16.dp),
+        )
+    }
+}


=== DIFF: app/src/main/java/com/secrethero/neurocode/ui/screens/ModelSwitcherDialog.kt ===
@@ -19,6 +19,9 @@ import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.filled.Check
 import androidx.compose.material.icons.filled.Clear
+import androidx.compose.material.icons.filled.Star
+import androidx.compose.material.icons.filled.StarBorder
+import androidx.compose.material3.Checkbox
 import androidx.compose.material3.FilterChip
 import androidx.compose.material3.Icon
 import androidx.compose.material3.IconButton
@@ -35,16 +38,24 @@ import androidx.compose.runtime.remember
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
-import androidx.compose.ui.graphics.Shape
 import androidx.compose.ui.res.stringResource
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.window.Dialog
 import com.secrethero.neurocode.R
+import com.secrethero.neurocode.ai.ImportedModel
+import com.secrethero.neurocode.ai.ModelProblem
+import com.secrethero.neurocode.ai.ModelResolver
+import com.secrethero.neurocode.ai.ModelSource
+import com.secrethero.neurocode.ai.ResolvedModel
 import com.secrethero.neurocode.model.AppDesign
 import com.secrethero.neurocode.model.AppSettings
+import com.secrethero.neurocode.model.ModelKind
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.model.Project
 import com.secrethero.neurocode.model.ProviderConfig
+import com.secrethero.neurocode.ui.ChatViewModel.ModelScope
 import com.secrethero.neurocode.ui.ProviderModelsState
 
 private const val ZERO_WIDTH_SPACE_CODE = 0x200B
@@ -52,7 +63,8 @@ private val ZERO_WIDTH_SPACE = ZERO_WIDTH_SPACE_CODE.toChar().toString()
 private const val FREE_SUFFIX = ":free"
 private const val CLASSIC_RADIUS_DP = 10
 private const val MODERN_RADIUS_DP = 28
-private const val DIALOG_HEIGHT_FRACTION = 0.88f
+private const val DIALOG_HEIGHT_FRACTION = 0.9f
+private const val BYTES_IN_MB = 1024L * 1024
 
 /** Добавляет точки переноса после `/` и `:`, чтобы длинный `org/model:tag` не ломался посреди слова. */
 internal fun breakableModelId(id: String): String =
@@ -60,33 +72,58 @@ internal fun breakableModelId(id: String): String =
 
 internal fun isFreeModel(id: String): Boolean = id.endsWith(FREE_SUFFIX, ignoreCase = true)
 
+/** Что показывает окно выбора модели. [retry] — выбор модели для повтора ответа, без области «диалог / проект». */
+class ModelPickerModel(
+    val settings: AppSettings,
+    val active: ResolvedModel,
+    val project: Project?,
+    val cloudModels: ProviderModelsState?,
+    val localModels: List<ImportedModel>,
+    val retry: Boolean = false,
+)
+
+class ModelPickerActions(
+    val onBrowseProvider: (ProviderConfig) -> Unit,
+    val onRefresh: (ProviderConfig) -> Unit,
+    val onSelect: (ModelRef, ModelScope) -> Unit,
+    val onClear: (ModelScope) -> Unit,
+    val onToggleFavorite: (ModelRef) -> Unit,
+    val onDismiss: () -> Unit,
+)
+
 /**
- * Окно выбора модели. Цвета и скругления берутся из активной темы: в современном дизайне — контейнерные
- * цвета Material 3 без рамок, в классическом — панели с рамкой (GitHub-dark).
+ * Окно выбора модели: облачные провайдеры и локальные GGUF в одном месте, область применения
+ * (этот диалог или весь проект), недавние и избранные. Цвета и скругления берутся из активной темы.
  */
 @OptIn(ExperimentalLayoutApi::class)
-@Suppress("LongMethod", "LongParameterList")
+@Suppress("LongMethod")
 @Composable
-fun ModelSwitcherDialog(
-    settings: AppSettings,
-    state: ProviderModelsState?,
-    onProvider: (ProviderConfig) -> Unit,
-    onRefresh: (ProviderConfig) -> Unit,
-    onModel: (ProviderConfig, String) -> Unit,
-    onDismiss: () -> Unit,
-) {
+fun ModelSwitcherDialog(picker: ModelPickerModel, actions: ModelPickerActions) {
+    val settings = picker.settings
     val modern = settings.appDesign == AppDesign.MODERN
     var query by remember { mutableStateOf("") }
-    val provider = settings.providers.firstOrNull { it.id == settings.selectedProviderId }
-    val allModels = state?.models.orEmpty()
+    var forProject by remember { mutableStateOf(false) }
+    var showLocal by remember { mutableStateOf(picker.active.isLocal) }
+    var browsedId by remember {
+        mutableStateOf(
+            picker.active.provider?.id ?: settings.selectedProviderId ?: settings.providers.firstOrNull()?.id,
+        )
+    }
+    val browsed = settings.providers.firstOrNull { it.id == browsedId }
+    val allModels = picker.cloudModels?.models.orEmpty()
     val models = allModels.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
-    val loading = state?.loading == true
-    Dialog(onDismissRequest = onDismiss) {
+    val loading = picker.cloudModels?.loading == true
+    val scope = if (forProject && !picker.retry) ModelScope.PROJECT else ModelScope.DIALOG
+    val choose: (ModelRef) -> Unit = { ref ->
+        actions.onSelect(ref, scope)
+        actions.onDismiss()
+    }
+    Dialog(onDismissRequest = actions.onDismiss) {
         Surface(
             modifier = Modifier
                 .fillMaxWidth()
                 .fillMaxHeight(DIALOG_HEIGHT_FRACTION),
-            shape = dialogShape(modern),
+            shape = RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp),
             color = if (modern) {
                 MaterialTheme.colorScheme.surfaceContainerHigh
             } else {
@@ -95,118 +132,70 @@ fun ModelSwitcherDialog(
             border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
         ) {
             Column(Modifier.fillMaxSize()) {
-                SwitcherHeader(onDismiss)
-                CurrentModelCard(provider, loading, modern) { provider?.let(onRefresh) }
+                SwitcherHeader(picker.retry, actions.onDismiss)
+                CurrentModelCard(picker, browsed, loading, modern, actions)
+                QuickChoices(picker, choose)
+                if (picker.project != null && !picker.retry) {
+                    ScopeRow(picker.project.name, forProject) { forProject = it }
+                }
                 FlowRow(
                     modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                     horizontalArrangement = Arrangement.spacedBy(6.dp),
                 ) {
                     settings.providers.forEach { item ->
                         FilterChip(
-                            selected = item.id == settings.selectedProviderId,
-                            onClick = { onProvider(item) },
+                            selected = !showLocal && item.id == browsedId,
+                            onClick = {
+                                showLocal = false
+                                browsedId = item.id
+                                query = ""
+                                actions.onBrowseProvider(item)
+                            },
                             label = { Text(item.name) },
                         )
                     }
-                }
-                OutlinedTextField(
-                    value = query,
-                    onValueChange = { query = it },
-                    label = { Text(stringResource(R.string.search_model)) },
-                    singleLine = true,
-                    trailingIcon = {
-                        if (query.isNotEmpty()) {
-                            IconButton(onClick = { query = "" }) {
-                                Icon(
-                                    Icons.Default.Clear,
-                                    contentDescription = stringResource(R.string.model_search_clear),
-                                )
-                            }
-                        }
-                    },
-                    shape = RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp),
-                    modifier = Modifier
-                        .fillMaxWidth()
-                        .padding(horizontal = 16.dp, vertical = 4.dp),
-                )
-                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
-                state?.error?.let {
-                    Text(
-                        it,
-                        color = MaterialTheme.colorScheme.error,
-                        style = MaterialTheme.typography.bodySmall,
-                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
+                    FilterChip(
+                        selected = showLocal,
+                        onClick = { showLocal = true },
+                        label = { Text(stringResource(R.string.model_chip_local)) },
                     )
                 }
-                if (!loading && state?.error == null && models.isEmpty()) {
-                    val emptyText = if (allModels.isEmpty()) R.string.model_list_empty else R.string.model_search_empty
-                    Text(
-                        stringResource(emptyText),
-                        style = MaterialTheme.typography.bodyMedium,
-                        color = MaterialTheme.colorScheme.onSurfaceVariant,
-                        modifier = Modifier.padding(16.dp),
+                if (showLocal) {
+                    LocalModelList(picker, modern, choose)
+                } else {
+                    CloudModelList(
+                        modifier = Modifier.weight(1f),
+                        picker = picker,
+                        browsed = browsed,
+                        models = models,
+                        query = query,
+                        onQuery = { query = it },
+                        loading = loading,
+                        modern = modern,
+                        onChoose = choose,
+                        onToggleFavorite = actions.onToggleFavorite,
                     )
                 }
-                LazyColumn(
-                    modifier = Modifier
-                        .weight(1f)
-                        .fillMaxWidth(),
-                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
-                    verticalArrangement = Arrangement.spacedBy(6.dp),
-                ) {
-                    items(models, key = { it }) { model ->
-                        ModelRow(
-                            model = model,
-                            selected = model == provider?.model,
-                            modern = modern,
-                            onClick = {
-                                provider?.let { onModel(it, model) }
-                                onDismiss()
-                            },
-                        )
-                    }
-                }
             }
         }
     }
 }
 
-@Composable
-private fun dialogShape(modern: Boolean): Shape =
-    RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp)
-
-@Composable
-private fun SwitcherHeader(onDismiss: () -> Unit) {
-    Row(
-        Modifier
-            .fillMaxWidth()
-            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
-        verticalAlignment = Alignment.CenterVertically,
-    ) {
-        Column(Modifier.weight(1f)) {
-            Text(
-                stringResource(R.string.model_picker_title),
-                style = MaterialTheme.typography.titleMedium,
-                fontWeight = FontWeight.SemiBold,
-            )
-            Text(
-                stringResource(R.string.model_picker_subtitle),
-                style = MaterialTheme.typography.bodySmall,
-                color = MaterialTheme.colorScheme.onSurfaceVariant,
-            )
-        }
-        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
-    }
-}
-
-/** Текущая модель отдельной карточкой; кнопка «Обновить» вынесена в свою строку и не сжимается идентификатором. */
+/** Действующая модель и откуда она взята; кнопки сброса возвращают диалог к проекту, а проект — к умолчанию. */
 @Composable
 private fun CurrentModelCard(
-    provider: ProviderConfig?,
+    picker: ModelPickerModel,
+    browsed: ProviderConfig?,
     loading: Boolean,
     modern: Boolean,
-    onRefresh: () -> Unit,
+    actions: ModelPickerActions,
 ) {
+    val active = picker.active
+    val sourceLabel = when (active.source) {
+        ModelSource.DIALOG -> R.string.model_source_dialog
+        ModelSource.PROJECT -> R.string.model_source_project
+        ModelSource.GLOBAL -> R.string.model_source_global
+    }
     Surface(
         modifier = Modifier
             .fillMaxWidth()
@@ -216,22 +205,40 @@ private fun CurrentModelCard(
         contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
     ) {
         Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
-            Text(
-                stringResource(R.string.model_current),
-                style = MaterialTheme.typography.labelMedium,
-            )
-            provider?.let {
-                Text(it.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
+            Text(stringResource(sourceLabel), style = MaterialTheme.typography.labelMedium)
+            active.ref?.let {
+                Text(
+                    ModelResolver.fullLabel(it, picker.settings.providers),
+                    style = MaterialTheme.typography.bodyMedium,
+                    fontWeight = FontWeight.SemiBold,
+                )
             }
             Text(
-                provider?.model?.takeIf { it.isNotBlank() }?.let(::breakableModelId)
+                ModelResolver.shortLabel(active.ref)?.let(::breakableModelId)
                     ?: stringResource(R.string.model_not_selected),
                 fontFamily = FontFamily.Monospace,
                 style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.fillMaxWidth(),
             )
-            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
-                TextButton(onClick = onRefresh, enabled = provider != null && !loading) {
+            active.problem?.let {
+                Text(
+                    stringResource(R.string.model_unavailable_badge) + ": " + stringResource(problemText(it)),
+                    style = MaterialTheme.typography.bodySmall,
+                    color = MaterialTheme.colorScheme.error,
+                )
+            }
+            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
+                if (!picker.retry && active.source == ModelSource.DIALOG) {
+                    TextButton(onClick = { actions.onClear(ModelScope.DIALOG) }) {
+                        Text(stringResource(R.string.model_reset_dialog))
+                    }
+                }
+                if (!picker.retry && picker.project?.model != null) {
+                    TextButton(onClick = { actions.onClear(ModelScope.PROJECT) }) {
+                        Text(stringResource(R.string.model_reset_project))
+                    }
+                }
+                TextButton(onClick = { browsed?.let(actions.onRefresh) }, enabled = browsed != null && !loading) {
                     Text(stringResource(if (loading) R.string.loading else R.string.action_refresh))
                 }
             }
@@ -239,45 +246,96 @@ private fun CurrentModelCard(
     }
 }
 
+@Suppress("LongParameterList", "LongMethod")
 @Composable
-private fun ModelRow(model: String, selected: Boolean, modern: Boolean, onClick: () -> Unit) {
-    Surface(
-        modifier = Modifier
-            .fillMaxWidth()
-            .clickable(onClick = onClick),
-        color = when {
-            selected -> MaterialTheme.colorScheme.primaryContainer
-            modern -> MaterialTheme.colorScheme.surfaceContainer
-            else -> MaterialTheme.colorScheme.surface
-        },
-        border = if (modern) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
-        shape = RoundedCornerShape(if (modern) 16.dp else 6.dp),
-    ) {
-        Row(
-            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
-            verticalAlignment = Alignment.CenterVertically,
-            horizontalArrangement = Arrangement.spacedBy(8.dp),
+private fun CloudModelList(
+    picker: ModelPickerModel,
+    browsed: ProviderConfig?,
+    models: List<String>,
+    query: String,
+    onQuery: (String) -> Unit,
+    loading: Boolean,
+    modern: Boolean,
+    onChoose: (ModelRef) -> Unit,
+    onToggleFavorite: (ModelRef) -> Unit,
+    modifier: Modifier = Modifier,
+) {
+    val state = picker.cloudModels
+    Column(modifier) {
+        OutlinedTextField(
+            value = query,
+            onValueChange = onQuery,
+            label = { Text(stringResource(R.string.search_model)) },
+            singleLine = true,
+            trailingIcon = {
+                if (query.isNotEmpty()) {
+                    IconButton(onClick = { onQuery("") }) {
+                        Icon(
+                            Icons.Default.Clear,
+                            contentDescription = stringResource(R.string.model_search_clear),
+                        )
+                    }
+                }
+            },
+            shape = RoundedCornerShape(if (modern) MODERN_RADIUS_DP.dp else CLASSIC_RADIUS_DP.dp),
+            modifier = Modifier
+                .fillMaxWidth()
+                .padding(horizontal = 16.dp, vertical = 4.dp),
+        )
+        CloudListStatus(state, loading, models.size)
+        LazyColumn(
+            modifier = Modifier.weight(1f).fillMaxWidth(),
+            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
+            verticalArrangement = Arrangement.spacedBy(6.dp),
         ) {
-            Text(
-                breakableModelId(model),
-                fontFamily = FontFamily.Monospace,
-                style = MaterialTheme.typography.bodySmall,
-                modifier = Modifier.weight(1f),
-            )
-            if (isFreeModel(model)) {
-                Text(
-                    stringResource(R.string.model_free_badge),
-                    style = MaterialTheme.typography.labelSmall,
-                    color = MaterialTheme.colorScheme.tertiary,
-                )
-            }
-            if (selected) {
-                Icon(
-                    Icons.Default.Check,
-                    contentDescription = stringResource(R.string.model_selected_cd),
-                    modifier = Modifier.size(18.dp),
+            items(models, key = { it }) { model ->
+                val ref = browsed?.let { ModelRef.cloud(it.id, model) }
+                ModelRow(
+                    title = breakableModelId(model),
+                    selected = ref != null && ref.key == picker.active.ref?.key,
+                    free = isFreeModel(model),
+                    favorite = ref != null && picker.settings.favoriteModels.any { it.key == ref.key },
+                    modern = modern,
+                    onClick = { ref?.let(onChoose) },
+                    onFavorite = ref?.let { { onToggleFavorite(it) } },
                 )
             }
         }
     }
 }
+
+@Composable
+private fun androidx.compose.foundation.layout.ColumnScope.LocalModelList(
+    picker: ModelPickerModel,
+    modern: Boolean,
+    onChoose: (ModelRef) -> Unit,
+) {
+    if (picker.localModels.isEmpty()) {
+        Text(
+            stringResource(R.string.model_local_empty),
+            style = MaterialTheme.typography.bodyMedium,
+            color = MaterialTheme.colorScheme.onSurfaceVariant,
+            modifier = Modifier.padding(16.dp),
+        )
+        return
+    }
+    LazyColumn(
+        modifier = Modifier.weight(1f).fillMaxWidth(),
+        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
+        verticalArrangement = Arrangement.spacedBy(6.dp),
+    ) {
+        items(picker.localModels, key = { it.path }) { local ->
+            val ref = ModelRef.local(local.path, local.name)
+            ModelRow(
+                title = breakableModelId(local.name),
+                subtitle = "${local.size / BYTES_IN_MB} MB",
+                selected = ref.key == picker.active.ref?.key && picker.active.ref.kind == ModelKind.LOCAL,
+                free = false,
+                favorite = false,
+                modern = modern,
+                onClick = { onChoose(ref) },
+                onFavorite = null,
+            )
+        }
+    }
+}


=== DIFF: app/src/test/java/com/secrethero/neurocode/HistoryAdapterTest.kt ===
@@ -0,0 +1,147 @@
+package com.secrethero.neurocode
+
+import com.secrethero.neurocode.ai.HistoryAdapter
+import com.secrethero.neurocode.ai.TargetCapabilities
+import com.secrethero.neurocode.data.ProjectManifest
+import com.secrethero.neurocode.model.AttachmentKind
+import com.secrethero.neurocode.model.ChatAttachment
+import com.secrethero.neurocode.model.ChatMessage
+import com.secrethero.neurocode.model.MessageRole
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.model.Project
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNull
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class HistoryAdapterTest {
+    private val cloud = TargetCapabilities(supportsTools = true, supportsImages = true, contextTokens = null)
+    private val local = TargetCapabilities(supportsTools = false, supportsImages = false, contextTokens = null)
+
+    private fun message(role: MessageRole, text: String, id: String = text) =
+        ChatMessage(id = id, role = role, content = text)
+
+    @Test
+    fun reasoningAndModelSignatureAreNotRepeated() {
+        val history = listOf(
+            message(MessageRole.USER, "q"),
+            ChatMessage(
+                role = MessageRole.ASSISTANT,
+                content = "a",
+                reasoning = "thinking",
+                generatedBy = ModelRef.cloud("p", "m"),
+            ),
+        )
+
+        val adapted = HistoryAdapter.adapt(history, cloud)
+
+        assertNull(adapted.messages[1].reasoning)
+        assertNull(adapted.messages[1].generatedBy)
+    }
+
+    @Test
+    fun toolResultsBecomeTextSummariesForModelsWithoutTools() {
+        val history = listOf(
+            message(MessageRole.USER, "q"),
+            ChatMessage(role = MessageRole.TOOL, content = "x".repeat(2_000), toolName = "read_file"),
+        )
+
+        val adapted = HistoryAdapter.adapt(history, local)
+
+        val summary = adapted.messages[1]
+        assertEquals(MessageRole.USER, summary.role)
+        assertNull(summary.toolName)
+        assertTrue(summary.content.startsWith("[сводка: результат инструмента read_file]"))
+        assertTrue(summary.content.length < 700)
+    }
+
+    @Test
+    fun toolResultsStayForModelsWithTools() {
+        val history = listOf(ChatMessage(role = MessageRole.TOOL, content = "ok", toolName = "t"))
+
+        assertEquals(MessageRole.TOOL, HistoryAdapter.adapt(history, cloud).messages.single().role)
+    }
+
+    @Test
+    fun imagesAreRemovedForModelsWithoutVision() {
+        val image = ChatAttachment(
+            name = "a.png",
+            mimeType = "image/png",
+            sizeBytes = 1,
+            relativePath = "p",
+            kind = AttachmentKind.IMAGE,
+        )
+        val history = listOf(ChatMessage(role = MessageRole.USER, content = "look", attachments = listOf(image)))
+
+        val adapted = HistoryAdapter.adapt(history, local)
+
+        assertTrue(adapted.imagesRemoved)
+        assertTrue(adapted.messages.single().attachments.isEmpty())
+        assertTrue(adapted.messages.single().content.contains("a.png"))
+    }
+
+    @Test
+    fun historyIsShortenedToFitSmallerContextWindowKeepingNewest() {
+        val history = (1..20).map { message(MessageRole.USER, "m$it " + "x".repeat(300), id = "id$it") }
+
+        val adapted = HistoryAdapter.adapt(history, cloud.copy(contextTokens = 400))
+
+        assertTrue(adapted.droppedMessages > 0)
+        assertEquals("id20", adapted.messages.last().id)
+        assertEquals(history.size, adapted.messages.size + adapted.droppedMessages)
+    }
+
+    @Test
+    fun lastMessageIsKeptEvenIfItAloneExceedsTheWindow() {
+        val history = listOf(message(MessageRole.USER, "x".repeat(10_000)))
+
+        val adapted = HistoryAdapter.adapt(history, cloud.copy(contextTokens = 100))
+
+        assertEquals(1, adapted.messages.size)
+        assertEquals(0, adapted.droppedMessages)
+    }
+
+    @Test
+    fun unknownWindowKeepsWholeHistory() {
+        val history = (1..50).map { message(MessageRole.USER, "m$it") }
+
+        assertEquals(50, HistoryAdapter.adapt(history, cloud).messages.size)
+    }
+
+    @Test
+    fun transcriptTakesNewestMessagesWithinLimit() {
+        val history = listOf(
+            message(MessageRole.USER, "old question"),
+            message(MessageRole.ASSISTANT, "old answer"),
+            message(MessageRole.USER, "new question"),
+            message(MessageRole.ASSISTANT, "new answer"),
+        )
+
+        val text = HistoryAdapter.transcript(history, maxChars = 40)
+
+        assertFalse(text.contains("old question"))
+        assertTrue(text.endsWith("Ассистент: new answer"))
+    }
+
+    @Test
+    fun projectManifestKeepsModelButDropsLocalPath() {
+        val project = Project(
+            id = "a",
+            name = "Demo",
+            rootPath = "/r",
+            model = ModelRef.local("/data/user/0/app/files/models/q.gguf", "q.gguf"),
+        )
+
+        val decoded = ProjectManifest.decode(ProjectManifest.forExport(project).encode())
+
+        assertEquals("Demo", decoded?.name)
+        assertEquals("q.gguf", decoded?.model?.localName)
+        assertNull(decoded?.model?.localPath)
+    }
+
+    @Test
+    fun brokenManifestIsIgnored() {
+        assertNull(ProjectManifest.decode("not json"))
+    }
+}


=== DIFF: app/src/test/java/com/secrethero/neurocode/ModelResolverTest.kt ===
@@ -0,0 +1,164 @@
+package com.secrethero.neurocode
+
+import com.secrethero.neurocode.ai.ModelProblem
+import com.secrethero.neurocode.ai.ModelResolver
+import com.secrethero.neurocode.ai.ModelSource
+import com.secrethero.neurocode.model.AppSettings
+import com.secrethero.neurocode.model.ChatSession
+import com.secrethero.neurocode.model.ModelKind
+import com.secrethero.neurocode.model.ModelRef
+import com.secrethero.neurocode.model.Project
+import com.secrethero.neurocode.model.ProviderConfig
+import kotlinx.serialization.json.Json
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNull
+import org.junit.Assert.assertTrue
+import org.junit.Test
+
+class ModelResolverTest {
+    private val openai = ProviderConfig(id = "p1", name = "OpenAI", baseUrl = "https://x/v1", model = "gpt-a")
+    private val router = ProviderConfig(id = "p2", name = "Router", baseUrl = "https://y/v1", model = "r-1")
+    private val settings = AppSettings(providers = listOf(openai, router), selectedProviderId = "p1")
+    private val project = Project(id = "pr", name = "Demo", rootPath = "/tmp/demo")
+    private val hasKey: (ProviderConfig) -> Boolean = { true }
+
+    @Test
+    fun usesGlobalModelWhenNothingIsOverridden() {
+        val result = ModelResolver.resolve(null, project, settings, hasKey)
+
+        assertEquals(ModelSource.GLOBAL, result.source)
+        assertEquals(ModelRef.cloud("p1", "gpt-a"), result.ref)
+        assertTrue(result.ready)
+    }
+
+    @Test
+    fun projectModelBeatsGlobal() {
+        val withModel = project.copy(model = ModelRef.cloud("p2", "r-9"))
+
+        val result = ModelResolver.resolve(null, withModel, settings, hasKey)
+
+        assertEquals(ModelSource.PROJECT, result.source)
+        assertEquals("r-9", result.provider?.model)
+        assertEquals("gpt-a", openai.model)
+    }
+
+    @Test
+    fun dialogModelBeatsProjectAndGlobal() {
+        val withModel = project.copy(model = ModelRef.cloud("p2", "r-9"))
+        val session = ChatSession(model = ModelRef.cloud("p1", "gpt-b"))
+
+        val result = ModelResolver.resolve(session, withModel, settings, hasKey)
+
+        assertEquals(ModelSource.DIALOG, result.source)
+        assertEquals("gpt-b", result.provider?.model)
+    }
+
+    @Test
+    fun globalLocalModelIsUsedWhenEnabled() {
+        val local = settings.copy(useLocalModel = true, localModelPath = "/m/a.gguf", localModelName = "a.gguf")
+
+        val result = ModelResolver.resolve(null, null, local, hasKey, fileExists = { true })
+
+        assertTrue(result.isLocal)
+        assertEquals(ModelRef.local("/m/a.gguf", "a.gguf"), result.ref)
+    }
+
+    @Test
+    fun projectCanUseLocalModelWhileGlobalIsCloud() {
+        val withModel = project.copy(model = ModelRef.local("/m/b.gguf", "b.gguf"))
+
+        val result = ModelResolver.resolve(null, withModel, settings, hasKey, fileExists = { true })
+
+        assertTrue(result.isLocal)
+        assertEquals(ModelSource.PROJECT, result.source)
+    }
+
+    @Test
+    fun missingProviderIsReportedWithoutFallingBackToAnotherModel() {
+        val withModel = project.copy(model = ModelRef.cloud("gone", "m"))
+
+        val result = ModelResolver.resolve(null, withModel, settings, hasKey)
+
+        assertFalse(result.ready)
+        assertEquals(ModelProblem.NO_PROVIDER, result.problem)
+        assertEquals(ModelSource.PROJECT, result.source)
+    }
+
+    @Test
+    fun missingKeyDisabledProviderAndMissingFileAreReported() {
+        val noKey = ModelResolver.resolve(null, null, settings, hasKey = { false })
+        val disabled = ModelResolver.resolve(
+            null,
+            null,
+            settings.copy(providers = listOf(openai.copy(enabled = false), router)),
+            hasKey,
+        )
+        val missingFile = ModelResolver.resolve(
+            ChatSession(model = ModelRef.local("/gone.gguf", "gone.gguf")),
+            null,
+            settings,
+            hasKey,
+            fileExists = { false },
+        )
+
+        assertEquals(ModelProblem.NO_KEY, noKey.problem)
+        assertEquals(ModelProblem.PROVIDER_DISABLED, disabled.problem)
+        assertEquals(ModelProblem.LOCAL_FILE_MISSING, missingFile.problem)
+    }
+
+    @Test
+    fun nothingSelectedWhenThereAreNoProviders() {
+        val result = ModelResolver.resolve(null, null, AppSettings(), hasKey)
+
+        assertNull(result.ref)
+        assertEquals(ModelProblem.NOTHING_SELECTED, result.problem)
+    }
+
+    @Test
+    fun recentModelsKeepNewestFirstWithoutDuplicatesAndLimit() {
+        val refs = (1..7).map { ModelRef.cloud("p1", "m$it") }
+        var recent = emptyList<ModelRef>()
+        refs.forEach { recent = ModelResolver.pushRecent(recent, it) }
+        recent = ModelResolver.pushRecent(recent, refs[4])
+
+        assertEquals(AppSettings.RECENT_MODELS_LIMIT, recent.size)
+        assertEquals(refs[4], recent.first())
+        assertEquals(recent.size, recent.map { it.key }.toSet().size)
+    }
+
+    @Test
+    fun favoriteTogglesOnAndOff() {
+        val ref = ModelRef.cloud("p1", "m")
+
+        val added = ModelResolver.toggleFavorite(emptyList(), ref)
+        val removed = ModelResolver.toggleFavorite(added, ref)
+
+        assertEquals(listOf(ref), added)
+        assertTrue(removed.isEmpty())
+    }
+
+    @Test
+    fun oldSavedDataWithoutModelFieldsStillLoads() {
+        val json = Json { ignoreUnknownKeys = true }
+
+        val project = json.decodeFromString(Project.serializer(), """{"id":"a","name":"n","rootPath":"/r"}""")
+        val session = json.decodeFromString(ChatSession.serializer(), """{"id":"s","messages":[]}""")
+        val app = json.decodeFromString(AppSettings.serializer(), "{}")
+
+        assertNull(project.model)
+        assertNull(session.model)
+        assertTrue(app.recentModels.isEmpty())
+    }
+
+    @Test
+    fun labelsDescribeCloudAndLocalModels() {
+        val cloud = ModelRef.cloud("p1", "gpt-a")
+        val local = ModelRef.local("/m/x.gguf", "x.gguf")
+
+        assertEquals("gpt-a", ModelResolver.shortLabel(cloud))
+        assertEquals("OpenAI · gpt-a", ModelResolver.fullLabel(cloud, settings.providers))
+        assertEquals("GGUF · x.gguf", ModelResolver.fullLabel(local, settings.providers))
+        assertEquals(ModelKind.LOCAL, local.kind)
+    }
+}


Investigate per the method in your instructions, then return the findings list.

## Assistant 2026-10-23T21:25:29.929Z
Failed to authenticate. API Error: 401 Invalid bearer token

