package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.AssistantTurn
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun complete(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray = JsonArray(emptyList()),
        onDelta: ((String) -> Unit)? = null,
    ): AssistantTurn {
        if (onDelta != null) {
            var received = false
            try {
                return executeStreaming(provider, apiKey, messages, tools) { chunk ->
                    received = true
                    onDelta(chunk)
                }
            } catch (error: IOException) {
                if (received) throw error
            }
        }
        return executeBlocking(provider, apiKey, messages, tools)
    }

    suspend fun models(provider: ProviderConfig, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            validate(provider, apiKey)
            val endpoint = provider.baseUrl.trimEnd('/') + "/models"
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
                val root = json.parseToJsonElement(body).jsonObject
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
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray,
    ): AssistantTurn = withContext(Dispatchers.IO) {
        validate(provider, apiKey)
        val payload = buildPayload(provider, messages, tools, stream = false)
        val request = buildRequest(provider, apiKey, payload)
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw apiError(response.code, body, response.message)
            }
            parseTurn(body)
        }
    }

    private suspend fun executeStreaming(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray,
        onChunk: (String) -> Unit,
    ): AssistantTurn = withContext(Dispatchers.IO) {
        validate(provider, apiKey)
        val payload = buildPayload(provider, messages, tools, stream = true)
        val request = buildRequest(provider, apiKey, payload)
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
        provider: ProviderConfig,
        apiKey: String,
        payload: JsonObject,
    ): Request {
        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
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
        return IOException(
            "Ошибка API $code: ${message ?: body.take(500).ifBlank { fallback }}",
        )
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
