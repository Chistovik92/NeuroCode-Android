package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContextCompactorTest {

    private lateinit var compactor: SimpleContextCompactor

    @Before
    fun setup() {
        compactor = SimpleContextCompactor()
    }

    @Test
    fun `shouldCompact returns false when token count is low`() {
        val events = generateEvents(30)
        val modelWindow = 4096
        val currentTokens = 1000  // 24% of window

        val result = compactor.shouldCompact(events, modelWindow, currentTokens)

        assertFalse("Should not compact with low token count", result)
    }

    @Test
    fun `shouldCompact returns true when approaching limit`() {
        val events = generateEvents(30)
        val modelWindow = 4096
        val currentTokens = 3400  // 83% of window

        val result = compactor.shouldCompact(events, modelWindow, currentTokens)

        assertTrue("Should compact when approaching limit", result)
    }

    @Test
    fun `shouldCompact returns false with too few events`() {
        val events = generateEvents(10)
        val modelWindow = 4096
        val currentTokens = 3400

        val result = compactor.shouldCompact(events, modelWindow, currentTokens)

        assertFalse("Should not compact with too few events", result)
    }

    @Test
    fun `compact removes appropriate number of events`() = runBlocking {
        val sessionId = "test-session"
        val events = generateEvents(50)
        val targetTokens = 2048

        val result = compactor.compact(sessionId, events, targetTokens)

        assertTrue("Should remove some events", result.removedEventIds.isNotEmpty())
        assertTrue("Should generate summary", result.summary.isNotEmpty())
        assertTrue("Should free tokens", result.tokensFreed > 0)
        assertEquals("Summary event should match removed count",
            result.removedEventIds.size,
            result.summaryEvent.compactedEventIds.size
        )
    }

    @Test
    fun `compact generates readable summary`() = runBlocking {
        val sessionId = "test-session"
        val events = listOf(
            UserMessageEvent(sessionId = sessionId, content = "Hello"),
            AssistantMessageEvent(sessionId = sessionId, content = "Hi", modelId = "gpt-4"),
            ToolCallEvent(sessionId = sessionId, toolName = "read_file", arguments = kotlinx.serialization.json.buildJsonObject {}),
            ToolResultEvent(sessionId = sessionId, callId = "c1", result = "file content")
        )

        val result = compactor.compact(sessionId, events, 1024)

        assertTrue("Summary should mention user messages", result.summary.contains("UserMessageEvent"))
        assertTrue("Summary should mention tools", result.summary.contains("read_file") || result.summary.contains("Tools used"))
    }

    private fun generateEvents(count: Int): List<SessionEvent> {
        val sessionId = "test"
        return (1..count).map { i ->
            if (i % 2 == 0) {
                UserMessageEvent(sessionId = sessionId, content = "Message $i")
            } else {
                AssistantMessageEvent(sessionId = sessionId, content = "Response $i", modelId = "gpt-4")
            }
        }
    }
}
