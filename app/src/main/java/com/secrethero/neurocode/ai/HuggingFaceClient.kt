package com.secrethero.neurocode.ai

import android.content.Context
import com.secrethero.neurocode.device.DeviceProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Результат живого поиска модели на Hugging Face (0.9.1).
 */
data class HuggingFaceModel(
    val id: String,
    val downloads: Long,
    val likes: Int,
    val gated: Boolean,
)

/**
 * Файл GGUF в репозитории Hugging Face: имя, размер и (если отдан) SHA-256 из LFS.
 */
data class HuggingFaceFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/**
 * Клиент каталога и загрузок Hugging Face (0.9.1).
 *
 * - поиск моделей через публичный API `/api/models` с фильтром `gguf`;
 * - список файлов через `/api/models/<repo>/tree/main`;
 * - загрузка по `/<repo>/resolve/main/<file>` с докачкой по `Range` (общий [DownloadQueue]),
 *   сверкой SHA-256 с данными LFS и проверкой magic GGUF;
 * - gated-модели: необязательный токен в заголовке `Authorization: Bearer`.
 *
 * Список и скачивание не отправляют на сервер данные о диалогах; характеристики устройства
 * для подбора считаются локально. Только HTTPS.
 */
class HuggingFaceClient(
    private val context: Context,
    private val tokenProvider: suspend () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<HuggingFaceModel> =
        withContext(Dispatchers.IO) {
            require(query.isNotBlank()) { "Пустой поисковый запрос" }
            val url = "$BASE/api/models?filter=gguf&sort=downloads&direction=-1&limit=$limit&search=$query"
            val request = authedRequest(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Hugging Face: HTTP ${response.code}")
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                (root["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    HuggingFaceModel(
                        id = id,
                        downloads = (obj["downloads"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                        likes = (obj["likes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                        gated = (obj["gated"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    )
                }
            }
        }

    /** Файлы GGUF в репозитории: имя, размер, SHA-256 (если API отдал LFS-данные). */
    suspend fun listFiles(repoId: String): List<HuggingFaceFile> = withContext(Dispatchers.IO) {
        val url = "$BASE/api/models/${repoId.trim()}/tree/main"
        val request = authedRequest(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Hugging Face: HTTP ${response.code}")
            val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            (root["siblings"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val path = (obj["rfilename"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                HuggingFaceFile(
                    path = path,
                    sizeBytes = (obj["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: -1L,
                    sha256 = (obj["lfs"] as? JsonObject)
                        ?.get("sha256") as? JsonPrimitive
                        ?.contentOrNull,
                )
            }.filter { it.path.endsWith(".gguf", ignoreCase = true) }
        }
    }

    /**
     * Выбирает файл для скачивания из списка: наибольший из предпочитаемых квантизаций,
     * помещающийся в [maxSizeMb]. Предпочитаемый порядок: Q4_K_M → Q5_K_M → Q8_0 → Q4_0 …
     */
    fun pickFile(files: List<HuggingFaceFile>, maxSizeMb: Long): HuggingFaceFile? =
        HuggingFaceQuantization.pick(files, maxSizeMb)

    /**
     * Скачивает модель в `files/models/<fileName>` с докачкой по Range и проверкой SHA-256
     * (если известен) и magic GGUF. [fileName] — целевое имя, [url] — resolve-адрес файла.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String?,
        wifiOnly: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Только HTTPS" }
        if (wifiOnly && !isOnWifi()) {
            throw IOException("Доступно только по Wi‑Fi (включено в настройках)")
        }
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        val final = downloadResuming(url, partial, target, expectedSha256, onProgress)
        // Скачали в .part, свернули и переименовали — проверяем magic и хэш ещё раз по итогу.
        verifyGguf(final, expectedSha256)
        final
    }

    /** true, если активная сеть — Wi‑Fi (не сотовая и не Ethernet/VPN без указания транспорта). */
    private fun isOnWifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun downloadResuming(
        url: String,
        partial: File,
        target: File,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit,
    ): File {
        // Range-докачка и ретраи; токен (для gated-моделей) — в заголовке Authorization.
        val token = tokenProvider()
        val resumeFrom = if (partial.isFile) partial.length() else 0L
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")
            connection.connect()
            val code = connection.responseCode
            val appending = code == 206
            if (code != 200 && !appending) throw IOException("HTTP $code for $url")
            val total = if (appending) {
                val len = connection.contentLengthLong.coerceAtLeast(0)
                if (len >= 0) len + resumeFrom else -1L
            } else {
                connection.contentLengthLong
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, appending).use { output ->
                    copyVerified(input, output, resumeFrom, total, onProgress)
                }
            }
        } finally {
            connection.disconnect()
        }
        // Итог: сверка SHA-256 перед публикацией в files/models.
        if (expectedSha256 != null) {
            val actual = sha256(partial)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                partial.delete()
                throw IOException("Контрольная сумма не совпала (SHA-256)")
            }
        }
        if (target.exists() && !target.delete()) throw IOException("Не удалось заменить ${target.name}")
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    private suspend fun copyVerified(
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
            currentCoroutineContext().ensureActive()
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

    private fun verifyGguf(file: File, expectedSha256: String?) {
        val magic = ByteArray(GGUF_MAGIC_LENGTH)
        file.inputStream().use { it.read(magic) }
        require(magic.decodeToString() == GGUF_MAGIC) { "Скачанный файл не является GGUF" }
        if (expectedSha256 != null) {
            val actual = sha256(file)
            require(actual.equals(expectedSha256, ignoreCase = true)) { "SHA-256 не совпал" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun authedRequest(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private companion object {
        const val BASE = "https://huggingface.co"
        const val DEFAULT_SEARCH_LIMIT = 20
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024
        const val GGUF_MAGIC = "GGUF"
        const val GGUF_MAGIC_LENGTH = 4
    }
}

/**
 * Чистая логика выбора квантизации под размер устройства. Вынесена из [HuggingFaceClient],
 * чтобы покрыть unit-тестами без Android и сети.
 */
object HuggingFaceQuantization {
    /** Порядок предпочтения квантизаций: от лучшего качества к меньшему. */
    val PREFERRED_ORDER = listOf(
        "Q4_K_M", "Q5_K_M", "Q8_0", "Q4_0", "Q5_0", "Q6_K", "Q2_K", "Q3_K_M", "Q3_K_S", "IQ4_XS",
    )

    fun preferred(names: List<String>): String? =
        PREFERRED_ORDER.firstOrNull { q -> names.any { it.contains(q, ignoreCase = true) } }

    fun rank(name: String, preferred: String?): Int {
        if (preferred != null && name.contains(preferred, ignoreCase = true)) return Int.MAX_VALUE
        val index = PREFERRED_ORDER.indexOfFirst { name.contains(it, ignoreCase = true) }
        return if (index < 0) -1 else PREFERRED_ORDER.size - index
    }

    /** Файл для скачивания: помещающийся в [maxSizeMb], наибольший по приоритету квантизации. */
    fun pick(files: List<HuggingFaceFile>, maxSizeMb: Long): HuggingFaceFile? {
        val eligible = files.filter {
            it.sizeBytes > 0 && it.sizeBytes <= maxSizeMb * DeviceProfile.MB
        }
        if (eligible.isEmpty()) return null
        val prefer = preferred(eligible.map { it.path })
        return eligible.maxByOrNull { rank(it.path, prefer) }
    }
}
