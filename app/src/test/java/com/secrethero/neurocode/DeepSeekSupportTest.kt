package com.secrethero.neurocode

import com.secrethero.neurocode.ai.ApiMessage
import com.secrethero.neurocode.ai.DeepSeekSupport
import com.secrethero.neurocode.model.ModelLimits
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepSeekSupportTest {
    private val deepSeek = provider("deepseek")
    private val assistantPrefix = ApiMessage(role = "assistant", content = "fun ", prefix = true)

    @Test
    fun `assistant prefix is enabled only for DeepSeek`() {
        assertEquals(true, DeepSeekSupport.messagePrefix(assistantPrefix, deepSeek)?.content?.toBoolean())
        assertNull(DeepSeekSupport.messagePrefix(assistantPrefix, provider("openai")))
    }

    @Test
    fun `prefix is omitted unless explicitly enabled`() {
        assertNull(DeepSeekSupport.messagePrefix(ApiMessage(role = "assistant"), deepSeek))
        assertNull(DeepSeekSupport.messagePrefix(assistantPrefix.copy(prefix = false), deepSeek))
    }

    @Test
    fun `cache hit and miss counters are read from usage`() {
        val usage = buildJsonObject {
            put("prompt_cache_hit_tokens", 12)
            put("prompt_cache_miss_tokens", 34)
        }

        assertEquals(12 to 34, DeepSeekSupport.cacheUsage(usage))
    }

    @Test
    fun `missing cache counters preserve previous telemetry`() {
        val previous = ModelLimits(
            model = "deepseek-chat",
            promptCacheHitTokens = 12,
            promptCacheMissTokens = 34,
        )
        val usage = buildJsonObject { put("prompt_cache_hit_tokens", 56) }

        val updated = with(DeepSeekSupport) { previous.withCacheUsage(usage) }

        assertEquals(56, updated.promptCacheHitTokens)
        assertEquals(34, updated.promptCacheMissTokens)
    }

    private fun provider(id: String) = ProviderConfig(
        id = id,
        name = id,
        baseUrl = "https://example.test/v1",
        model = "test-model",
    )
}
