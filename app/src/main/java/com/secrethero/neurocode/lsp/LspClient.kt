package com.secrethero.neurocode.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class LspDiagnostic(
    val line: Int,
    val column: Int,
    val severity: Int,
    val message: String,
    val source: String?,
)

class LspClient(
    private val command: List<String>,
    private val workingDir: File,
    private val onDiagnostics: (String, List<LspDiagnostic>) -> Unit,
    private val onError: (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var nextRequestId = 1

    var running: Boolean = false
        private set

    fun start() {
        if (running) return
        runCatching {
            val proc = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(false)
                .start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            running = true
            readerJob = scope.launch {
                runCatching { readLoop(proc.inputStream) }
                    .onFailure { if (running) onError(it) }
            }
        }.onFailure {
            onError(it)
            stop()
        }
    }

    fun initialize(workspaceUri: String) {
        notify(
            "initialize",
            buildJsonObject {
                put("processId", JsonPrimitive(null as Int?))
                put("rootUri", JsonPrimitive(workspaceUri))
                put(
                    "capabilities",
                    buildJsonObject { },
                )
            },
        )
        notify("initialized", buildJsonObject { })
    }

    fun didOpen(fileUri: String, languageId: String, version: Int, text: String) {
        notify(
            "textDocument/didOpen",
            buildJsonObject {
                put(
                    "textDocument",
                    buildJsonObject {
                        put("uri", fileUri)
                        put("languageId", languageId)
                        put("version", version)
                        put("text", text)
                    },
                )
            },
        )
    }

    fun didChange(fileUri: String, version: Int, text: String) {
        notify(
            "textDocument/didChange",
            buildJsonObject {
                put(
                    "textDocument",
                    buildJsonObject {
                        put("uri", fileUri)
                        put("version", version)
                    },
                )
                put(
                    "contentChanges",
                    JsonArray(
                        listOf(
                            buildJsonObject { put("text", text) },
                        ),
                    ),
                )
            },
        )
    }

    fun didSave(fileUri: String, text: String) {
        notify(
            "textDocument/didSave",
            buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", fileUri) })
                put("text", text)
            },
        )
    }

    fun didClose(fileUri: String) {
        notify(
            "textDocument/didClose",
            buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", fileUri) })
            },
        )
    }

    fun stop() {
        running = false
        runCatching {
            request("shutdown", buildJsonObject { })
            notify("exit", buildJsonObject { })
        }
        writer?.runCatching { close() }
        writer = null
        process?.destroy()
        process = null
        readerJob?.cancel()
        readerJob = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun notify(method: String, params: JsonObject) {
        send(buildMessage(method, params))
    }

    private fun request(method: String, params: JsonObject) {
        val id = nextRequestId++
        send(buildRequest(id, method, params))
    }

    private fun buildMessage(method: String, params: JsonObject): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }

    private fun buildRequest(id: Int, method: String, params: JsonObject): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

    @Synchronized
    private fun send(message: JsonObject) {
        val output = writer ?: return
        runCatching {
            val body = json.encodeToString(JsonObject.serializer(), message)
            output.write("Content-Length: ${body.toByteArray().size}\r\n\r\n$body")
            output.flush()
        }.onFailure { if (running) onError(it) }
    }

    private fun readLoop(input: InputStream) {
        while (running) {
            val length = readHeaders(input) ?: break
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(body, offset, length - offset)
                if (read < 0) throw IOException("LSP stream closed")
                offset += read
            }
            handleMessage(String(body, Charsets.UTF_8))
        }
    }

    private fun readHeaders(input: InputStream): Int? {
        var contentLength: Int? = null
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) return contentLength
            val separator = line.indexOf(':')
            if (separator > 0 &&
                line.substring(0, separator).equals(CONTENT_LENGTH_HEADER, ignoreCase = true)
            ) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull()
            }
        }
    }

    private fun readHeaderLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            val ch = value.toChar()
            if (ch == '\n') return builder.toString().trimEnd('\r')
            builder.append(ch)
            if (builder.length > MAX_HEADER_LENGTH) throw IOException("LSP header too long")
        }
    }

    private fun handleMessage(raw: String) {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return
        val obj = element as? JsonObject ?: return
        val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
        if (method != "textDocument/publishDiagnostics") return
        val params = obj["params"]?.jsonObject ?: return
        val uri = params["uri"]?.jsonPrimitive?.contentOrNull ?: return
        val diagnostics = params["diagnostics"]?.jsonArray?.mapNotNull { item ->
            val diagnostic = item as? JsonObject ?: return@mapNotNull null
            val range = diagnostic["range"]?.jsonObject
            val start = range?.get("start")?.jsonObject
            LspDiagnostic(
                line = start?.get("line")?.jsonPrimitive?.intOrNull ?: 0,
                column = start?.get("character")?.jsonPrimitive?.intOrNull ?: 0,
                severity = diagnostic["severity"]?.jsonPrimitive?.intOrNull ?: SEVERITY_INFO,
                message = diagnostic["message"]?.jsonPrimitive?.contentOrNull ?: "",
                source = diagnostic["source"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        onDiagnostics(uri, diagnostics)
    }

    companion object {
        private const val CONTENT_LENGTH_HEADER = "Content-Length"
        private const val MAX_HEADER_LENGTH = 256
        const val SEVERITY_ERROR = 1
        const val SEVERITY_WARNING = 2
        const val SEVERITY_INFO = 3

        fun splitCommand(template: String): List<String> =
            template.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
}
