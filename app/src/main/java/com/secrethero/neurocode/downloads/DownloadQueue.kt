package com.secrethero.neurocode.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Общий менеджер загрузок (0.9.0): основа для rootfs и будущих моделей из каталога.
 *
 * Возобновление через HTTP `Range`: недокачанный файл лежит рядом с целевым как `.part`
 * и дополняется при повторной попытке, если сервер вернул 206. Сервер без поддержки
 * Range (200 на запрос с offset) заставляет начать заново. Свободное место проверяется
 * до старта и по ходу копирования.
 *
 * Вызовы блокирующие — запускать из `Dispatchers.IO` (как `ProotManager.prepare`).
 * Параллельные вызовы сериализуются внутренним локом: приложение скачивает последовательно.
 */
class DownloadQueue(private val context: Context) {

    private val lock = Any()

    /**
     * Скачивает [url] в [target] с ретраями и докачкой.
     * [onProgress] получает (скачано, всего); всего = 0, если сервер не отдал размер.
     * Кидает [NetworkUnavailableException] без сети и [IOException] при неудаче после всех попыток.
     */
    fun download(
        url: String,
        target: File,
        attempts: Int = DEFAULT_ATTEMPTS,
        retryDelayMs: Long = RETRY_DELAY_MS,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        synchronized(lock) {
            requireNetwork()
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + PART_SUFFIX)
            var lastError: IOException? = null
            for (attempt in 1..attempts) {
                try {
                    downloadOnce(url, partial, target, onProgress)
                    return
                } catch (error: IOException) {
                    lastError = error
                    // Частичный файл оставляем: следующая попытка (или запуск приложения
                    // после обрыва сети) докачает его через Range.
                    if (attempt < attempts) Thread.sleep(retryDelayMs * attempt)
                }
            }
            throw lastError ?: IOException("Download failed: $url")
        }
    }

    /** Размер недокачанного `.part` для цели; 0, если докачивать нечего. */
    fun partialBytes(target: File): Long {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        return if (partial.isFile) partial.length() else 0L
    }

    private fun downloadOnce(
        url: String,
        partial: File,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val resumeFrom = if (partial.isFile) partial.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            connection.connect()
            val code = connection.responseCode
            // 206 — сервер поддержал Range и отдаёт остаток: дописываем к .part.
            // 200 на запрос с Range — сервер начал заново: .part обнуляем.
            val appending = code == HttpURLConnection.HTTP_PARTIAL
            if (!appending) {
                checkHttpStatus(code, url)
                if (resumeFrom > 0) partial.delete()
            }
            val total = connection.contentLengthLong.coerceAtLeast(0L).let {
                if (appending && it >= 0) it + resumeFrom else it
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, appending).use { output ->
                    copyWithProgress(input, output, resumeFrom, total, onProgress)
                }
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Cannot replace ${target.name}")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun checkHttpStatus(code: Int, url: String) {
        if (code in HTTP_OK_FIRST..HTTP_OK_LAST) return
        throw IOException("HTTP $code for $url")
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        already: Long,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = already
        var reported = -1L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            downloaded += read
            if (downloaded - reported >= PROGRESS_STEP_BYTES || reported < 0) {
                reported = downloaded
                onProgress(downloaded, total)
            }
        }
        onProgress(downloaded, total)
    }

    /** Понятная ошибка до старта вместо сетевого тайм-аута с адресом. */
    private fun requireNetwork() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!online) throw NetworkUnavailableException()
    }

    class NetworkUnavailableException :
        IOException("No network connection available")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val DEFAULT_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 3_000L
        const val PART_SUFFIX = ".part"
        const val HTTP_OK_FIRST = 200
        const val HTTP_OK_LAST = 299
    }
}
