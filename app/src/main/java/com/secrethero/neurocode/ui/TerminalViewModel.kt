package com.secrethero.neurocode.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.secrethero.neurocode.NeuroCodeApplication
import com.secrethero.neurocode.terminal.TerminalLine
import kotlinx.coroutines.flow.StateFlow

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NeuroCodeApplication).container

    val settings = container.settings.settings
    val lines: StateFlow<List<TerminalLine>> = container.shell.lines

    fun runTerminal(command: String) {
        val projectId = settings.value.selectedProjectId ?: return
        container.shell.start(projectId)
        container.shell.send(command)
    }

    fun startTerminal() {
        settings.value.selectedProjectId?.let(container.shell::start)
    }

    fun clearTerminal() = container.shell.clear()

    fun interruptTerminal() = container.shell.interrupt()

    fun shutdown() = container.close()
}
