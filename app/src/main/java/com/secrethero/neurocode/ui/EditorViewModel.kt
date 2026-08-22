package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.model.FileNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container
    private val bus = container.bus

    val settings = container.settings.settings
    private val _fileTree = MutableStateFlow<List<FileNode>>(emptyList())
    val fileTree: StateFlow<List<FileNode>> = _fileTree.asStateFlow()
    private val _openPath = MutableStateFlow<String?>(null)
    val openPath: StateFlow<String?> = _openPath.asStateFlow()
    private val _editorText = MutableStateFlow("")
    val editorText: StateFlow<String> = _editorText.asStateFlow()
    private val _editorDirty = MutableStateFlow(false)
    val editorDirty: StateFlow<Boolean> = _editorDirty.asStateFlow()

    private var loadedProjectId: String? = null

    init {
        viewModelScope.launch {
            container.settings.settings
                .map { it.selectedProjectId }
                .distinctUntilChanged()
                .collectLatest { projectId -> onProjectChanged(projectId) }
        }
    }

    private suspend fun onProjectChanged(projectId: String?) {
        if (loadedProjectId != null && projectId != loadedProjectId && _editorDirty.value) {
            saveOpenFileInternal(loadedProjectId)
        }
        loadedProjectId = projectId
        _openPath.value = null
        _editorText.value = ""
        _editorDirty.value = false
        refreshTree(projectId)
    }

    fun currentContext(): EditorContext? {
        val path = _openPath.value ?: return null
        return EditorContext(path, _editorText.value)
    }

    fun refreshTree() = viewModelScope.launch {
        refreshTree(settings.value.selectedProjectId)
    }

    fun openFile(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            if (_editorDirty.value) saveOpenFileInternal(projectId)
            _editorText.value = container.projects.readText(projectId, path)
            _openPath.value = path
            _editorDirty.value = false
        }.onFailure(bus::showError)
    }

    fun updateEditorText(value: String) {
        if (_editorText.value != value) {
            _editorText.value = value
            _editorDirty.value = true
        }
    }

    fun saveOpenFile() = viewModelScope.launch {
        runCatching {
            saveOpenFileInternal(settings.value.selectedProjectId)
            refreshTree()
        }.onFailure(bus::showError)
    }

    fun createFile(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.projects.createFile(projectId, path)
            refreshTree(projectId)
            openFile(path)
        }.onFailure(bus::showError)
    }

    fun createDirectory(path: String) = viewModelScope.launch {
        val projectId = settings.value.selectedProjectId ?: return@launch
        runCatching {
            container.projects.createDirectory(projectId, path)
            refreshTree(projectId)
        }.onFailure(bus::showError)
    }

    private suspend fun saveOpenFileInternal(projectId: String?) {
        val path = _openPath.value ?: return
        if (!_editorDirty.value) return
        container.projects.writeText(projectId ?: return, path, _editorText.value)
        _editorDirty.value = false
    }

    private suspend fun refreshTree(projectId: String?) {
        if (projectId == null) {
            _fileTree.value = emptyList()
            return
        }
        runCatching { _fileTree.value = container.projects.tree(projectId) }
            .onFailure(bus::showError)
    }
}
