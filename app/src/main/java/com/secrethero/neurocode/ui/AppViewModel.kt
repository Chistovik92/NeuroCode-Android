package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.ai.AgentEvent
import com.secrethero.neurocode.git.GitCommitInfo
import com.secrethero.neurocode.git.GitStatus
import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatRunState
import com.secrethero.neurocode.model.FileNode
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings
    val projects = container.projects.projects
    val sessions = container.chats.sessions
    val approval = container.approvals.request
    val terminalLines = container.shell.lines
    val localModelState = container.localLlama.state

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()
    private val _chatRunState = MutableStateFlow<ChatRunState>(ChatRunState.Idle)
    val chatRunState: StateFlow<ChatRunState> = _chatRunState.asStateFlow()
    private val _streamingResponse = MutableStateFlow("")
    val streamingResponse: StateFlow<String> = _streamingResponse.asStateFlow()
    private val _agentLog = MutableStateFlow<List<String>>(emptyList())
    val agentLog: StateFlow<List<String>> = _agentLog.asStateFlow()
    private var chatJob: Job? = null

    private val _fileTree = MutableStateFlow<List<FileNode>>(emptyList())
    val fileTree: StateFlow<List<FileNode>> = _fileTree.asStateFlow()
    private val _openPath = MutableStateFlow<String?>(null)
    val openPath: StateFlow<String?> = _openPath.asStateFlow()
    private val _editorText = MutableStateFlow("")
    val editorText: StateFlow<String> = _editorText.asStateFlow()
    private val _editorDirty = MutableStateFlow(false)
    val editorDirty: StateFlow<Boolean> = _editorDirty.asStateFlow()

    private val _gitStatus = MutableStateFlow<GitStatus?>(null)
    val gitStatus: StateFlow<GitStatus?> = _gitStatus.asStateFlow()
    private val _gitDiff = MutableStateFlow("")
    val gitDiff: StateFlow<String> = _gitDiff.asStateFlow()
    private val _gitLog = MutableStateFlow<List<GitCommitInfo>>(emptyList())
    val gitLog: StateFlow<List<GitCommitInfo>> = _gitLog.asStateFlow()
    private val _gitRemoteUrl = MutableStateFlow<String?>(null)
    val gitRemoteUrl: StateFlow<String?> = _gitRemoteUrl.asStateFlow()
    private val _gitSyncBusy = MutableStateFlow(false)
    val gitSyncBusy: StateFlow<Boolean> = _gitSyncBusy.asStateFlow()
    private val _modelImportProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val modelImportProgress: StateFlow<Pair<Long, Long>?> = _modelImportProgress.asStateFlow()
    private val _exportProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val exportProgress: StateFlow<Pair<Int, Int>?> = _exportProgress.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                container.settings.initialize()
                container.projects.initialize()
                container.chats.initialize()
                val currentSettings = settings.value
                val selected = container.projects.get(currentSettings.selectedProjectId)
                    ?: projects.value.firstOrNull()
                if (selected != null) {
                    if (selected.id != currentSettings.selectedProjectId) {
                        container.settings.update { it.copy(selectedProjectId = selected.id) }
                    }
                    refreshProject(selected.id)
                }
                _activeSessionId.value = sessions.value.firstOrNull()?.id
            }.onFailure(::showError)
            _ready.value = true
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun exportProject(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            _notice.value = "Сначала выберите проект"
            return@launch
        }
        runCatching {
            _exportProgress.value = 0 to 0
            val count = container.projects.exportTree(projectId, uri) { copied, total ->
                _exportProgress.value = copied to total
            }
            _notice.value = "Экспортировано файлов: $count"
        }.onFailure(::showError)
        _exportProgress.value = null
    }

    fun createProject(name: String) = viewModelScope.launch {
        runCatching {
            val project = container.projects.create(name)
            container.settings.update { it.copy(selectedProjectId = project.id) }
            refreshProject(project.id)
        }.onFailure(::showError)
    }

    fun importProject(uri: Uri, name: String? = null) = viewModelScope.launch {
        runCatching {
            val project = container.projects.importTree(uri, name)
            container.settings.update { it.copy(selectedProjectId = project.id) }
            refreshProject(project.id)
        }.onFailure(::showError)
    }

    fun deleteProject(projectId: String) = viewModelScope.launch {
        runCatching {
            container.projects.delete(projectId)
            val next = projects.value.firstOrNull()
            container.settings.update { it.copy(selectedProjectId = next?.id) }
            _fileTree.value = emptyList()
            _openPath.value = null
            _editorText.value = ""
            next?.let { refreshProject(it.id) }
        }.onFailure(::showError)
    }

    fun selectProject(projectId: String) = viewModelScope.launch {
        runCatching {
            if (_editorDirty.value) saveOpenFileInternal()
            container.settings.update { it.copy(selectedProjectId = projectId) }
            _openPath.value = null
            _editorText.value = ""
            _editorDirty.value = false
            refreshProject(projectId)
        }.onFailure(::showError)
    }

    fun refreshFileTree() = viewModelScope.launch {
        settings.value.selectedProjectId?.let { projectId ->
            runCatching { _fileTree.value = container.projects.tree(projectId) }
                .onFailure(::showError)
        }
    }

    fun openFile(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            if (_editorDirty.value) saveOpenFileInternal()
            _editorText.value = container.projects.readText(projectId, path)
            _openPath.value = path
            _editorDirty.value = false
        }.onFailure(::showError)
    }

    fun updateEditorText(value: String) {
        if (_editorText.value != value) {
            _editorText.value = value
            _editorDirty.value = true
        }
    }

    fun saveOpenFile() = viewModelScope.launch {
        runCatching { saveOpenFileInternal() }.onFailure(::showError)
    }

    fun createFile(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.projects.createFile(projectId, path)
            refreshProject(projectId)
            openFile(path)
        }.onFailure(::showError)
    }

    fun createDirectory(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.projects.createDirectory(projectId, path)
            refreshProject(projectId)
        }.onFailure(::showError)
    }

    fun newChat() = viewModelScope.launch {
        runCatching {
            val session = container.chats.create(
                projectId = settings.value.selectedProjectId,
                providerId = settings.value.selectedProviderId,
            )
            _activeSessionId.value = session.id
        }.onFailure(::showError)
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
                _activeSessionId.value = sessions.value.firstOrNull()?.id
            }
        }.onFailure(::showError)
    }

    fun sendMessage(text: String) {
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
                _chatRunState.value = ChatRunState.Working("Модель думает")

                val answer = if (settings.value.useLocalModel) {
                    runLocal(sessionId, text.trim())
                } else {
                    runCloud(sessionId, history)
                }
                container.chats.append(
                    sessionId,
                    ChatMessage(role = MessageRole.ASSISTANT, content = answer),
                )
                _streamingResponse.value = ""
                _chatRunState.value = ChatRunState.Idle
                settings.value.selectedProjectId?.let { refreshProject(it) }
            } catch (cancelled: CancellationException) {
                _chatRunState.value = ChatRunState.Idle
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message ?: error::class.java.simpleName
                _chatRunState.value = ChatRunState.Failed(message)
                _error.value = message
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

    fun runTerminal(command: String) {
        val projectId = settings.value.selectedProjectId ?: return
        container.shell.start(projectId)
        container.shell.send(command)
    }

    fun startTerminal() {
        settings.value.selectedProjectId?.let(container.shell::start)
    }

    fun clearTerminal() = container.shell.clear()
    fun interruptTerminal() = container.shell.interrupt()

    fun refreshGit(stagedDiff: Boolean = false) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            _gitStatus.value = container.git.status(projectId)
            _gitDiff.value = container.git.diff(projectId, stagedDiff)
            _gitLog.value = container.git.log(projectId)
        }.onFailure {
            _gitStatus.value = null
            _gitDiff.value = ""
            _gitLog.value = emptyList()
        }
        _gitRemoteUrl.value = runCatching { container.git.remoteUrl(projectId) }.getOrNull()
    }

    fun connectRemote(url: String, username: String, token: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        gitSync {
            container.git.setRemoteUrl(projectId, url)
            container.settings.saveGitToken(projectId, token)
            container.settings.update {
                it.copy(gitUsernames = it.gitUsernames + (projectId to username.trim()))
            }
            _gitRemoteUrl.value = container.git.remoteUrl(projectId)
            "Remote сохранён: $url"
        }
    }

    fun pullRemote() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        gitSync {
            val result = container.git.pull(
                projectId,
                settings.value.gitUsernames[projectId].orEmpty(),
                container.settings.gitToken(projectId).orEmpty(),
            )
            refreshGitInternal(projectId)
            result
        }
    }

    fun pushRemote() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        gitSync {
            val result = container.git.push(
                projectId,
                settings.value.gitUsernames[projectId].orEmpty(),
                container.settings.gitToken(projectId).orEmpty(),
            )
            refreshGitInternal(projectId)
            result
        }
    }

    fun cloneProject(url: String, username: String, token: String) = viewModelScope.launch {
        runCatching {
            _gitSyncBusy.value = true
            val name = url.trimEnd('/').substringAfterLast('/')
                .removeSuffix(".git").ifBlank { "Клонированный проект" }
            val project = container.projects.register(name)
            try {
                container.git.clone(url, java.io.File(project.rootPath), username, token)
            } catch (error: Throwable) {
                container.projects.delete(project.id)
                throw error
            }
            container.settings.saveGitToken(project.id, token)
            container.settings.update {
                it.copy(
                    selectedProjectId = project.id,
                    gitUsernames = it.gitUsernames + (project.id to username.trim()),
                )
            }
            refreshProject(project.id)
            _notice.value = "Клонировано: ${project.name}"
        }.onFailure(::showError)
        _gitSyncBusy.value = false
    }

    private suspend fun gitSync(block: suspend () -> String) {
        try {
            _gitSyncBusy.value = true
            val message = block()
            _notice.value = message
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showError(error)
        } finally {
            _gitSyncBusy.value = false
        }
    }

    private suspend fun refreshGitInternal(projectId: String) {
        _gitStatus.value = container.git.status(projectId)
        _gitDiff.value = container.git.diff(projectId)
        _gitLog.value = container.git.log(projectId)
        _gitRemoteUrl.value = container.git.remoteUrl(projectId)
    }

    fun initGit() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            _gitStatus.value = container.git.init(projectId)
            refreshGit()
        }.onFailure(::showError)
    }

    fun stageAll() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.git.addAll(projectId)
            refreshGit(stagedDiff = true)
        }.onFailure(::showError)
    }

    fun commit(message: String, name: String, email: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.git.commit(projectId, message, name, email)
            refreshGit()
        }.onFailure(::showError)
    }

    fun selectProvider(providerId: String) = updateSettings {
        it.copy(selectedProviderId = providerId, useLocalModel = false)
    }

    fun setUseLocalModel(enabled: Boolean) = updateSettings { it.copy(useLocalModel = enabled) }
    fun setAgentMode(enabled: Boolean) = updateSettings { it.copy(agentMode = enabled) }
    fun setAllowAgentShell(enabled: Boolean) = updateSettings { it.copy(allowAgentShell = enabled) }
    fun setMaxAgentSteps(value: Int) = updateSettings { it.copy(maxAgentSteps = value.coerceIn(1, 20)) }

    fun saveProvider(provider: ProviderConfig, apiKey: String?) = viewModelScope.launch {
        runCatching { container.settings.upsertProvider(provider, apiKey) }
            .onFailure(::showError)
    }

    fun removeProvider(providerId: String) = viewModelScope.launch {
        runCatching { container.settings.removeProvider(providerId) }
            .onFailure(::showError)
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
        }.onFailure(::showError)
        _modelImportProgress.value = null
    }

    private suspend fun runCloud(sessionId: String, history: List<ChatMessage>): String {
        val current = settings.value
        val provider = current.providers.firstOrNull { it.id == current.selectedProviderId }
            ?: error("Выберите API-провайдера")
        val key = container.settings.apiKey(provider)
            ?: error("Добавьте API-ключ для ${provider.name}")
        val project = container.projects.get(current.selectedProjectId)
        return if (current.agentMode && project != null) {
            container.agent.run(
                projectId = project.id,
                projectName = project.name,
                provider = provider,
                apiKey = key,
                history = history,
                maxSteps = current.maxAgentSteps,
                allowAgentShell = current.allowAgentShell,
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
    }

    private suspend fun runLocal(sessionId: String, userMessage: String): String {
        val current = settings.value
        val path = current.localModelPath ?: error("Сначала импортируйте GGUF-модель")
        _chatRunState.value = ChatRunState.Working("Загрузка локальной модели")
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
            val filePath = _openPath.value
            if (filePath != null && _editorText.value.length <= 20_000) {
                append("\n\nТекущий файл: ").append(filePath)
                append("\n```\n").append(_editorText.value).append("\n```")
            }
        }
        _chatRunState.value = ChatRunState.Working("Локальная генерация")
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

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { container.settings.update(transform) }.onFailure(::showError)
        }
    }

    private suspend fun saveOpenFileInternal() {
        val projectId = settings.value.selectedProjectId ?: return
        val path = _openPath.value ?: return
        if (!_editorDirty.value) return
        container.projects.writeText(projectId, path, _editorText.value)
        _editorDirty.value = false
        _fileTree.value = container.projects.tree(projectId)
    }

    private suspend fun refreshProject(projectId: String) {
        _fileTree.value = container.projects.tree(projectId)
        container.shell.start(projectId)
        runCatching {
            _gitStatus.value = container.git.status(projectId)
            _gitDiff.value = container.git.diff(projectId)
            _gitLog.value = container.git.log(projectId)
        }.onFailure {
            _gitStatus.value = null
            _gitDiff.value = ""
            _gitLog.value = emptyList()
        }
    }

    private fun showError(error: Throwable) {
        _error.value = error.message ?: error::class.java.simpleName
    }

    override fun onCleared() {
        container.shell.close()
        super.onCleared()
    }
}
