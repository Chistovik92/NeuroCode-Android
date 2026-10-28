package com.secrethero.neurocode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Файл состояния в JSON. Повреждённый файл **не затирается**: он переименовывается в
 * `<имя>.corrupt-<время>` (хранится не больше [MAX_QUARANTINED] копий), о чём сообщает [onCorrupt],
 * а чтение возвращает значение по умолчанию. Раньше следующая запись молча уничтожала данные.
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
    private val onCorrupt: (File) -> Unit = {},
) {
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(): T = mutex.withLock {
        withContext(Dispatchers.IO) { readLocked() }
    }

    private fun readLocked(): T {
        val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        if (text.isNullOrBlank()) return defaultValue()
        return runCatching { json.decodeFromString(serializer, text) }.getOrElse {
            quarantine()?.let(onCorrupt)
            defaultValue()
        }
    }

    /** Переносит повреждённый файл в сторону и возвращает путь копии; null, если перенести не удалось. */
    private fun quarantine(): File? {
        val target = File(file.parentFile, "${file.name}$CORRUPT_MARK${System.currentTimeMillis()}")
        val moved = file.renameTo(target) || runCatching { file.copyTo(target, overwrite = true) }.isSuccess
        if (!moved) return null
        pruneQuarantine()
        return target
    }

    private fun pruneQuarantine() {
        file.parentFile?.listFiles { candidate -> candidate.name.startsWith(file.name + CORRUPT_MARK) }
            .orEmpty()
            .sortedByDescending { it.name }
            .drop(MAX_QUARANTINED)
            .forEach { it.delete() }
    }

    suspend fun write(value: T) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val pending = File(file.parentFile, file.name + ".tmp")
            // fsync до переименования: после сбоя питания остаётся либо старый файл, либо новый целиком.
            FileOutputStream(pending).use { output ->
                output.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!pending.renameTo(file)) {
                pending.copyTo(file, overwrite = true)
                check(pending.delete()) {
                    "Не удалось завершить сохранение ${file.name}"
                }
            }
        }
    }

    companion object {
        const val CORRUPT_MARK = ".corrupt-"
        const val MAX_QUARANTINED = 5
    }
}
