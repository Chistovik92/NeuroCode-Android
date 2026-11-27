package com.secrethero.neurocode

import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ModelKind
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.ProviderConfig
import com.secrethero.neurocode.model.AgentSkill
import com.secrethero.neurocode.sync.SyncPayload
import com.secrethero.neurocode.sync.SyncSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncPayloadTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun payloadRoundTrips() {
        val payload = SyncPayload(
            deviceId = "A",
            deviceName = "Pixel",
            chats = listOf(
                ChatSession(
                    id = "c1",
                    title = "Чат",
                    projectId = null,
                    messages = listOf(
                        ChatMessage(role = MessageRole.USER, content = "привет"),
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = "ответ",
                            generatedBy = ModelRef.cloud("openrouter", "deepseek-chat"),
                        ),
                    ),
                ),
            ),
            settings = SyncSettings(
                providers = listOf(
                    ProviderConfig(name = "OR", baseUrl = "https://openrouter.ai/api/v1", model = "x"),
                ),
                selectedModel = ModelRef.local("/tmp/a.gguf", "a.gguf"),
                skills = listOf(AgentSkill(name = "Код", prompt = "пиши код")),
            ),
        )
        val text = json.encodeToString(SyncPayload.serializer(), payload)
        val restored = json.decodeFromString(SyncPayload.serializer(), text)
        assertEquals(payload, restored)
    }

    @Test
    fun localModelRefSurvivesRoundTripWithoutFile() {
        val model = ModelRef.local("/data/user/0/app/files/models/qwen.gguf", "qwen.gguf")
        val text = json.encodeToString(ModelRef.serializer(), model)
        val restored = json.decodeFromString(ModelRef.serializer(), text)
        assertEquals(ModelKind.LOCAL, restored.kind)
        assertEquals("qwen.gguf", restored.localName)
        // Файл не переносится, но ссылка сохраняется: второе устройство покажет «импортируйте файл».
        assertEquals(model.localPath, restored.localPath)
    }

    @Test
    fun providerConfigSerializationContainsNoApiKey() {
        val provider = ProviderConfig(name = "P", baseUrl = "https://x", model = "m", apiKeyName = "p-key")
        val text = json.encodeToString(ProviderConfig.serializer(), provider)
        // Поля-ключа нет вовсе: apiKeyName — только ссылка на запись в Keystore.
        assertFalse(text.contains("\"apiKey\""))
    }
}
