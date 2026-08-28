package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.AgentEvent
import com.secrethero.neurocode.ai.PreparedAttachment
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.ModelLimits
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ProviderConfig
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

    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { container.chats.initialize() }.onFailure(container.bus::showError)
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
        if ((text.isBlank() && attached.isEmpty()) || chatJob?.isActive == true) return
        chatJob = viewModelScope.launch {
            try {
                var sessionId = _activeSessionId.value
                if (sessionId == null) {
                    sessionId = container.chats.create(
                        settings.value.selectedProjectId,
                        settings.value.selectedProviderId,
                    ).id
                    _activeSessionId.value = sessionId
                }
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
                val history = session?.messages.orEmpty()
                // Проект берём из самого диалога, а не из текущего выбора: иначе история
                // старого проекта применялась бы к файлам нового.
                val projectId = session?.projectId ?: settings.value.selectedProjectId
                _streamingResponse.value = ""
                _streamingReasoning.value = ""
                _agentLog.value = emptyList()
                _chatRunState.value = ChatRunState.Working(str(R.string.status_thinking))

                val answer = if (settings.value.useLocalModel) {
                    runLocal(sessionId, text.trim(), editorContext, attached)
                } else {
                    runCloud(sessionId, projectId, history)
                }
                container.chats.append(
                    sessionId,
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = answer,
                        reasoning = _streamingReasoning.value.takeIf { it.isNotBlank() },
                    ),
                )
                _streamingResponse.value = ""
                _streamingReasoning.value = ""
                _chatRunState.value = ChatRunState.Idle
                projectsMutated.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                _chatRunState.value = ChatRunState.Idle
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message ?: error::class.java.simpleName
                _chatRunState.value = ChatRunState.Failed(message)
                container.bus.showError(error)
            }
        }
    }

    fun cancelChat() {
        chatJob?.cancel()
        chatJob = null
        _chatRunState.value = ChatRunState.Idle
    }

    fun approveTool(approved: Boolean) {
        container.approvals.resolve(approved)
    }

    private suspend fun runCloud(
        sessionId: String,
        projectId: String?,
        history: List<ChatMessage>,
    ): String {
        val current = settings.value
        val provider = current.providers.firstOrNull { it.id == current.selectedProviderId }
            ?: error(str(R.string.error_select_provider))
        val key = container.settings.apiKey(provider)
            ?: error(str(R.string.error_add_key_format, provider.name))
        val project = container.projects.get(projectId)
        val request = CloudRequest(
            current = current,
            project = project,
            history = history,
            attachments = prepareAttachments(projectId, history),
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
            _chatRunState.value =
                ChatRunState.Working(str(R.string.status_fallback_format, fallback.name))
            runCloudAttempt(request, fallback, fallbackKey)
        }
        runPostChecks(current, project, sessionId)
        return answer
    }

    private fun fallbackProvider(current: AppSettings, primaryId: String?): ProviderConfig? {
        if (current.useLocalModel) return null
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

    fun switchProvider(providerId: String) = updateSettings {
        it.copy(selectedProviderId = providerId, useLocalModel = false)
    }

    fun setProviderModel(provider: ProviderConfig, model: String) = viewModelScope.launch {
        runCatching {
            container.settings.upsertProvider(provider.copy(model = model), null)
        }.onFailure(container.bus::showError)
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

    private suspend fun runLocal(
        sessionId: String,
        userMessage: String,
        editorContext: EditorContext?,
        attachments: List<ChatAttachment> = emptyList(),
    ): String {
        val current = settings.value
        val path = current.localModelPath ?: error(str(R.string.error_import_model_first))
        _chatRunState.value = ChatRunState.Working(str(R.string.status_loading_model))
        container.localLlama.load(
            path = path,
            systemPrompt = """
                Ты — локальный помощник программиста NeuroCode на Android.
                Отвечай по-русски. Пиши точный код и явно отмечай ограничения.
                У тебя нет прямого доступа к инструментам и файлам, кроме контекста в сообщении.
            """.trimIndent(),
            conversationKey = sessionId,
        )
        val projectId = settings.value.selectedProjectId
        val context = buildString {
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
    }
}

data class EditorContext(
    val path: String,
    val text: String,
)
