package com.secrethero.neurocode

import android.app.Application
import com.secrethero.neurocode.ai.AgentOrchestrator
import com.secrethero.neurocode.ai.AgentJobs
import com.secrethero.neurocode.ai.AgentInteraction
import com.secrethero.neurocode.ai.AgentWorkflows
import com.secrethero.neurocode.ai.SubagentPolicy
import com.secrethero.neurocode.model.AgentPermissionMode
import com.secrethero.neurocode.ai.AgentTools
import com.secrethero.neurocode.ai.LocalLlamaClient
import com.secrethero.neurocode.ai.OpenAiCompatibleClient
import com.secrethero.neurocode.ai.FsAgentTools
import com.secrethero.neurocode.ai.FsTools
import com.secrethero.neurocode.data.ChatRepository
import com.secrethero.neurocode.data.FilesystemAccess
import com.secrethero.neurocode.data.ProjectRepository
import com.secrethero.neurocode.data.SettingsRepository
import com.secrethero.neurocode.device.DeviceSpecs
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import java.io.File

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

    /** Скоуп для фоновых операций UI (терминал и т.п.); живёт с контейнером, отменяется в [close]. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val bus = UiMessageBus()

    /** Повреждённый файл состояния сохранён рядом, данные не затёрты — сообщаем пользователю. */
    private val reportCorrupt: (File) -> Unit = { file ->
        bus.showNotice(application.getString(R.string.notice_state_corrupt_format, file.name))
    }

    val settings = SettingsRepository(application, reportCorrupt)
    val projects = ProjectRepository(application, reportCorrupt)
    val chats = ChatRepository(application, reportCorrupt)
    val git = GitRepository(projects)
    val prootManager = ProotManager(application)
    val shell = ShellSession(projects, prootManager, application)
    val approvals = ApprovalGate()
    val interaction = AgentInteraction()
    val jobs = AgentJobs(com.secrethero.neurocode.ai.AgentCommandRunner { projectId, command, emit ->
        emit(shell.runOnce(projectId, command, 120_000))
    })
    val workflows = AgentWorkflows()
    val filesystem = FilesystemAccess(application)
    val apiClient = OpenAiCompatibleClient()
    private val agentToolsFs = FsTools(
        FsAgentTools(application, filesystem, projects, approvals) { settings.settings.value.fsRoots },
    )
    private val subagentPolicy = SubagentPolicy()
    private val subagentSlots = Semaphore(subagentPolicy.limits.maxParallel)
    private lateinit var subagentExecutor: suspend (String, String, String, Int) -> String
    val agentTools = AgentTools(
        application, projects, git, shell, approvals, agentToolsFs, interaction, jobs,
        runSubagent = { projectId, task, description, depth -> subagentExecutor(projectId, task, description, depth) },
        subagentPolicy = subagentPolicy,
    )
    val agent = AgentOrchestrator(apiClient, agentTools)
    val localLlama: LocalLlamaClient
    val localDoc = com.secrethero.neurocode.ai.LocalDocModelsClient(application)
    val huggingFace = com.secrethero.neurocode.ai.HuggingFaceClient(
        application,
    ) { settings.huggingFaceToken() }
    val lsp = LspController(prootManager, settings.settings)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        subagentExecutor = { projectId, task, description, depth ->
            subagentPolicy.requireCanSpawn(depth - 1, 0)
            subagentSlots.withPermit {
                withTimeout(subagentPolicy.limits.timeoutMillis) {
                    val current = settings.settings.value
                    val provider = current.providers.firstOrNull { it.id == current.selectedProviderId }
                        ?: error("Провайдер для субагента не выбран")
                    val key = settings.apiKey(provider) ?: error("API-ключ субагента не задан")
                    val nestedTools = AgentTools(
                        application, projects, git, shell, approvals, agentToolsFs, interaction, jobs,
                        runSubagent = subagentExecutor, subagentPolicy = subagentPolicy, subagentDepth = depth,
                    )
                    subagentPolicy.sanitizeResult(AgentOrchestrator(apiClient, nestedTools).run(
                        projectId = projectId,
                        projectName = projects.get(projectId)?.name.orEmpty(),
                        provider = provider,
                        apiKey = key,
                        history = emptyList(),
                        maxSteps = current.maxAgentSteps,
                        allowAgentShell = current.allowAgentShell,
                        externalTools = current.externalTools,
                        permissionMode = current.agentPermissionMode,
                        planMode = current.planMode,
                    ).let { if (description.isBlank()) it else "$description\n$it" })
                }
            }
        }
        val limitedDevice = runCatching {
            DeviceSpecs(application).snapshot().recommendation().limitedDevice
        }.getOrDefault(false)
        localLlama = LocalLlamaClient(application, limitedDevice)
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
        jobs.close()
        workflows.close()
        lsp.close()
        appScope.cancel()
        scope.cancel()
    }
}
