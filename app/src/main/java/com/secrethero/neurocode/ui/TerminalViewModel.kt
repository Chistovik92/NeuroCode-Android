package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.terminal.TerminalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings
    val lines: StateFlow<List<TerminalLine>> = container.shell.lines

    fun runTerminal(command: String) {
        val projectId = settings.value.selectedProjectId ?: return
        // fork/exec и блокирующая запись — не для главного потока (ANR при старте proot).
        container.appScope.launch(Dispatchers.IO) {
            container.shell.start(projectId)
            container.shell.send(command)
        }
    }

    fun startTerminal() {
        val projectId = settings.value.selectedProjectId ?: return
        container.appScope.launch(Dispatchers.IO) { container.shell.start(projectId) }
    }

    fun clearTerminal() = container.shell.clear()

    fun interruptTerminal() = container.shell.interrupt()
}
