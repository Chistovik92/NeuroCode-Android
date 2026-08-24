package com.secrethero.neurocode.lsp

import com.secrethero.neurocode.model.AppSettings
import com.secrethero.neurocode.terminal.ProotManager
import com.secrethero.neurocode.util.CodeLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class LspController(
    private val proot: ProotManager,
    private val settings: StateFlow<AppSettings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _diagnostics = MutableStateFlow<List<LspDiagnostic>>(emptyList())
    val diagnostics: StateFlow<List<LspDiagnostic>> = _diagnostics.asStateFlow()

    private var client: LspClient? = null
    private var projectRoot: File? = null
    private var openRelativePath: String? = null
    private var versionCounter = 0
    private var debounceJob: Job? = null

    fun onFileOpened(root: File, relativePath: String, text: String) {
        projectRoot = root
        openRelativePath = relativePath
        _diagnostics.value = emptyList()
        val started = ensureStarted(root)
        versionCounter++
        started?.didOpen(virtualUri(relativePath), languageId(relativePath), versionCounter, text)
    }

    fun onTextChanged(relativePath: String, text: String) {
        if (!isTracking(relativePath)) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            versionCounter++
            client?.didChange(virtualUri(relativePath), versionCounter, text)
        }
    }

    fun onFileSaved(relativePath: String, text: String) {
        if (!isTracking(relativePath)) return
        client?.didSave(virtualUri(relativePath), text)
    }

    fun onFileClosed() {
        openRelativePath?.let { client?.didClose(virtualUri(it)) }
        openRelativePath = null
        _diagnostics.value = emptyList()
    }

    fun onProjectChanged() {
        reset()
    }

    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        openRelativePath = null
        _diagnostics.value = emptyList()
        client?.close()
        client = null
    }

    fun close() {
        reset()
        scope.cancel()
    }

    private fun isTracking(relativePath: String): Boolean =
        client != null && openRelativePath == relativePath

    private fun ensureStarted(root: File): LspClient? {
        val config = settings.value
        if (!config.lspEnabled || config.lspCommand.isBlank()) return null
        client?.let { return it }
        val baseCommand = LspClient.splitCommand(config.lspCommand)
        if (baseCommand.isEmpty()) return null
        val fullCommand = proot.command(baseCommand, root)
        val created = LspClient(
            command = fullCommand,
            workingDir = root,
            onDiagnostics = { uri, diagnostics ->
                val tracked = openRelativePath
                if (tracked != null && uri.endsWith("/$tracked")) {
                    _diagnostics.value = diagnostics
                }
            },
        )
        created.start()
        created.initialize(VIRTUAL_WORKSPACE_URI)
        client = created
        return created
    }

    private fun virtualUri(relativePath: String): String =
        "$VIRTUAL_WORKSPACE_URI/$relativePath"

    private fun languageId(relativePath: String): String =
        CodeLanguages.byFileName(relativePath)?.id ?: "plaintext"

    companion object {
        private const val DEBOUNCE_MS = 700L
        private const val VIRTUAL_WORKSPACE_URI = "file:///workspace"
    }
}
