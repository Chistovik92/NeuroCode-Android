package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.AgentEvent
import com.secrethero.neurocode.ai.HistoryAdapter
import com.secrethero.neurocode.ai.ImportedModel
import com.secrethero.neurocode.ai.ModelProblem
import com.secrethero.neurocode.ai.ModelResolver
import com.secrethero.neurocode.ai.ModelSource
import com.secrethero.neurocode.ai.PreparedAttachment
import com.secrethero.neurocode.ai.ResolvedModel
import com.secrethero.neurocode.ai.TargetCapabilities
import com.secrethero.neurocode.device.DeviceProfile
import com.secrethero.neurocode.device.DeviceSpecs
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelLimits
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ProviderConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings

    /** Диалоги только выбранного проекта: история одного проекта не должна попадать в другой. */
    val sessions: StateFlow<List<ChatSession>> = combine(
        container.chats.sessions,
        container.settings.settings.map { it.selectedProjectId }.distinctUntilChanged(),
    ) { all, projectId ->
        all.filter { it.projectId == projectId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val approval = container.approvals.request
    val localModelState = container.localLlama.state
    val projectsMutated = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    private val _chatRunState = MutableStateFlow<ChatRunState>(ChatRunState.Idle)
    val chatRunState: StateFlow<ChatRunState> = _chatRunState.asStateFlow()
    private val _streamingResponse = MutableStateFlow("")
    val streamingResponse: StateFlow<String> = _streamingResponse.asStateFlow()
    private val _agentLog = MutableStateFlow<List<String>>(emptyList())
    val agentLog: StateFlow<List<String>> = _agentLog.asStateFlow()

    /** Размышления модели по текущему ответу (пока он генерируется). */
    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()

    /** Файлы, выбранные для следующего сообщения. */
    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** Лимиты и расход по последнему ответу провайдера. */
    val modelLimits: StateFlow<ModelLimits?> = container.apiClient.limits

    /** Действующая модель активного диалога: диалог → проект → глобальная по умолчанию. */
    val activeModel: StateFlow<ResolvedModel> = combine(
        container.settings.settings,
        container.projects.projects,
        container.chats.sessions,
        _activeSessionId,
    ) { current, projects, all, activeId ->
        ModelResolver.resolve(
            session = all.firstOrNull { it.id == activeId },
            project = projects.firstOrNull { it.id == current.selectedProjectId },
            settings = current,
            hasKey = { container.settings.apiKey(it) != null },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ResolvedModel(null, ModelSource.GLOBAL, problem = ModelProblem.NOTHING_SELECTED),
    )

    private val _localModels = MutableStateFlow<List<ImportedModel>>(emptyList())

    /** Импортированные GGUF для окна выбора модели. */
    val localModels: StateFlow<List<ImportedModel>> = _localModels.asStateFlow()

    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { container.chats.initialize() }.onFailure(container.bus::showError)
        }
        viewModelScope.launch {
            // Вложения лежат в папке проекта, где их выбрали, — в другом проекте файла не будет.
            container.settings.settings.map { it.selectedProjectId }.distinctUntilChanged()
                .collect { _pendingAttachments.value = emptyList() }
        }
        viewModelScope.launch {
            // Активный диалог всегда принадлежит выбранному проекту: при смене проекта
            // или удалении сессии переключаемся на последний диалог этого проекта.
            sessions.collect { visible ->
                if (visible.none { it.id == _activeSessionId.value }) {
                    _activeSessionId.value = visible.firstOrNull()?.id
                    _streamingResponse.value = ""
                    _streamingReasoning.value = ""
                    _agentLog.value = emptyList()
                }
            }
        }
    }

    fun newChat() = viewModelScope.launch {
        runCatching {
            val session = container.chats.create(
                projectId = settings.value.selectedProjectId,
                providerId = settings.value.selectedProviderId,
            )
            _activeSessionId.value = session.id
        }.onFailure(container.bus::showError)
    }

    fun selectChat(sessionId: String) {
        _activeSessionId.value = sessionId
        _streamingResponse.value = ""
        _streamingReasoning.value = ""
        _agentLog.value = emptyList()
    }

    /** Копирует выбранные через SAF файлы в проект и прикрепляет их к следующему сообщению. */
    fun attachFiles(uris: List<Uri>) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            container.bus.showNotice(str(R.string.notice_select_project_first))
            return@launch
        }
        uris.forEach { uri ->
            runCatching { container.projects.importAttachment(projectId, uri) }
                .onSuccess { attachment ->
                    _pendingAttachments.value = _pendingAttachments.value + attachment
                }
                .onFailure(container.bus::showError)
        }
    }

    /** Файл вложения на диске — нужен превью картинок в списке сообщений. */
    fun attachmentFile(attachment: ChatAttachment): java.io.File? {
        val projectId = sessions.value.firstOrNull { it.id == _activeSessionId.value }?.projectId
            ?: settings.value.selectedProjectId
            ?: return null
        return container.projects.attachmentFile(projectId, attachment)
    }

    fun removeAttachment(attachmentId: String) = viewModelScope.launch {
        val attachment = _pendingAttachments.value.firstOrNull { it.id == attachmentId }
            ?: return@launch
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == attachmentId }
        settings.value.selectedProjectId?.let { projectId ->
            runCatching { container.projects.deleteAttachment(projectId, attachment) }
        }
    }

    fun deleteChat(sessionId: String) = viewModelScope.launch {
        // Следующий активный диалог выберет коллектор sessions в init.
        runCatching { container.chats.delete(sessionId) }.onFailure(container.bus::showError)
    }

    fun sendMessage(text: String, editorContext: EditorContext? = null) {
        val attached = _pendingAttachments.value
        if (text.isBlank() && attached.isEmpty()) return
        launchTurn {
            val sessionId = ensureSession()
            container.chats.append(
                sessionId,
                ChatMessage(
                    role = MessageRole.USER,
                    content = text.trim(),
                    attachments = attached,
                ),
            )
            _pendingAttachments.value = emptyList()
            val session = container.chats.get(sessionId)
            answerTurn(
                Turn(
                    sessionId = sessionId,
                    // Проект берём из самого диалога, а не из текущего выбора: иначе история
                    // старого проекта применялась бы к файлам нового.
                    projectId = session?.projectId ?: settings.value.selectedProjectId,
                    history = session?.messages.orEmpty(),
                    request = UserRequest(text.trim(), attached, editorContext),
                    model = resolveModel(session),
                ),
            )
        }
    }

    /**
     * Повторяет ответ [assistantMessageId] другой моделью: запрос того же сообщения пользователя,
     * прежний ответ остаётся в истории, новый подписывается своей моделью.
     */
    fun retryWithModel(assistantMessageId: String, ref: ModelRef) {
        launchTurn {
            val session = container.chats.get(_activeSessionId.value) ?: return@launchTurn
            val answerIndex = session.messages.indexOfFirst { it.id == assistantMessageId }
            val userIndex = session.messages.take(answerIndex.coerceAtLeast(0))
                .indexOfLast { it.role == MessageRole.USER }
            if (answerIndex < 0 || userIndex < 0) return@launchTurn
            val request = session.messages[userIndex]
            answerTurn(
                Turn(
                    sessionId = session.id,
                    projectId = session.projectId ?: settings.value.selectedProjectId,
                    history = session.messages.take(userIndex + 1),
                    request = UserRequest(request.content, request.attachments, null),
                    model = resolveModel(ChatSession(model = ref), session.projectId),
                ),
            )
        }
    }

    private suspend fun ensureSession(): String {
        _activeSessionId.value?.let { return it }
        val created = container.chats.create(
            settings.value.selectedProjectId,
            settings.value.selectedProviderId,
        )
        _activeSessionId.value = created.id
        return created.id
    }

    private fun launchTurn(block: suspend () -> Unit) {
        if (chatJob?.isActive == true) return
        chatJob = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                clearStreaming()
                _chatRunState.value = ChatRunState.Idle
                throw cancelled
            } catch (error: Throwable) {
                clearStreaming()
                val message = error.message ?: error::class.java.simpleName
                _chatRunState.value = ChatRunState.Failed(message)
                container.bus.showError(error)
            }
        }
    }

    /** Сообщение пользователя с вложениями и контекстом редактора. */
    private class UserRequest(
        val text: String,
        val attachments: List<ChatAttachment>,
        val editorContext: EditorContext?,
    )

    /** Один запрос к модели: всё, что нужно, чтобы выполнить его независимо от настроек на момент ответа. */
    private class Turn(
        val sessionId: String,
        val projectId: String?,
        val history: List<ChatMessage>,
        val request: UserRequest,
        val model: ResolvedModel,
    )

    private val _retryRequest = MutableStateFlow<String?>(null)

    /** Сообщение ассистента, для которого пользователь хочет повторить ответ другой моделью. */
    val retryRequest: StateFlow<String?> = _retryRequest.asStateFlow()

    fun requestRetry(assistantMessageId: String) {
        _retryRequest.value = assistantMessageId
    }

    fun clearRetryRequest() {
        _retryRequest.value = null
    }

    private suspend fun answerTurn(turn: Turn) {
        check(turn.model.ready) { unavailableMessage(turn.model) }
        clearStreaming()
        _agentLog.value = emptyList()
        _chatRunState.value = ChatRunState.Working(str(R.string.status_thinking))
        val answer = if (turn.model.isLocal) runLocal(turn) else runCloud(turn)
        container.chats.append(
            turn.sessionId,
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = answer,
                reasoning = _streamingReasoning.value.takeIf { it.isNotBlank() },
                generatedBy = turn.model.ref,
            ),
        )
        clearStreaming()
        _chatRunState.value = ChatRunState.Idle
        projectsMutated.tryEmit(Unit)
    }

    private fun resolveModel(session: ChatSession?, projectId: String? = session?.projectId): ResolvedModel {
        val current = settings.value
        return ModelResolver.resolve(
            session = session,
            project = container.projects.get(projectId ?: current.selectedProjectId),
            settings = current,
            hasKey = { container.settings.apiKey(it) != null },
        )
    }

    private fun unavailableMessage(model: ResolvedModel): String {
        val source = str(
            when (model.source) {
                ModelSource.DIALOG -> R.string.model_source_dialog_genitive
                ModelSource.PROJECT -> R.string.model_source_project_genitive
                ModelSource.GLOBAL -> R.string.model_source_global_genitive
            },
        )
        val reason = str(
            when (model.problem) {
                ModelProblem.NO_PROVIDER -> R.string.model_problem_no_provider
                ModelProblem.PROVIDER_DISABLED -> R.string.model_problem_provider_disabled
                ModelProblem.NO_KEY -> R.string.model_problem_no_key
                ModelProblem.NO_MODEL_ID -> R.string.model_problem_no_model_id
                ModelProblem.LOCAL_NOT_IMPORTED -> R.string.model_problem_local_not_imported
                ModelProblem.LOCAL_FILE_MISSING -> R.string.model_problem_local_file_missing
                ModelProblem.NOTHING_SELECTED, null -> R.string.model_problem_nothing_selected
            },
        )
        val name = ModelResolver.shortLabel(model.ref) ?: "—"
        return str(R.string.error_model_unavailable_format, source, name, reason)
    }

    fun refreshLocalModels() = viewModelScope.launch {
        _localModels.value = runCatching { container.localLlama.listModels() }.getOrDefault(emptyList())
    }

    /** Куда применить выбор в окне выбора модели. */
    enum class ModelScope { DIALOG, PROJECT }

    fun selectModel(ref: ModelRef, scope: ModelScope) = viewModelScope.launch {
        runCatching {
            when (scope) {
                ModelScope.DIALOG -> container.chats.setModel(ensureSession(), ref)
                ModelScope.PROJECT -> {
                    val projectId = settings.value.selectedProjectId
                    if (projectId == null) {
                        container.bus.showNotice(str(R.string.notice_select_project_first))
                        return@launch
                    }
                    container.projects.setModel(projectId, ref)
                    // Модель проекта действует сразу: прежнее переопределение диалога снимается.
                    _activeSessionId.value?.let { container.chats.setModel(it, null) }
                }
            }
            container.settings.update { it.copy(recentModels = ModelResolver.pushRecent(it.recentModels, ref)) }
            warnIfHeavy(ref)
        }.onFailure(container.bus::showError)
    }

    /** Снимает выбор: диалог возвращается к модели проекта, проект — к глобальной модели. */
    fun clearModel(scope: ModelScope) = viewModelScope.launch {
        runCatching {
            when (scope) {
                ModelScope.DIALOG -> _activeSessionId.value?.let { container.chats.setModel(it, null) }
                ModelScope.PROJECT -> settings.value.selectedProjectId?.let { container.projects.setModel(it, null) }
            }
        }.onFailure(container.bus::showError)
    }

    fun toggleFavorite(ref: ModelRef) = updateSettings {
        it.copy(favoriteModels = ModelResolver.toggleFavorite(it.favoriteModels, ref))
    }

    /** Предупреждает, если выбранная GGUF тяжелее рекомендованного для этого телефона размера. */
    private fun warnIfHeavy(ref: ModelRef) {
        val file = ref.localPath?.takeIf { ref.kind == ModelKind.LOCAL }?.let(::File)?.takeIf { it.isFile }
        val recommendation = file?.let {
            runCatching { DeviceSpecs(getApplication()).snapshot().recommendation() }.getOrNull()
        }
        val sizeMb = (file?.length() ?: 0L) / DeviceProfile.MB
        if (recommendation != null && sizeMb > recommendation.maxModelSizeMb) {
            container.bus.showNotice(
                str(R.string.notice_model_oversize_format, sizeMb, recommendation.maxModelSizeMb),
            )
        }
    }

    /** Весь активный диалог одним текстом — для копирования из бокового меню. */
    fun conversationText(): String =
        sessions.value.firstOrNull { it.id == _activeSessionId.value }?.messages.orEmpty()
            .joinToString("\n\n") { message ->
                when (message.role) {
                    MessageRole.USER -> str(R.string.prefix_user, message.content)
                    MessageRole.ASSISTANT -> str(R.string.prefix_agent, message.content)
                    MessageRole.TOOL -> str(R.string.prefix_tool, message.toolName ?: "", message.content)
                    MessageRole.SYSTEM -> message.content
                }
            }

    fun cancelChat() {
        chatJob?.cancel()
        chatJob = null
        clearStreaming()
        _chatRunState.value = ChatRunState.Idle
    }

    /** Сбрасывает недописанный ответ, иначе «зависший» пузырь остаётся после отмены или ошибки. */
    private fun clearStreaming() {
        _streamingResponse.value = ""
        _streamingReasoning.value = ""
    }

    fun approveTool(approved: Boolean) {
        container.approvals.resolve(approved)
    }

    @Suppress("ThrowsCount")
    private suspend fun runCloud(turn: Turn): String {
        val current = settings.value
        val provider = checkNotNull(turn.model.provider)
        val key = container.settings.apiKey(provider)
            ?: error(str(R.string.error_add_key_format, provider.name))
        val project = container.projects.get(turn.projectId)
        val history = adaptHistory(
            turn.history,
            TargetCapabilities(
                supportsTools = true,
                supportsImages = true,
                contextTokens = container.apiClient.contextWindow(provider.model),
            ),
        )
        val sessionId = turn.sessionId
        val request = CloudRequest(
            current = current,
            project = project,
            history = history,
            attachments = prepareAttachments(turn.projectId, history),
            sessionId = sessionId,
        )

        val answer = try {
            runCloudAttempt(request, provider, key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (primaryError: Throwable) {
            val fallback = fallbackProvider(current, provider.id) ?: throw primaryError
            val fallbackKey = container.settings.apiKey(fallback) ?: throw primaryError
            appendAgentLog(
                str(
                    R.string.log_fallback_format,
                    provider.name,
                    primaryError.message ?: primaryError::class.java.simpleName,
                    fallback.name,
                ),
            )
            clearStreaming()
            _chatRunState.value =
                ChatRunState.Working(str(R.string.status_fallback_format, fallback.name))
            runCloudAttempt(request, fallback, fallbackKey)
        }
        runPostChecks(current, project, sessionId)
        return answer
    }

    /** Приводит историю к возможностям новой модели и сообщает пользователю, если что-то пришлось сократить. */
    private fun adaptHistory(history: List<ChatMessage>, target: TargetCapabilities): List<ChatMessage> {
        val adapted = HistoryAdapter.adapt(history, target)
        if (adapted.droppedMessages > 0) {
            container.bus.showNotice(str(R.string.notice_history_trimmed_format, adapted.droppedMessages))
        }
        if (adapted.imagesRemoved) container.bus.showNotice(str(R.string.notice_images_removed))
        return adapted.messages
    }

    private fun fallbackProvider(current: AppSettings, primaryId: String?): ProviderConfig? {
        val fallbackId = current.fallbackProviderId ?: return null
        if (fallbackId == primaryId) return null
        val fallback = current.providers.firstOrNull { it.id == fallbackId } ?: return null
        if (!fallback.enabled) return null
        return fallback
    }

    /** Всё, что не меняется между основной попыткой и фолбэком на резервного провайдера. */
    private class CloudRequest(
        val current: AppSettings,
        val project: com.secrethero.neurocode.model.Project?,
        val history: List<ChatMessage>,
        val attachments: Map<String, PreparedAttachment>,
        val sessionId: String,
    ) {
        val skills: List<String>
            get() = if (current.skillsEnabled) {
                current.skills.filter { it.enabled }.map { "${it.name}: ${it.prompt}" }
            } else {
                emptyList()
            }

        val externalTools = current.externalTools.filter { it.enabled }
    }

    private suspend fun runCloudAttempt(
        request: CloudRequest,
        provider: ProviderConfig,
        key: String,
    ): String {
        val project = request.project
        return if (request.current.agentMode && project != null) {
            val projectSummary = runCatching {
                container.projects.contextSummary(project.id)
            }.getOrDefault("")
            container.agent.run(
                projectId = project.id,
                projectName = project.name,
                projectSummary = projectSummary,
                provider = provider,
                apiKey = key,
                history = request.history,
                maxSteps = request.current.maxAgentSteps,
                allowAgentShell = request.current.allowAgentShell,
                activeSkills = request.skills,
                externalTools = request.externalTools,
                attachments = request.attachments,
                onEvent = { onAgentEvent(request.sessionId, it) },
            )
        } else {
            container.agent.chat(
                provider = provider,
                apiKey = key,
                history = request.history,
                attachments = request.attachments,
                onEvent = { onAgentEvent(request.sessionId, it) },
            )
        }
    }

    /**
     * Готовит вложения из истории к отправке: картинки — в data-URL, текстовые файлы —
     * содержимым, остальные — путём внутри проекта.
     */
    private suspend fun prepareAttachments(
        projectId: String?,
        history: List<ChatMessage>,
    ): Map<String, PreparedAttachment> {
        val attachments = if (projectId == null) emptyList() else history.flatMap { it.attachments }
        if (projectId == null || attachments.isEmpty()) return emptyMap()
        return attachments.associate { attachment ->
            val prepared = when (attachment.kind) {
                AttachmentKind.IMAGE -> {
                    val bytes = runCatching {
                        container.projects.readAttachmentBytes(projectId, attachment)
                    }.getOrNull()
                    val dataUrl = bytes
                        ?.takeIf { it.size <= MAX_INLINE_IMAGE_BYTES }
                        ?.let { "data:${attachment.mimeType};base64," + encodeBase64(it) }
                    PreparedAttachment(
                        name = attachment.name,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                        dataUrl = dataUrl,
                        path = attachment.relativePath.takeIf { dataUrl == null },
                    )
                }
                AttachmentKind.TEXT -> PreparedAttachment(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    text = runCatching {
                        container.projects.readAttachmentText(projectId, attachment)
                    }.getOrNull(),
                    path = attachment.relativePath,
                )
                AttachmentKind.BINARY -> PreparedAttachment(
                    name = attachment.name,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    path = attachment.relativePath,
                )
            }
            attachment.id to prepared
        }
    }

    private fun encodeBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private suspend fun runPostChecks(current: AppSettings, project: com.secrethero.neurocode.model.Project?, sessionId: String) {
        if (!current.postChecksEnabled || !current.agentMode) return
        val commands = current.postCheckCommands
        if (commands.isEmpty() || project == null) return
        _chatRunState.value =
            ChatRunState.Working(str(R.string.status_post_checks_format, commands.size))
        appendAgentLog(str(R.string.log_post_checks_start))
        commands.forEach { command ->
            appendAgentLog(str(R.string.log_post_check_run_format, command))
            val result = runCatching {
                container.shell.runOnce(project.id, command, 120_000)
            }.getOrElse { error ->
                str(R.string.error_launch_format, error.message ?: error::class.java.simpleName)
            }
            val failed = listOf("error", "failed", "failure").any { marker ->
                result.contains(marker, ignoreCase = true)
            }
            appendAgentLog(
                str(
                    R.string.log_post_check_result_format,
                    if (failed) str(R.string.post_check_failed) else str(R.string.post_check_ok),
                    result.take(800),
                ),
            )
        }
    }

    private val _switcherModels =
        MutableStateFlow<ProviderModelsState?>(null)
    val switcherModels: StateFlow<ProviderModelsState?> = _switcherModels.asStateFlow()

    fun loadSwitcherModels(provider: ProviderConfig) = viewModelScope.launch {
        _switcherModels.value = ProviderModelsState(emptyList(), null, true)
        runCatching {
            container.apiClient.models(provider, container.settings.apiKey(provider).orEmpty())
        }.onSuccess { models ->
            _switcherModels.value = ProviderModelsState(models, null, false)
        }.onFailure { error ->
            _switcherModels.value =
                ProviderModelsState(emptyList(), error.message ?: "ошибка", false)
        }
    }

    fun clearSwitcherModels() {
        _switcherModels.value = null
    }

    private suspend fun runLocal(turn: Turn): String {
        val ref = checkNotNull(turn.model.ref)
        val path = checkNotNull(ref.localPath)
        val userMessage = turn.request.text
        val editorContext = turn.request.editorContext
        val attachments = turn.request.attachments
        _chatRunState.value = ChatRunState.Working(str(R.string.status_loading_model))
        // Контекст в памяти движка актуален, только если предыдущий ответ дала эта же модель в этом диалоге.
        val lastAnswer = turn.history.lastOrNull { it.role == MessageRole.ASSISTANT }
        val warm = lastAnswer?.generatedBy?.key == ref.key &&
            container.localLlama.isLoadedFor(path, turn.sessionId)
        if (!warm) container.localLlama.unload()
        container.localLlama.load(
            path = path,
            systemPrompt = """
                Ты — локальный помощник программиста NeuroCode на Android.
                Отвечай по-русски. Пиши точный код и явно отмечай ограничения.
                У тебя нет прямого доступа к инструментам и файлам, кроме контекста в сообщении.
            """.trimIndent(),
            conversationKey = turn.sessionId,
        )
        // После перехода с другой модели (например, облачной) локальная видит прежний диалог текстом.
        val earlier = if (warm) "" else earlierDialog(turn.history.dropLast(1))
        val projectId = turn.projectId
        val context = buildString {
            if (earlier.isNotBlank()) {
                append(str(R.string.local_earlier_dialog_header)).append('\n').append(earlier).append("\n\n")
            }
            append(userMessage)
            if (editorContext != null && editorContext.text.length <= 20_000) {
                append("\n\nТекущий файл: ").append(editorContext.path)
                append("\n```\n").append(editorContext.text).append("\n```")
            }
            // Локальная модель не умеет в картинки и инструменты — отдаём только текст файлов.
            attachments.forEach { attachment ->
                append("\n\nВложение: ").append(attachment.name)
                if (attachment.kind == AttachmentKind.TEXT && projectId != null) {
                    val text = runCatching {
                        container.projects.readAttachmentText(projectId, attachment)
                    }.getOrNull()
                    if (!text.isNullOrBlank()) append("\n```\n").append(text).append("\n```")
                } else {
                    append(" (").append(attachment.mimeType).append(", содержимое не текстовое)")
                }
            }
        }
        _chatRunState.value = ChatRunState.Working(str(R.string.status_local_generation))
        val answer = StringBuilder()
        container.localLlama.generate(context).collect { token ->
            answer.append(token)
            _streamingResponse.value = answer.toString()
        }
        return answer.toString()
    }

    private fun earlierDialog(history: List<ChatMessage>): String {
        val target = TargetCapabilities(
            supportsTools = false,
            supportsImages = false,
            contextTokens = LOCAL_CONTEXT_TOKENS,
        )
        return HistoryAdapter.transcript(adaptHistory(history, target), LOCAL_HISTORY_CHARS)
    }

    private fun onAgentEvent(sessionId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.Status -> _chatRunState.value = ChatRunState.Working(event.text)
            is AgentEvent.Delta -> _streamingResponse.value += event.text
            is AgentEvent.Reasoning -> _streamingReasoning.value += event.text
            is AgentEvent.ToolStarted -> appendAgentLog("→ ${event.name}: ${event.arguments.take(500)}")
            is AgentEvent.ToolFinished -> {
                appendAgentLog("← ${event.name}: ${event.result.take(800)}")
                viewModelScope.launch {
                    runCatching {
                        container.chats.append(
                            sessionId,
                            ChatMessage(
                                role = MessageRole.TOOL,
                                content = event.result.take(4_000),
                                toolName = event.name,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun appendAgentLog(line: String) {
        _agentLog.value = (_agentLog.value + line).takeLast(50)
    }

    private fun str(resId: Int, vararg args: Any?): String =
        getApplication<Application>().getString(resId, *args)

    private fun updateSettings(transform: (AppSettings) -> AppSettings) =
        viewModelScope.launch {
            runCatching { (getApplication<NeuroCodeApplication>().container.settings.update(transform)) }
                .onFailure(container.bus::showError)
        }

    private companion object {
        /** Картинка крупнее уходит не data-URL, а путём в проекте: base64 раздувает запрос. */
        const val MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024

        /** Окно контекста локальной модели по умолчанию: движок его не сообщает, берём осторожное значение. */
        const val LOCAL_CONTEXT_TOKENS = 4_096
        const val LOCAL_HISTORY_CHARS = 6_000
    }
}

data class EditorContext(
    val path: String,
    val text: String,
)
