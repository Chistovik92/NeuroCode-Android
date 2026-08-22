package com.secrethero.neurocode.terminal

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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

data class TerminalLine(
    val text: String,
    val command: Boolean = false,
    val error: Boolean = false,
)

class ShellSession(private val projects: ProjectRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var activeProjectId: String? = null

    @Synchronized
    fun start(projectId: String) {
        if (process?.isAlive == true && activeProjectId == projectId) return
        stop()
        val root = projects.resolve(projectId, "")
        process = createProcess(root, listOf("/system/bin/sh")).also { shell ->
            writer = BufferedWriter(OutputStreamWriter(shell.outputStream))
            readerJob = scope.launch {
                shell.inputStream.bufferedReader().useLines { sequence ->
                    sequence.forEach { append(TerminalLine(it)) }
                }
                append(TerminalLine("[процесс завершён: ${runCatching { shell.exitValue() }.getOrDefault(-1)}]"))
            }
        }
        activeProjectId = projectId
        append(TerminalLine("NeuroCode shell · ${root.absolutePath}"))
        append(TerminalLine("Доступны команды Android /system/bin. Это не полный Linux-дистрибутив."))
    }

    @Synchronized
    fun send(command: String) {
        if (command.isBlank()) return
        val output = writer ?: return
        append(TerminalLine("$ $command", command = true))
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
        coroutineScope {
            val child = createProcess(root, listOf("/system/bin/sh", "-c", command))
            val reader = async(Dispatchers.IO) {
                child.inputStream.bufferedReader().readText()
            }
            try {
                val completed = child.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) {
                    child.destroyForcibly()
                }
                val output = withTimeoutOrNull(5_000) { reader.await() }
                    ?: "[не удалось получить весь вывод]"
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

    private fun createProcess(root: File, command: List<String>): Process =
        ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = root.absolutePath
                environment()["TMPDIR"] = File(root, ".neurocode/tmp").apply { mkdirs() }.absolutePath
                environment()["PATH"] = "/system/bin:/system/xbin"
                environment()["TERM"] = "xterm-256color"
            }
            .start()

    @Synchronized
    private fun append(line: TerminalLine) {
        _lines.value = (_lines.value + line).takeLast(2_000)
    }
}
