package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.git.GitCommitInfo
import com.secrethero.neurocode.git.GitStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class GitViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings

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

    init {
        viewModelScope.launch {
            container.settings.settings
                .map { it.selectedProjectId }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun refresh(stagedDiff: Boolean = false) = viewModelScope.launch {
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

    fun initGit() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            _gitStatus.value = container.git.init(projectId)
            refresh()
        }.onFailure(container.bus::showError)
    }

    fun stageAll() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.git.addAll(projectId)
            refresh(stagedDiff = true)
        }.onFailure(container.bus::showError)
    }

    fun commit(message: String, name: String, email: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.git.commit(projectId, message, name, email)
            refresh()
        }.onFailure(container.bus::showError)
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
            refreshInternal(projectId)
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
            refreshInternal(projectId)
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
            container.bus.showNotice("Клонировано: ${project.name}")
        }.onFailure(container.bus::showError)
        _gitSyncBusy.value = false
    }

    private suspend fun gitSync(block: suspend () -> String) {
        try {
            _gitSyncBusy.value = true
            val message = block()
            container.bus.showNotice(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            container.bus.showError(error)
        } finally {
            _gitSyncBusy.value = false
        }
    }

    private suspend fun refreshInternal(projectId: String) {
        _gitStatus.value = container.git.status(projectId)
        _gitDiff.value = container.git.diff(projectId)
        _gitLog.value = container.git.log(projectId)
        _gitRemoteUrl.value = container.git.remoteUrl(projectId)
    }
}
