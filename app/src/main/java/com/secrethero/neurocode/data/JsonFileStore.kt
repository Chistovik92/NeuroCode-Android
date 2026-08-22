package com.secrethero.neurocode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
) {
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext defaultValue()
            runCatching {
                json.decodeFromString(serializer, file.readText())
            }.getOrElse { defaultValue() }
        }
    }

    suspend fun write(value: T) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val pending = File(file.parentFile, file.name + ".tmp")
            pending.writeText(json.encodeToString(serializer, value))
            if (!pending.renameTo(file)) {
                pending.copyTo(file, overwrite = true)
                check(pending.delete()) {
                    "Не удалось завершить сохранение ${file.name}"
                }
            }
        }
    }
}
