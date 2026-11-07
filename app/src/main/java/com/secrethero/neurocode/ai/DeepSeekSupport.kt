package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.ModelLimits
import com.secrethero.neurocode.model.ProviderConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object DeepSeekSupport {
    fun isDeepSeek(provider: ProviderConfig): Boolean = provider.id == "deepseek"

    fun messagePrefix(message: ApiMessage, provider: ProviderConfig): JsonPrimitive? =
        if (isDeepSeek(provider) && message.prefix == true) JsonPrimitive(true) else null

    fun cacheUsage(usage: JsonObject?): Pair<Int?, Int?> =
        usage.int("prompt_cache_hit_tokens") to usage.int("prompt_cache_miss_tokens")

    fun ModelLimits.withCacheUsage(usage: JsonObject?): ModelLimits {
        val (hits, misses) = cacheUsage(usage)
        return copy(
            promptCacheHitTokens = hits ?: promptCacheHitTokens,
            promptCacheMissTokens = misses ?: promptCacheMissTokens,
        )
    }

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull
}
