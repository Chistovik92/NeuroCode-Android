package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.secrethero.neurocode.NeuroCodeApplication
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val ready: StateFlow<Boolean> = container.ready
    val error = container.bus.error
    val notice = container.bus.notice
    fun clearError() = container.bus.clearError()
    fun clearNotice() = container.bus.clearNotice()

    val chat = ChatViewModel(application)
    val editor = EditorViewModel(application)
    val git = GitViewModel(application)
    val projects = ProjectsViewModel(application)
    val settingsScreen = SettingsViewModel(application)
    val terminal = TerminalViewModel(application)

    init {
        viewModelScope.launch {
            chat.projectsMutated.collect {
                editor.refreshTree()
                git.refresh()
            }
        }
    }

    override fun onCleared() {
        container.close()
        super.onCleared()
    }
}
