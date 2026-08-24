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
import com.secrethero.neurocode.lsp.LspController
import com.secrethero.neurocode.terminal.ApprovalGate
import com.secrethero.neurocode.terminal.ProotManager
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
    companion object {
        var instance: NeuroCodeApplication? = null
            private set
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
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
    val prootManager = ProotManager(application)
    val shell = ShellSession(projects, prootManager, application)
    val approvals = ApprovalGate()
    val apiClient = OpenAiCompatibleClient()
    val agentTools = AgentTools(application, projects, git, shell, approvals)
    val agent = AgentOrchestrator(apiClient, agentTools)
    val localLlama = LocalLlamaClient(application)
    val lsp = LspController(prootManager, settings.settings)

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
        scope.launch { runCatching { prootManager.initialize() } }
    }

    fun close() {
        shell.close()
        lsp.close()
        scope.cancel()
    }
}
