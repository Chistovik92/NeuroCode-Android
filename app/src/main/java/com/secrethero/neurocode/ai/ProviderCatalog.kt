package com.secrethero.neurocode.ai

import com.secrethero.neurocode.model.ProviderConfig

object ProviderCatalog {
    fun defaults(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1-mini",
        ),
        ProviderConfig(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openai/gpt-4.1-mini",
            extraHeaders = mapOf(
                "HTTP-Referer" to "https://github.com/SecretHero/NeuroCode",
                "X-Title" to "NeuroCode Android",
            ),
        ),
        ProviderConfig(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        ),
        ProviderConfig(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            model = "llama-3.3-70b-versatile",
        ),
        ProviderConfig(
            id = "mistral",
            name = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            model = "codestral-latest",
        ),
    )
}
