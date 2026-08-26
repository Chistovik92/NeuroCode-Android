package com.secrethero.neurocode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.R
import com.secrethero.neurocode.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container
    private val context = application

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

    private val _syncBusy = MutableStateFlow(false)
    val syncBusy: StateFlow<Boolean> = _syncBusy.asStateFlow()
    private val _syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val syncProgress: StateFlow<Pair<Int, Int>?> = _syncProgress.asStateFlow()

    fun linkedFolder(): String? =
        settings.value.selectedProjectId?.let { settings.value.linkedFolderByProject[it] }

    fun linkFolder(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        // Одна папка на два проекта = их файлы смешиваются при синхронизации.
        val takenBy = settings.value.linkedFolderByProject
            .filterValues { it == uri.toString() }
            .keys
            .firstOrNull { it != projectId }
        if (takenBy != null) {
            val owner = container.projects.get(takenBy)?.name ?: takenBy
            container.bus.showNotice(str(R.string.notice_folder_already_linked_format, owner))
            return@launch
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            container.settings.update {
                it.copy(linkedFolderByProject = it.linkedFolderByProject + (projectId to uri.toString()))
            }
            container.bus.showNotice(str(R.string.notice_folder_linked))
        }.onFailure(container.bus::showError)
    }

    fun syncToLinkedFolder() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        val stored = settings.value.linkedFolderByProject[projectId]
        if (stored == null) {
            container.bus.showNotice(str(R.string.notice_link_folder_first))
            return@launch
        }
        try {
            _syncBusy.value = true
            val count = container.projects.exportTree(projectId, Uri.parse(stored)) { _, _ -> }
            container.bus.showNotice(str(R.string.notice_synced_files_format, count))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            container.bus.showError(error)
        } finally {
            _syncBusy.value = false
        }
    }

    fun syncFromLinkedFolder() = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        val stored = settings.value.linkedFolderByProject[projectId]
        if (stored == null) {
            container.bus.showNotice(str(R.string.notice_link_folder_first))
            return@launch
        }
        try {
            _syncBusy.value = true
            _syncProgress.value = 0 to 0
            val result = container.projects.syncFromFolder(projectId, Uri.parse(stored)) { done, total ->
                _syncProgress.value = done to total
            }
            container.bus.showNotice(
                str(R.string.notice_from_folder_format, result.added, result.updated) +
                    if (result.skipped > 0) {
                        str(R.string.notice_skipped_suffix, result.skipped)
                    } else {
                        ""
                    },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            container.bus.showError(error)
        } finally {
            _syncBusy.value = false
            _syncProgress.value = null
        }
    }

    fun exportProject(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            container.bus.showNotice(str(R.string.notice_select_project_first))
            return@launch
        }
        runCatching {
            _exportProgress.value = 0 to 0
            val count = container.projects.exportTree(projectId, uri) { copied, total ->
                _exportProgress.value = copied to total
            }
            container.bus.showNotice(str(R.string.notice_exported_files_format, count))
        }.onFailure(container.bus::showError)
        _exportProgress.value = null
    }

    fun exportProjectZip(uri: Uri) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId
        if (projectId == null) {
            container.bus.showNotice(str(R.string.notice_select_project_first))
            return@launch
        }
        runCatching {
            _zipProgress.value = 0L to 0L
            val result = container.projects.exportZip(projectId, uri) { written, total ->
                _zipProgress.value = written to total
            }
            container.bus.showNotice(
                str(R.string.notice_zip_ready_format, result.files, result.bytes / BYTES_PER_MB),
            )
        }.onFailure(container.bus::showError)
        _zipProgress.value = null
    }

    private fun str(resId: Int, vararg args: Any?): String =
        context.getString(resId, *args)

    private companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
