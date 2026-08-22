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

class NeuroCodeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val settings = SettingsRepository(application)
    val projects = ProjectRepository(application)
    val chats = ChatRepository(application)
    val git = GitRepository(projects)
    val shell = ShellSession(projects)
    val approvals = ApprovalGate()
    val agentTools = AgentTools(projects, git, shell, approvals)
    val agent = AgentOrchestrator(OpenAiCompatibleClient(), agentTools)
    val localLlama = LocalLlamaClient(application)
}
