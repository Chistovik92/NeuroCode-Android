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

    suspend fun complete(
        provider: ProviderConfig,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: JsonArray = JsonArray(emptyList()),
    ): AssistantTurn = withContext(Dispatchers.IO) {
        require(provider.baseUrl.startsWith("https://")) {
            "Адрес провайдера должен использовать HTTPS"
        }
        require(provider.model.isNotBlank()) { "Укажите модель в настройках провайдера" }
        require(apiKey.isNotBlank()) { "API-ключ не задан" }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(provider.model))
            put("messages", buildJsonArray {
                messages.forEach { add(it.toJson()) }
            })
            put("temperature", JsonPrimitive(0.2))
            if (tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", JsonPrimitive("auto"))
            }
        }
        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply { provider.extraHeaders.forEach { (name, value) -> header(name, value) } }
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(body).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull()
                throw IOException(
                    "Ошибка API ${response.code}: ${message ?: body.take(500).ifBlank { response.message }}",
                )
            }
            parseTurn(body)
        }
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
}
