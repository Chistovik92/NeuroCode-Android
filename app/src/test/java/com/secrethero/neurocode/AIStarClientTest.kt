package com.secrethero.neurocode

import com.secrethero.neurocode.ai.AIStarClient
import com.secrethero.neurocode.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AIStarClientTest {

    /** Каталог со страницы ai.starimg.ru: csrf + категории моделей (порт aistar.ts). */
    private val bootstrapHtml = """
        <html><head><title>AI Star</title></head><body>
        <script>window.__STARIMG_BOOTSTRAP__={"csrf":"abc123","state":null,"models":[{"id":"anthropic","models":[{"id":"claude-opus-4-5","display_name":"Claude Opus 4.5"},{"id":"claude-sonnet-4-5","display_name":"Claude Sonnet 4.5"}]},{"id":"zai","models":[{"id":"glm-5.3-flash","display_name":"GLM 5.3 Flash"},{"id":"glm-4.7","display_name":"GLM 4.7"}]},{"id":"openai","models":[{"id":"gpt-5.2","display_name":null}]}]};</script>
        </body></html>
    """.trimIndent()

    /** Ответ активации: bootstrap содержит state.key с api_key и base_url региона. */
    private val activationHtml = """
        <html><body>
        <script>window.__STARIMG_BOOTSTRAP__={"csrf":"abc123","state":{"err":null,"key":{"access_code":"SECRET","api_key":"sk-star-test123","base_url":"https://de.ai.starimg.ru/"}},"models":[]};</script>
        </body></html>
    """.trimIndent()

    @Test
    fun bootstrapParsesModelsAcrossCategories() {
        val bootstrap = AIStarClient.parseBootstrap(bootstrapHtml)
        assertEquals("abc123", bootstrap.csrf)
        val models = bootstrap.models.flatMap { it.models }.map { it.id }
        assertEquals(listOf("claude-opus-4-5", "claude-sonnet-4-5", "glm-5.3-flash", "glm-4.7", "gpt-5.2"), models)
    }

    @Test
    fun fetchBootstrapModelsReturnsDistinctIds() {
        val html = bootstrapHtml.replace("glm-4.7", "glm-5.3-flash")
        val models = AIStarClient.parseBootstrap(html).models
            .flatMap { it.models }
            .map { it.id }
            .filter { it.isNotBlank() }
            .distinct()
        assertEquals(listOf("claude-opus-4-5", "claude-sonnet-4-5", "glm-5.3-flash", "gpt-5.2"), models)
    }

    @Test
    fun bootstrapWithoutMarkerFailsWithReadableMessage() {
        val error = runCatching { AIStarClient.parseBootstrap("<html>no catalog</html>") }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("каталог моделей") == true)
    }

    @Test
    fun activationExtractsKeyAndNormalizesBaseUrl() {
        val activation = AIStarClient.parseActivation(activationHtml, httpCode = 200)
        assertEquals("sk-star-test123", activation.apiKey)
        assertEquals("https://de.ai.starimg.ru/v1", activation.baseUrl)
    }

    @Test
    fun activationFailsOnSiteError() {
        val html = activationHtml
            .replace("\"err\":null", "\"err\":\"Неверный код доступа\"")
        val error = runCatching { AIStarClient.parseActivation(html, httpCode = 200) }
            .exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("Неверный код доступа", error?.message)
    }

    @Test
    fun activationFailsOnHttpError() {
        val error = runCatching { AIStarClient.parseActivation(activationHtml, httpCode = 403) }
            .exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message?.contains("403") == true)
    }

    @Test
    fun extraModelsMergeFirstThenApiCatalogWithoutDuplicates() {
        val provider = ProviderConfig(
            id = "aistar",
            name = "AI Star",
            baseUrl = "https://ai.starimg.ru/v1",
            model = "glm-5.3-flash",
            extraModels = listOf(" my-fine-tune ", "", "glm-5.3-flash"),
        )
        val merged = provider.mergeModels(listOf("glm-5.3-flash", "claude-opus-4-5", "gpt-5.2"))
        assertEquals(
            listOf("glm-5.3-flash", "my-fine-tune", "claude-opus-4-5", "gpt-5.2"),
            merged,
        )
    }
}
