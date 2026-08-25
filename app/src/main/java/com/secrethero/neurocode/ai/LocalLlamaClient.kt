package com.secrethero.neurocode.ai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadataReader
import com.arm.aichat.isModelLoaded
import com.secrethero.neurocode.device.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ImportedModel(
    val name: String,
    val path: String,
    val size: Long,
)

class LocalLlamaClient(
    private val context: Context,
    limitedDevice: Boolean = false,
) {
    private val engine by lazy { AiChat.getInferenceEngine(context) }
    private val modelsDirectory = File(context.filesDir, "models").apply { mkdirs() }
    private var loadedPath: String? = null
    private var loadedConversationKey: String? = null
    private val defaultPredictTokens =
        DeviceProfile.defaultLocalPredictTokens(limitedDevice)

    val state get() = engine.state

    suspend fun importModel(
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): ImportedModel = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        require(GgufMetadataReader.create().ensureSourceFileFormat(context, uri)) {
            "Выбранный файл не является GGUF"
        }
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (total > 0) {
            val available = StatFs(modelsDirectory.absolutePath).availableBytes
            require(available > total + 256L * 1024 * 1024) {
                "Недостаточно свободного места для модели"
            }
        }
        val target = uniqueTarget(name)
        val pending = File(target.parentFile, target.name + ".part")
        resolver.openInputStream(uri)?.use { input ->
            pending.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, total)
                }
            }
        } ?: throw IOException("Не удалось открыть файл модели")
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
        ImportedModel(target.name, target.absolutePath, target.length())
    }

    suspend fun load(path: String, systemPrompt: String, conversationKey: String) {
        val canonical = File(path).canonicalPath
        if (
            loadedPath == canonical &&
            loadedConversationKey == conversationKey &&
            engine.state.value.isModelLoaded
        ) return
        if (engine.state.value.isModelLoaded) {
            engine.cleanUp()
        }
        awaitInitialized()
        engine.loadModel(canonical)
        engine.setSystemPrompt(systemPrompt)
        loadedPath = canonical
        loadedConversationKey = conversationKey
    }

    fun generate(message: String, maxTokens: Int = defaultPredictTokens): Flow<String> =
        engine.sendUserPrompt(message, maxTokens.coerceIn(32, 2_048))

    fun unload() {
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        loadedPath = null
        loadedConversationKey = null
    }

    private suspend fun awaitInitialized() {
        when (val current = engine.state.value) {
            is InferenceEngine.State.Initialized -> return
            is InferenceEngine.State.Error -> throw current.exception
            else -> {
                when (val ready = engine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }) {
                    is InferenceEngine.State.Error -> throw ready.exception
                    else -> Unit
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) ?: "model.gguf"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
    }

    private fun uniqueTarget(name: String): File {
        val requested = File(modelsDirectory, name)
        if (!requested.exists()) return requested
        val stem = requested.nameWithoutExtension
        val extension = requested.extension
        var index = 2
        while (true) {
            val candidate = File(modelsDirectory, "$stem-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}
