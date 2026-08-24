package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.R
import com.secrethero.neurocode.ai.AgentEvent
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings
    val sessions = container.chats.sessions
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
    private var chatJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { container.chats.initialize() }.onFailure(container.bus::showError)
            _activeSessionId.value = container.chats.sessions.value.firstOrNull()?.id
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
        _agentLog.value = emptyList()
    }

    fun deleteChat(sessionId: String) = viewModelScope.launch {
        runCatching {
            container.chats.delete(sessionId)
            if (_activeSessionId.value == sessionId) {
                _activeSessionId.value = container.chats.sessions.value.firstOrNull()?.id
            }
        }.onFailure(container.bus::showError)
    }

    fun sendMessage(text: String, editorContext: EditorContext? = null) {
        if (text.isBlank() || chatJob?.isActive == true) return
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
                    ChatMessage(role = MessageRole.USER, content = text.trim()),
                )
                val history = container.chats.get(sessionId)?.messages.orEmpty()
                _streamingResponse.value = ""
                _agentLog.value = emptyList()
                _chatRunState.value = ChatRunState.Working(str(R.string.status_thinking))

                val answer = if (settings.value.useLocalModel) {
                    runLocal(sessionId, text.trim(), editorContext)
                } else {
                    runCloud(sessionId, history)
                }
                container.chats.append(
                    sessionId,
                    ChatMessage(role = MessageRole.ASSISTANT, content = answer),
                )
                _streamingResponse.value = ""
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

    private suspend fun runCloud(sessionId: String, history: List<ChatMessage>): String {
        val current = settings.value
        val provider = current.providers.firstOrNull { it.id == current.selectedProviderId }
            ?: error(str(R.string.error_select_provider))
        val key = container.settings.apiKey(provider)
            ?: error(str(R.string.error_add_key_format, provider.name))
        val project = container.projects.get(current.selectedProjectId)
        val skills = if (current.skillsEnabled) {
            current.skills.filter { it.enabled }.map { "${it.name}: ${it.prompt}" }
        } else {
            emptyList()
        }
        val externalTools = current.externalTools.filter { it.enabled }

        val answer = try {
            runCloudAttempt(current, project, provider, key, history, skills, externalTools, sessionId)
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
            runCloudAttempt(current, project, fallback, fallbackKey, history, skills, externalTools, sessionId)
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

    private suspend fun runCloudAttempt(
        current: AppSettings,
        project: com.secrethero.neurocode.model.Project?,
        provider: ProviderConfig,
        key: String,
        history: List<ChatMessage>,
        skills: List<String>,
        externalTools: List<com.secrethero.neurocode.model.ExternalAgentTool>,
        sessionId: String,
    ): String = if (current.agentMode && project != null) {
        val projectSummary = runCatching {
            container.projects.contextSummary(project.id)
        }.getOrDefault("")
        container.agent.run(
            projectId = project.id,
            projectName = project.name,
            projectSummary = projectSummary,
            provider = provider,
            apiKey = key,
            history = history,
            maxSteps = current.maxAgentSteps,
            allowAgentShell = current.allowAgentShell,
            activeSkills = skills,
            externalTools = externalTools,
            onEvent = { onAgentEvent(sessionId, it) },
        )
    } else {
        container.agent.chat(
            provider,
            key,
            history,
            onEvent = { onAgentEvent(sessionId, it) },
        )
    }

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
        val context = buildString {
            append(userMessage)
            if (editorContext != null && editorContext.text.length <= 20_000) {
                append("\n\nТекущий файл: ").append(editorContext.path)
                append("\n```\n").append(editorContext.text).append("\n```")
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
}

data class EditorContext(
    val path: String,
    val text: String,
)
