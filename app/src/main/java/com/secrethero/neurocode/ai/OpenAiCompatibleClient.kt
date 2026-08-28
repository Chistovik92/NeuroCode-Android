package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AssistantTurn
import com.secrethero.neurocode.model.ModelLimits
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ApiMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** Картинки как data-URL: уходят частями `image_url` в multimodal-формате OpenAI. */
    val images: List<String> = emptyList(),
)

class OpenAiCompatibleClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val _limits = MutableStateFlow<ModelLimits?>(null)

    /** Лимиты и расход по последнему ответу провайдера. */
    val limits: StateFlow<ModelLimits?> = _limits.asStateFlow()

    /** Окна контекста из каталога моделей, если провайдер их отдаёт. */
    private val contextWindows = mutableMapOf<String, Int>()
    private val maxOutputTokens = mutableMapOf<String, Int>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val streamingHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    private class ApiException(val code: Int, message: String) : IOException(message)

    private fun httpHint(code: Int): String = when {
        code == 401 -> " Проверьте API-ключ и доступ к выбранной модели."
        code == 402 -> " Недостаточно средств на аккаунте провайдера."
        code == 403 -> " Ключу запрещён доступ к этой модели."
        code == 404 -> " Модель или адрес не найдены — проверьте Base URL и имя модели."
        code == 429 -> " Слишком много запросов — попробуйте позже."
        code in SERVER_ERROR_CODES -> " Ошибка на стороне провайдера."
        else -> ""
    }

    private fun chatEndpoints(provider: ProviderConfig): List<String> {
        val base = provider.baseUrl.trim().trimEnd('/')
        return buildList {
            add("$base/chat/completions")
            if (!base.endsWith("/v1") && !base.contains("/v1beta")) {
                add("$base/v1/chat/completions")
            }
        }
    }

    private fun isRetryableEndpointFailure(error: Throwable): Boolean =
        error is SerializationException ||
            (error is ApiException && (error.code == 404 || error.code == 405))

    @Suppress("LongParameterList")
    suspend fun complete(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray = JsonArray(emptyList()),
        onDelta: ((String) -> Unit)? = null,
        onReasoning: ((String) -> Unit)? = null,
    ): AssistantTurn {
        validate(provider, apiKey)
        if (onDelta == null) {
            return tryChatEndpoints(provider) { endpoint ->
                executeBlocking(endpoint, apiKey, messages, tools, provider)
            }
        }
        var received = false
        return tryChatEndpoints(provider, shouldRethrow = { received }) { endpoint ->
            executeStreaming(
                endpoint = endpoint,
                apiKey = apiKey,
                messages = messages,
                tools = tools,
                onChunk = { chunk ->
                    received = true
                    onDelta(chunk)
                },
                onReasoningChunk = { chunk ->
                    received = true
                    onReasoning?.invoke(chunk)
                },
                providerForHeaders = provider,
            )
        }
    }

    private suspend fun tryChatEndpoints(
        provider: ProviderConfig,
        shouldRethrow: () -> Boolean = { false },
        block: suspend (String) -> AssistantTurn,
    ): AssistantTurn {
        var lastError: Exception? = null
        for (endpoint in chatEndpoints(provider)) {
            try {
                return block(endpoint)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (shouldRethrow() || !isRetryableEndpointFailure(error)) throw error
                lastError = error
            } catch (error: SerializationException) {
                if (shouldRethrow()) throw error
                lastError = error
            }
        }
        throw IOException(
            "Эндпоинт чата не найден. Проверьте Base URL — он обычно оканчивается на /v1. " +
                "Причина последней попытки: ${lastError?.message ?: "неизвестна"}",
        )
    }

    suspend fun models(provider: ProviderConfig, apiKey: String): List<String> {
        require(provider.baseUrl.startsWith("https://")) {
            "Адрес провайдера должен использовать HTTPS"
        }
        val base = provider.baseUrl.trim().trimEnd('/')
        val candidates = buildList {
            add("$base/models")
            if (!base.endsWith("/v1") && !base.contains("/v1beta")) add("$base/v1/models")
        }
        var lastError: Exception? = null
        for (endpoint in candidates) {
            try {
                return fetchModels(endpoint, provider, apiKey)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = error
            } catch (error: SerializationException) {
                lastError = error
            }
        }
        throw IOException(
            "Список моделей не получен. Проверьте Base URL — он обычно оканчивается на /v1. " +
                "Причина последней попытки: ${lastError?.message ?: "неизвестна"}",
        )
    }

    private suspend fun fetchModels(
        endpoint: String,
        provider: ProviderConfig,
        apiKey: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .apply { provider.extraHeaders.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiError(response.code, body, response.message)
            }
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw IOException("сервер вернул не JSON (проверьте адрес)")
            }
            val data = (root["data"] as? JsonArray) ?: JsonArray(emptyList())
            data.forEach { element -> rememberModelLimits(element) }
            data.mapNotNull { element ->
                runCatching {
                    element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }.filter { !it.isNullOrBlank() }
                .map { it as String }
                .distinct()
                .sorted()
        }
    }

    private fun validate(provider: ProviderConfig, apiKey: String) {
        require(provider.baseUrl.startsWith("https://")) {
            "Адрес провайдера должен использовать HTTPS"
        }
        require(provider.model.isNotBlank()) { "Укажите модель в настройках провайдера" }
        require(apiKey.isNotBlank()) { "API-ключ не задан" }
    }

    private suspend fun executeBlocking(
        endpoint: String,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray,
        providerForHeaders: ProviderConfig,
    ): AssistantTurn = withContext(Dispatchers.IO) {
        validate(providerForHeaders, apiKey)
        val payload = buildPayload(providerForHeaders, messages, tools, stream = false)
        val request = buildRequest(endpoint, providerForHeaders, apiKey, payload)
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiError(response.code, body, response.message)
            }
            captureLimits(providerForHeaders, response, body)
            parseTurn(body)
        }
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun executeStreaming(
        endpoint: String,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray,
        onChunk: (String) -> Unit,
        onReasoningChunk: (String) -> Unit,
        providerForHeaders: ProviderConfig,
    ): AssistantTurn = withContext(Dispatchers.IO) {
        validate(providerForHeaders, apiKey)
        val payload = buildPayload(providerForHeaders, messages, tools, stream = true)
        val request = buildRequest(endpoint, providerForHeaders, apiKey, payload)
        streamingHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw apiError(response.code, body, response.message)
            }
            captureLimits(providerForHeaders, response, body = null)
            val source = response.body?.source()
                ?: throw IOException("Провайдер вернул пустой поток")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val splitter = ReasoningSplitter()
            val builders = mutableMapOf<Int, StreamToolCall>()
            fun emit(chunk: ReasoningSplitter.Chunk) {
                if (chunk.content.isNotEmpty()) {
                    content.append(chunk.content)
                    onChunk(chunk.content)
                }
                if (chunk.reasoning.isNotEmpty()) {
                    reasoning.append(chunk.reasoning)
                    onReasoningChunk(chunk.reasoning)
                }
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                val data = line.takeIf { it.startsWith("data:") }
                    ?.removePrefix("data:")?.trim() ?: continue
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val element = runCatching { json.parseToJsonElement(data).jsonObject }
                    .getOrNull() ?: continue
                element["usage"]?.let { usage ->
                    updateUsage(providerForHeaders.model, usage as? JsonObject)
                }
                val delta = element["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta") as? JsonObject ?: continue
                reasoningField(delta)?.takeIf { it.isNotEmpty() }?.let { chunk ->
                    reasoning.append(chunk)
                    onReasoningChunk(chunk)
                }
                (delta["content"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { chunk -> emit(splitter.push(chunk)) }
                (delta["tool_calls"] as? JsonArray)?.forEach { raw ->
                    val item = raw.jsonObject
                    val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: builders.size
                    val accumulator = builders.getOrPut(index) { StreamToolCall() }
                    (item["id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let { accumulator.id = it }
                    val function = item["function"] as? JsonObject ?: return@forEach
                    (function["name"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let { accumulator.name = it }
                    (function["arguments"] as? JsonPrimitive)?.contentOrNull
                        ?.let { accumulator.arguments.append(it) }
                }
            }
            emit(splitter.flush())
            AssistantTurn(
                content = content.toString(),
                toolCalls = builders.toSortedMap().values.mapIndexedNotNull { index, accumulator ->
                    accumulator.name.takeIf { it.isNotBlank() }?.let { name ->
                        ToolCall(
                            id = accumulator.id.ifBlank { "call-stream-$index" },
                            name = name,
                            arguments = accumulator.arguments.toString(),
                        )
                    }
                },
                reasoning = reasoning.toString(),
            )
        }
    }

    /** Модели отдают размышления по-разному: `reasoning_content` (DeepSeek) или `reasoning`. */
    private fun reasoningField(node: JsonObject): String? =
        (node["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            ?: (node["reasoning"] as? JsonPrimitive)?.contentOrNull

    private fun buildPayload(
        provider: ProviderConfig,
        messages: List<ApiMessage>,
        tools: JsonArray,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(provider.model))
        put("messages", buildJsonArray {
            messages.forEach { add(it.toJson()) }
        })
        put("temperature", JsonPrimitive(0.2))
        if (stream) {
            put("stream", JsonPrimitive(true))
        }
        if (tools.isNotEmpty()) {
            put("tools", tools)
            put("tool_choice", JsonPrimitive("auto"))
        }
    }

    private fun buildRequest(
        endpoint: String,
        provider: ProviderConfig,
        apiKey: String,
        payload: JsonObject,
    ): Request {
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .apply { provider.extraHeaders.forEach { (name, value) -> header(name, value) } }
            .post(payload.toString().toRequestBody(mediaType))
            .build()
    }

    private fun apiError(code: Int, body: String, fallback: String): IOException {
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        val short = when {
            parsed != null -> parsed
            body.contains("<!DOCTYPE", ignoreCase = true) ||
                body.contains("<html", ignoreCase = true) ->
                "сервер вернул HTML-страницу вместо ответа API"
            else -> body.take(200).ifBlank { fallback }
        }
        val hint = httpHint(code)
        return ApiException(code, "Ошибка API $code: ${short.take(300)}.$hint")
    }

    /** Заголовки `x-ratelimit-*` и блок `usage` — источник данных для карточки лимитов. */
    private fun captureLimits(provider: ProviderConfig, response: Response, body: String?) {
        fun header(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            response.header(name)?.takeIf { it.isNotBlank() }
        }
        val usage = body?.let {
            runCatching { json.parseToJsonElement(it).jsonObject["usage"] as? JsonObject }
                .getOrNull()
        }
        val previous = _limits.value?.takeIf { it.model == provider.model }
        _limits.value = ModelLimits(
            model = provider.model,
            contextWindow = contextWindows[provider.model] ?: previous?.contextWindow,
            maxOutputTokens = maxOutputTokens[provider.model] ?: previous?.maxOutputTokens,
            promptTokens = usage.int("prompt_tokens") ?: previous?.promptTokens,
            completionTokens = usage.int("completion_tokens") ?: previous?.completionTokens,
            totalTokens = usage.int("total_tokens") ?: previous?.totalTokens,
            requestsRemaining = header("x-ratelimit-remaining-requests")
                ?: previous?.requestsRemaining,
            requestsLimit = header("x-ratelimit-limit-requests") ?: previous?.requestsLimit,
            tokensRemaining = header("x-ratelimit-remaining-tokens") ?: previous?.tokensRemaining,
            tokensLimit = header("x-ratelimit-limit-tokens") ?: previous?.tokensLimit,
            resetHint = header("x-ratelimit-reset-requests", "x-ratelimit-reset-tokens")
                ?: previous?.resetHint,
        )
    }

    /** Обновляет расход токенов из `usage`, который стрим присылает последним событием. */
    private fun updateUsage(model: String, usage: JsonObject?) {
        if (usage == null) return
        val current = _limits.value?.takeIf { it.model == model } ?: ModelLimits(model = model)
        _limits.value = current.copy(
            promptTokens = usage.int("prompt_tokens") ?: current.promptTokens,
            completionTokens = usage.int("completion_tokens") ?: current.completionTokens,
            totalTokens = usage.int("total_tokens") ?: current.totalTokens,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Каталоги моделей (OpenRouter и совместимые) отдают окно контекста прямо в `/models`. */
    private fun rememberModelLimits(element: JsonElement) {
        val item = element as? JsonObject ?: return
        val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return
        listOf("context_length", "context_window", "max_context_length")
            .firstNotNullOfOrNull { key -> item.int(key) }
            ?.let { contextWindows[id] = it }
        val topProvider = item["top_provider"] as? JsonObject
        listOfNotNull(
            item.int("max_completion_tokens"),
            item.int("max_output_tokens"),
            topProvider.int("max_completion_tokens"),
        ).firstOrNull()?.let { maxOutputTokens[id] = it }
    }

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun parseTurn(body: String): AssistantTurn {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: throw IOException("Провайдер вернул ответ без choices.message")
        val rawContent = message["content"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive?.contentOrNull
            .orEmpty()
        val splitter = ReasoningSplitter()
        val split = splitter.push(rawContent)
        val tail = splitter.flush()
        val content = split.content + tail.content
        val reasoning = buildString {
            reasoningField(message)?.let(::append)
            append(split.reasoning)
            append(tail.reasoning)
        }
        val calls = message["tool_calls"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val function = item["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ToolCall(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: "call-${System.nanoTime()}",
                name = name,
                arguments = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }
        return AssistantTurn(content = content, toolCalls = calls, reasoning = reasoning)
    }

    private fun ApiMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        when {
            toolCallId != null -> {
                put("tool_call_id", JsonPrimitive(toolCallId))
                put("content", JsonPrimitive(content.orEmpty()))
            }
            images.isNotEmpty() -> put("content", buildJsonArray {
                content?.takeIf { it.isNotBlank() }?.let { text ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    })
                }
                images.forEach { dataUrl ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put("image_url", buildJsonObject { put("url", JsonPrimitive(dataUrl)) })
                    })
                }
            })
            toolCalls.isNotEmpty() -> {
                put("content", content?.let(::JsonPrimitive) ?: JsonNull)
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", JsonPrimitive(call.id))
                            put("type", JsonPrimitive("function"))
                            put("function", buildJsonObject {
                                put("name", JsonPrimitive(call.name))
                                put("arguments", JsonPrimitive(call.arguments))
                            })
                        })
                    }
                })
            }
            else -> put("content", JsonPrimitive(content.orEmpty()))
        }
    }

    private fun JsonElement?.orEmpty(): JsonArray =
        (this as? JsonArray) ?: JsonArray(emptyList())

    private class StreamToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private companion object {
        val SERVER_ERROR_CODES = 500..599
    }
}
