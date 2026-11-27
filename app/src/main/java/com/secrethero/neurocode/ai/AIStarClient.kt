package com.secrethero.neurocode.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AI Star (ai.starimg.ru) — OpenAI-совместимый сервис, доступ выдаётся кодом активации.
 * Порт `src/aistar.ts` референс-проекта winOpenCodeMod (0.9.5.5): публичная страница
 * содержит `window.__STARIMG_BOOTSTRAP__` — каталог моделей по категориям и CSRF-токен;
 * активация кода — POST формы на ту же страницу, в ответе api_key (`sk-star-…`) и
 * base_url региона.
 */
object AIStarClient {

    const val PROVIDER_ID = "aistar"
    const val BASE_URL = "https://ai.starimg.ru"

    /** OpenAI-совместимая база по умолчанию (до активации кода и региона). */
    const val API_BASE_URL = "$BASE_URL/v1"

    private const val BOOTSTRAP_MARKER = "window.__STARIMG_BOOTSTRAP__="
    private const val HTTP_SUCCESS_MIN = 200
    private const val HTTP_SUCCESS_MAX = 299
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class Bootstrap(
        val csrf: String? = null,
        val models: List<Category> = emptyList(),
        val state: State? = null,
    ) {
        @Serializable
        internal data class Category(
            val id: String? = null,
            val models: List<Model> = emptyList(),
        )

        @Serializable
        internal data class Model(
            val id: String,
            @SerialName("display_name") val displayName: String? = null,
        )

        @Serializable
        internal data class State(
            val err: String? = null,
            val key: Key? = null,
        )

        @Serializable
        internal data class Key(
            @SerialName("access_code") val accessCode: String? = null,
            @SerialName("api_key") val apiKey: String? = null,
            @SerialName("base_url") val baseUrl: String? = null,
        )
    }

    /** Ключ и base_url региона после успешной активации кода доступа. */
    data class Activation(val apiKey: String, val baseUrl: String)

    /**
     * Каталог моделей со страницы AI Star — публичный, ключ не нужен.
     * Возвращает id моделей (имена для OpenAI-совместимого /v1/chat/completions).
     */
    suspend fun fetchBootstrapModels(): List<String> = withContext(Dispatchers.IO) {
        val html = fetch(BASE_URL).use { response -> response.body?.string().orEmpty() }
        parseBootstrap(html).models
            .flatMap { it.models }
            .map { it.id }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Активация кода доступа: GET страницы (CSRF + cookie), POST формы доступа,
     * разбор bootstrap ответа с ключом. Ошибка активации — текст сайта (state.err).
     */
    suspend fun activateAccessCode(accessCode: String): Activation = withContext(Dispatchers.IO) {
        val (cookie, firstHtml) = fetch(BASE_URL).use { response ->
            response.header("Set-Cookie")?.substringBefore(";") to response.body?.string().orEmpty()
        }
        val csrf = parseBootstrap(firstHtml).csrf
            ?: error("Не удалось получить CSRF-токен со страницы AI Star")
        val form = FormBody.Builder()
            .add("csrf_token", csrf)
            .add("lang", "en")
            .add("access_code", accessCode)
            .build()
        val request = Request.Builder()
            .url(BASE_URL)
            .post(form)
            .apply { cookie?.let { header("Cookie", it) } }
            .build()
        http.newCall(request).execute().use { response ->
            parseActivation(response.body?.string().orEmpty(), response.code)
        }
    }

    private fun fetch(url: String) =
        http.newCall(Request.Builder().url(url).get().build()).execute()

    internal fun parseBootstrap(html: String): Bootstrap {
        val start = html.indexOf(BOOTSTRAP_MARKER)
        require(start >= 0) { "На странице AI Star не найден каталог моделей" }
        val from = start + BOOTSTRAP_MARKER.length
        val end = html.indexOf(";</script>", from)
        require(end > from) { "На странице AI Star обрезан каталог моделей" }
        return json.decodeFromString(Bootstrap.serializer(), html.substring(from, end))
    }

    internal fun parseActivation(html: String, httpCode: Int): Activation {
        val bootstrap = parseBootstrap(html)
        val key = bootstrap.state?.key
        val err = bootstrap.state?.err
        if (httpCode !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX || !err.isNullOrBlank() || key?.apiKey.isNullOrBlank()) {
            error(err ?: "Активация AI Star не удалась (HTTP $httpCode)")
        }
        val base = key.baseUrl?.trim()?.trimEnd('/').takeUnless { it.isNullOrEmpty() } ?: BASE_URL
        return Activation(
            apiKey = key.apiKey.orEmpty(),
            baseUrl = if (base.endsWith("/v1")) base else "$base/v1",
        )
    }
}
