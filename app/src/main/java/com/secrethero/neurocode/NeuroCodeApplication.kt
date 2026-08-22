package com.secrethero.neurocode

import android.app.Application
import com.secrethero.neurocode.ai.AgentOrchestrator
import com.secrethero.neurocode.ai.AgentTools
import com.secrethero.neurocode.ai.LocalLlamaClient
import com.secrethero.neurocode.ai.OpenAiCompatibleClient
import com.secrethero.neurocode.data.ChatRepository
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.data.SettingsRepository
import com.secrethero.neurocode.git.GitRepository
import com.secrethero.neurocode.terminal.ApprovalGate
import com.secrethero.neurocode.terminal.ShellSession
import com.secrethero.neurocode.ui.UiMessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NeuroCodeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bus = UiMessageBus()

    val settings = SettingsRepository(application)
    val projects = ProjectRepository(application)
    val chats = ChatRepository(application)
    val git = GitRepository(projects)
    val shell = ShellSession(projects)
    val approvals = ApprovalGate()
    val agentTools = AgentTools(projects, git, shell, approvals)
    val agent = AgentOrchestrator(OpenAiCompatibleClient(), agentTools)
    val localLlama = LocalLlamaClient(application)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        scope.launch {
            runCatching {
                settings.initialize()
                projects.initialize()
                chats.initialize()
                val current = settings.settings.value
                if (projects.get(current.selectedProjectId) == null) {
                    projects.projects.value.firstOrNull()?.let { first ->
                        settings.update { it.copy(selectedProjectId = first.id) }
                    }
                }
            }.onFailure(bus::showError)
            _ready.value = true
        }
    }

    fun close() {
        shell.close()
        scope.cancel()
    }
}
