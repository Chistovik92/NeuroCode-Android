package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import java.util.UUID

/**
 * ContextCompactor handles automatic context window management by compacting
 * old events into summaries when approaching model's context limit.
 */
interface ContextCompactor {
    /**
     * Check if compaction should be triggered.
     */
    fun shouldCompact(
        events: List<SessionEvent>,
        modelContextWindow: Int,
        currentTokenCount: Int
    ): Boolean

    /**
     * Compact events into a summary, freeing up tokens.
     */
    suspend fun compact(
        sessionId: String,
        events: List<SessionEvent>,
        targetTokenCount: Int
    ): CompactionResult
}

class SimpleContextCompactor : ContextCompactor {

    override fun shouldCompact(
        events: List<SessionEvent>,
        modelContextWindow: Int,
        currentTokenCount: Int
    ): Boolean {
        val threshold = (modelContextWindow * SessionConstants.COMPACTION_THRESHOLD_PERCENT) / 100
        val hasEnoughEvents = events.size >= SessionConstants.MIN_EVENTS_BEFORE_COMPACTION
        
        return currentTokenCount >= threshold && hasEnoughEvents
    }

    override suspend fun compact(
        sessionId: String,
        events: List<SessionEvent>,
        targetTokenCount: Int
    ): CompactionResult {
        // Calculate how many events to compact
        val targetPercent = SessionConstants.COMPACTION_TARGET_PERCENT / 100.0
        val eventsToCompact = (events.size * (1.0 - targetPercent)).toInt()
        
        if (eventsToCompact <= 0) {
            return CompactionResult(
                summary = "",
                removedEventIds = emptyList(),
                tokensFreed = 0,
                summaryEvent = ContextCompactedEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    sessionId = sessionId,
                    compactedEventIds = emptyList(),
                    summary = "",
                    tokensFreed = 0
                )
            )
        }

        val toCompact = events.take(eventsToCompact)
        val summary = generateSummary(toCompact)
        val tokensFreed = estimateTokens(toCompact)

        val summaryEvent = ContextCompactedEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            compactedEventIds = toCompact.map { it.id },
            summary = summary,
            tokensFreed = tokensFreed,
            method = CompactionMethod.SIMPLE_SUMMARY
        )

        return CompactionResult(
            summary = summary,
            removedEventIds = toCompact.map { it.id },
            tokensFreed = tokensFreed,
            summaryEvent = summaryEvent
        )
    }

    private fun generateSummary(events: List<SessionEvent>): String {
        val builder = StringBuilder()
        builder.appendLine("=== Compacted context summary ===")
        builder.appendLine("Period: ${formatTimestamp(events.firstOrNull()?.timestamp)} to ${formatTimestamp(events.lastOrNull()?.timestamp)}")
        builder.appendLine()

        // Summarize by event type
        val byType = events.groupBy { it::class.simpleName }
        
        byType.forEach { (type, typeEvents) ->
            builder.appendLine("$type: ${typeEvents.size} events")
        }

        builder.appendLine()

        // Summarize key interactions
        val userMessages = events.filterIsInstance<UserMessageEvent>()
        val assistantMessages = events.filterIsInstance<AssistantMessageEvent>()
        val toolCalls = events.filterIsInstance<ToolCallEvent>()

        if (userMessages.isNotEmpty()) {
            builder.appendLine("User messages:")
            userMessages.take(3).forEach { msg ->
                builder.appendLine("  - ${msg.content.take(100)}${if (msg.content.length > 100) "..." else ""}")
            }
            if (userMessages.size > 3) {
                builder.appendLine("  ... and ${userMessages.size - 3} more")
            }
            builder.appendLine()
        }

        if (toolCalls.isNotEmpty()) {
            builder.appendLine("Tools used:")
            val toolCounts = toolCalls.groupingBy { it.toolName }.eachCount()
            toolCounts.forEach { (tool, count) ->
                builder.appendLine("  - $tool: $count times")
            }
            builder.appendLine()
        }

        if (assistantMessages.isNotEmpty()) {
            builder.appendLine("Assistant responses: ${assistantMessages.size}")
        }

        builder.appendLine("=== End of summary ===")

        return builder.toString()
    }

    private fun estimateTokens(events: List<SessionEvent>): Int {
        // Rough estimate: 1 token ≈ 4 characters
        var chars = 0
        
        events.forEach { event ->
            when (event) {
                is UserMessageEvent -> chars += event.content.length
                is AssistantMessageEvent -> chars += event.content.length + (event.reasoning?.length ?: 0)
                is ToolResultEvent -> chars += event.result.length
                is ContextCompactedEvent -> chars += event.summary.length
                else -> chars += 50  // metadata events
            }
        }

        return chars / 4
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null) return "unknown"
        val date = java.util.Date(timestamp)
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)
    }
}
