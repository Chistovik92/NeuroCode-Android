package com.secrethero.neurocode.terminal

import android.content.Context
import com.secrethero.neurocode.R
import com.secrethero.neurocode.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

data class TerminalLine(
    val text: String,
    val command: Boolean = false,
    val error: Boolean = false,
)

class ShellSession(
    private val projects: ProjectRepository,
    private val proot: ProotManager,
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var activeProjectId: String? = null
    private var interactiveCommandsSent = 0

    @Synchronized
    fun start(projectId: String) {
        if (process?.isAlive == true && activeProjectId == projectId) return
        val previousProjectId = activeProjectId
        stop()
        // Вывод чужого проекта не должен оставаться в буфере после переключения.
        if (previousProjectId != null && previousProjectId != projectId) {
            _lines.value = emptyList()
        }
        val root = projects.resolve(projectId, "")
        launchBackgroundInit()
        val shellCommand = interactiveShellCommand(root)
        process = createProcess(root, shellCommand).also { shell ->
            writer = BufferedWriter(OutputStreamWriter(shell.outputStream))
            readerJob = scope.launch {
                runCatching {
                    shell.inputStream.bufferedReader().useLines { sequence ->
                        sequence.forEach { append(TerminalLine(it)) }
                    }
                }
                append(
                    TerminalLine(
                        "[процесс завершён: ${runCatching { shell.exitValue() }.getOrDefault(-1)}]",
                        error = true,
                    ),
                )
            }
        }
        activeProjectId = projectId
        append(TerminalLine("NeuroCode shell · ${root.absolutePath}"))
    }

    @Synchronized
    fun send(command: String) {
        if (command.isBlank()) return
        val output = writer ?: return
        append(TerminalLine("$ $command", command = true))
        interactiveCommandsSent++
        runCatching {
            output.write(command)
            output.newLine()
            output.flush()
        }.onFailure {
            append(TerminalLine(it.message ?: "Ошибка терминала", error = true))
        }
    }

    @Synchronized
    fun interrupt() {
        process?.destroy()
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    @Synchronized
    fun stop() {
        writer?.close()
        writer = null
        process?.destroy()
        process = null
        readerJob?.cancel()
        readerJob = null
        activeProjectId = null
        interactiveCommandsSent = 0
    }

    fun close() {
        stop()
        scope.cancel()
    }

    suspend fun runOnce(
        projectId: String,
        command: String,
        timeoutMs: Long = 60_000,
    ): String = withContext(Dispatchers.IO) {
        val root = projects.resolve(projectId, "")
        val inner = listOf("/bin/sh", "-c", command)
        val commandList = if (proot.isReady()) {
            proot.command(inner, root)
        } else {
            listOf("/system/bin/sh", "-c", command)
        }
        coroutineScope {
            val child = createProcess(root, commandList)
            val reader = async(Dispatchers.IO) {
                child.inputStream.bufferedReader().readText()
            }
            try {
                val completed = child.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) {
                    child.destroyForcibly()
                }
                val output = reader.await()
                if (completed) {
                    "$output\n[код: ${child.exitValue()}]".trim()
                } else {
                    "$output\n[тайм-аут]".trim()
                }
            } finally {
                if (child.isAlive) child.destroyForcibly()
                reader.cancel()
            }
        }
    }

    private fun interactiveShellCommand(root: File): List<String> =
        if (proot.isReady()) {
            proot.command(listOf("/bin/sh", "-l"), root)
        } else {
            listOf("/system/bin/sh")
        }

    private fun launchBackgroundInit() {
        if (proot.isReady()) {
            append(TerminalLine(appContext.getString(R.string.banner_linux_active)))
            return
        }
        if (_initAttempted) return
        _initAttempted = true
        scope.launch {
            val prepared = runCatching { proot.initialize() }.getOrDefault(false)
            if (!prepared) {
                append(TerminalLine(appContext.getString(R.string.banner_linux_unavailable)))
                return@launch
            }
            append(TerminalLine(appContext.getString(R.string.banner_linux_ready)))
            synchronized(this@ShellSession) {
                // activeProjectId читаем до stop(): он его обнуляет, и перезапуск ушёл бы
                // в проект, который был активен на момент запуска proot.
                val current = activeProjectId
                if (interactiveCommandsSent == 0 && current != null) {
                    stop()
                    start(current)
                }
            }
        }
    }

    @Volatile
    private var _initAttempted = false

    private fun createProcess(root: File, command: List<String>): Process =
        ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = root.absolutePath
                environment()["TMPDIR"] = File(root, ".neurocode/tmp").apply { mkdirs() }.absolutePath
                environment()["PATH"] = if (proot.isReady()) {
                    "${ProotManager.GUEST_PATH}:/system/bin"
                } else {
                    "/system/bin:/system/xbin"
                }
                environment()["TERM"] = "xterm-256color"
            }
            .start()

    @Synchronized
    private fun append(line: TerminalLine) {
        _lines.value = (_lines.value + line).takeLast(2_000)
    }
}
