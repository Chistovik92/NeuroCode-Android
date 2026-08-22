package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AssistantTurn
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
)

class OpenAiCompatibleClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

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

    suspend fun complete(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray = JsonArray(emptyList()),
        onDelta: ((String) -> Unit)? = null,
    ): AssistantTurn {
        validate(provider, apiKey)
        if (onDelta == null) {
            return tryChatEndpoints(provider) { endpoint ->
                executeBlocking(endpoint, apiKey, messages, tools, provider)
            }
        }
        var received = false
        return tryChatEndpoints(provider, shouldRethrow = { received }) { endpoint ->
            executeStreaming(endpoint, apiKey, messages, tools, { chunk ->
                received = true
                onDelta(chunk)
            }, provider)
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
        validate(provider, apiKey)
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
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
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
            parseTurn(body)
        }
    }

    private suspend fun executeStreaming(
        endpoint: String,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray,
        onChunk: (String) -> Unit,
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
            val source = response.body?.source()
                ?: throw IOException("Провайдер вернул пустой поток")
            val content = StringBuilder()
            val builders = mutableMapOf<Int, StreamToolCall>()
            while (true) {
                val line = source.readUtf8Line() ?: break
                val data = line.takeIf { it.startsWith("data:") }
                    ?.removePrefix("data:")?.trim() ?: continue
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val element = runCatching { json.parseToJsonElement(data).jsonObject }
                    .getOrNull() ?: continue
                val delta = element["choices"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("delta") as? JsonObject ?: continue
                (delta["content"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { chunk ->
                        content.append(chunk)
                        onChunk(chunk)
                    }
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
            )
        }
    }

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
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        val short = message ?: body.take(160).ifBlank { fallback }
        return ApiException(code, "Ошибка API $code: $short")
    }

    private fun parseTurn(body: String): AssistantTurn {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
            ?: throw IOException("Провайдер вернул ответ без choices.message")
        val content = message["content"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive?.contentOrNull
            .orEmpty()
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
        return AssistantTurn(content = content, toolCalls = calls)
    }

    private fun ApiMessage.toJson(): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        when {
            toolCallId != null -> {
                put("tool_call_id", JsonPrimitive(toolCallId))
                put("content", JsonPrimitive(content.orEmpty()))
            }
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
}
