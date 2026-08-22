package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings
    val projects = container.projects.projects

    private val _exportProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val exportProgress: StateFlow<Pair<Int, Int>?> = _exportProgress.asStateFlow()
    private val _zipProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val zipProgress: StateFlow<Pair<Long, Long>?> = _zipProgress.asStateFlow()

    fun createProject(name: String) = viewModelScope.launch {
        runCatching {
            val project = container.projects.create(name)
            container.settings.update { it.copy(selectedProjectId = project.id) }
        }.onFailure(container.bus::showError)
    }

    fun importProject(uri: Uri, name: String? = null) = viewModelScope.launch {
        runCatching {
            val project = container.projects.importTree(uri, name)
            container.settings.update { it.copy(selectedProjectId = project.id) }
        }.onFailure(container.bus::showError)
    }

    fun deleteProject(projectId: String) = viewModelScope.launch {
        runCatching {
            container.projects.delete(projectId)
            val next = projects.value.firstOrNull()
            container.settings.update { it.copy(selectedProjectId = next?.id) }
        }.onFailure(container.bus::showError)
    }

    fun selectProject(projectId: String) = viewModelScope.launch {
        runCatching {
            container.settings.update { it.copy(selectedProjectId = projectId) }
        }.onFailure(container.bus::showError)
    }

    fun currentProject(): Project? =
        projects.value.firstOrNull { it.id == settings.value.selectedProjectId }

    fun exportProject(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            container.bus.showNotice("Сначала выберите проект")
            return@launch
        }
        runCatching {
            _exportProgress.value = 0 to 0
            val count = container.projects.exportTree(projectId, uri) { copied, total ->
                _exportProgress.value = copied to total
            }
            container.bus.showNotice("Экспортировано файлов: $count")
        }.onFailure(container.bus::showError)
        _exportProgress.value = null
    }

    fun exportProjectZip(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            container.bus.showNotice("Сначала выберите проект")
            return@launch
        }
        runCatching {
            _zipProgress.value = 0L to 0L
            val result = container.projects.exportZip(projectId, uri) { written, total ->
                _zipProgress.value = written to total
            }
            container.bus.showNotice(
                "Архив готов: ${result.files} файлов, ${result.bytes / BYTES_PER_MB} МБ",
            )
        }.onFailure(container.bus::showError)
        _zipProgress.value = null
    }

    private companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
