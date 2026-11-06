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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

data class ImportedModel(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long = 0L,
)

@Suppress("TooManyFunctions")
class LocalLlamaClient(
    private val context: Context,
    limitedDevice: Boolean = false,
) {
    private val engine by lazy { AiChat.getInferenceEngine(context) }
    private val modelsDirectory = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Все операции с движком и его полями — под одним мьютексом: раньше `deleteModel`
     * (IO) и `load`/`unload` (Main) гонялись на `loadedPath`, а удаление загруженной
     * модели не выгружало её из памяти.
     */
    private val engineMutex = Mutex()
    private var loadedPath: String? = null
    private var loadedConversationKey: String? = null
    private val defaultPredictTokens =
        DeviceProfile.defaultLocalPredictTokens(limitedDevice)

    val state get() = engine.state

    /** Каталог, куда складываются GGUF-модели (`files/models`) — общий для импорта и каталога 0.9.1. */
    fun modelsDir(): File = modelsDirectory

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
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть файл модели")
        input.use { stream ->
            importStream(stream, name, total, onProgress)
        }
    }

    /**
     * Импорт из уже открытого потока (LocalDoc AI): имя и размер известны из списка провайдера,
     * magic GGUF проверяется по первым байтам скопированного файла после копирования.
     */
    suspend fun importStream(
        input: InputStream,
        name: String,
        declaredSize: Long,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): ImportedModel = withContext(Dispatchers.IO) {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        requireSpaceFor(declaredSize)
        val target = uniqueTarget(safeName)
        val pending = File(target.parentFile, target.name + ".part")
        var renamed = false
        try {
            copyToPending(input, pending, declaredSize, onProgress)
            verifyCopiedModel(pending, declaredSize)
            publishPending(pending, target, declaredSize)
            renamed = true
        } finally {
            if (!renamed) pending.delete()
        }
        ImportedModel(target.name, target.absolutePath, target.length(), target.lastModified())
    }

    /** Запас места до старта: при неизвестном размере — минимум, иначе размер + запас. */
    private fun requireSpaceFor(total: Long) {
        if (total <= 0L) {
            require(freeBytes() >= UNKNOWN_SIZE_MIN_FREE_BYTES) {
                "Недостаточно свободного места для модели"
            }
        } else {
            require(freeBytes() > total + FREE_SPACE_HEADROOM_BYTES) {
                "Недостаточно свободного места для модели"
            }
        }
    }

    private suspend fun copyToPending(
        input: InputStream,
        pending: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        pending.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                // Импорт отменяется вместе с корутиной (уход с экрана), а не доедает гигабайты в фоне.
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                if (total > 0) {
                    require(copied <= total) { "Источник больше заявленного размера" }
                } else if (copied % SPACE_CHECK_EVERY_BYTES < DEFAULT_BUFFER_SIZE &&
                    freeBytes() < FREE_SPACE_HEADROOM_BYTES
                ) {
                    error("Место закончилось во время копирования модели")
                }
                onProgress(copied, total)
            }
        }
    }

    /** Magic GGUF: файл мог быть чем угодно, пока источник не начал проверять сам. */
    private fun verifyCopiedModel(pending: File, total: Long) {
        val magic = ByteArray(GGUF_MAGIC_LENGTH)
        pending.inputStream().use { it.read(magic) }
        require(magic.decodeToString() == GGUF_MAGIC) { "Выбранный файл не является GGUF" }
        if (total > 0 && pending.length() != total) {
            error("Скопировано ${pending.length()} байт вместо $total: источник изменился")
        }
    }

    /** Атомарная публикация: переименование, при неудаче — копирование с проверкой размера. */
    private fun publishPending(pending: File, target: File, total: Long) {
        if (pending.renameTo(target)) return
        try {
            pending.copyTo(target, overwrite = true)
        } catch (error: IOException) {
            // Битый недокопированный GGUF не должен появляться в списке моделей.
            target.delete()
            throw error
        }
        if (total > 0 && target.length() != total) {
            target.delete()
            error("Не удалось скопировать модель целиком")
        }
        pending.delete()
    }

    /** GGUF-модели, импортированные в `files/models`, — для окна выбора модели и «Моделей на устройстве». */
    suspend fun listModels(): List<ImportedModel> = withContext(Dispatchers.IO) {
        cleanupOrphanedParts()
        modelsDirectory.listFiles { file -> file.isFile && file.extension.equals("gguf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { ImportedModel(it.name, it.absolutePath, it.length(), it.lastModified()) }
    }

    /**
     * Удаляет файл модели с диска. Загруженная в движок модель сначала выгружается
     * (иначе место и ОЗУ оставались занятыми до следующей загрузки).
     * Возвращает true, если файл существовал и был удалён; false — файла уже нет.
     * Кидает IOException, если удалить не удалось: молчаливый сбой оставлял бы
     * «полусостояние» (ссылки сброшены, файл на месте).
     */
    suspend fun deleteModel(path: String): Boolean = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.isFile) return@withContext false
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (canonical == loadedPath) {
                if (engine.state.value.isModelLoaded) engine.cleanUp()
                loadedPath = null
                loadedConversationKey = null
            }
            if (!file.delete()) {
                throw IOException("Не удалось удалить файл модели: ${file.name}")
            }
            true
        }
    }

    /** Свободное место в каталоге моделей (байт); null, если статистика недоступна. */
    fun modelsFreeBytes(): Long? = runCatching {
        StatFs(modelsDirectory.absolutePath).availableBytes
    }.getOrNull()

    /** true, если модель уже загружена именно для этого диалога и контекст в памяти движка актуален. */
    suspend fun isLoadedFor(path: String, conversationKey: String): Boolean =
        engineMutex.withLock {
            loadedPath == canonicalOrNull(path) &&
                loadedConversationKey == conversationKey &&
                engine.state.value.isModelLoaded
        }

    suspend fun load(path: String, systemPrompt: String, conversationKey: String) =
        engineMutex.withLock {
            val canonical = canonicalOrNull(path) ?: error("Недоступен путь модели: $path")
            if (
                loadedPath == canonical &&
                loadedConversationKey == conversationKey &&
                engine.state.value.isModelLoaded
            ) return@withLock
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

    suspend fun unload() = engineMutex.withLock {
        if (engine.state.value.isModelLoaded) engine.cleanUp()
        loadedPath = null
        loadedConversationKey = null
    }

    /** Путь, применяемый для всех моделей в `files/models`; null, если путь недоступен. */
    private fun modelFile(path: String): File? =
        runCatching { File(path).canonicalFile }.getOrNull()?.takeIf { it.isFile }

    private fun canonicalOrNull(path: String): String? = modelFile(path)?.absolutePath

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

    private fun freeBytes(): Long =
        runCatching { StatFs(modelsDirectory.absolutePath).availableBytes }.getOrDefault(0L)

    /** Осиротевшие `.part` после сбоя/убийства процесса: невидимы в списке, но занимают гигабайты. */
    private fun cleanupOrphanedParts() {
        modelsDirectory.listFiles { file -> file.isFile && file.name.endsWith(".part") }
            .orEmpty()
            .filterNot { it.name.startsWith(ACTIVE_PART_PREFIX) }
            .forEach { runCatching { it.delete() } }
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

    private fun uniqueTarget(name: String): File = File(modelsDirectory, uniqueModelFileName(name) { candidate ->
        File(modelsDirectory, candidate).exists()
    })

    private companion object {
        /** Запас под контекст и системные нужды при импорте модели. */
        const val FREE_SPACE_HEADROOM_BYTES = 256L * 1024 * 1024

        /** Минимум для старта, когда источник размер не отдаёт: копирование прервётся по переполнению. */
        const val UNKNOWN_SIZE_MIN_FREE_BYTES = 512L * 1024 * 1024
        const val SPACE_CHECK_EVERY_BYTES = 64L * 1024 * 1024
        const val GGUF_MAGIC = "GGUF"
        const val GGUF_MAGIC_LENGTH = 4

        /** Префикс активной (идущей прямо сейчас) частичной загрузки — её чистить нельзя. */
        const val ACTIVE_PART_PREFIX = "\u0000active-"
    }
}
