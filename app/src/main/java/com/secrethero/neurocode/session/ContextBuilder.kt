package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * ContextBuilder assembles context for model requests with token budget management.
 * Converts SessionEvent log into CloudMessage format while respecting limits.
 */
interface ContextBuilder {
    suspend fun build(
        sessionId: String,
        events: List<SessionEvent>,
        maxTokens: Int,
        systemPrompt: String = "",
        includeSpilled: Boolean = false
    ): ContextBuildResult
}

class StandardContextBuilder(
    private val spillManager: SpillManager
) : ContextBuilder {

    override suspend fun build(
        sessionId: String,
        events: List<SessionEvent>,
        maxTokens: Int,
        systemPrompt: String,
        includeSpilled: Boolean
    ): ContextBuildResult {
        val budget = TokenBudget(total = maxTokens)
        val messages = mutableListOf<CloudMessage>()
        val spilledRefs = mutableListOf<SpillRef>()
        val compactedSummaries = mutableListOf<String>()

        var tokensUsed = 0

        // System prompt
        if (systemPrompt.isNotBlank()) {
            val systemTokens = estimateTokens(systemPrompt)
            tokensUsed += systemTokens
            budget = budget.withUsed(system = systemTokens)
        }

        // Process events in order
        val eventIterator = events.iterator()
        val pendingToolCalls = mutableMapOf<String, ToolCallEvent>()

        while (eventIterator.hasNext()) {
            val event = eventIterator.next()
            val eventTokens = estimateEventTokens(event, includeSpilled, sessionId)

            // Check if adding this event would exceed budget
            if (tokensUsed + eventTokens > maxTokens - budget.reserved) {
                break  // stop adding events
            }

            when (event) {
                is UserMessageEvent -> {
                    messages.add(
                        CloudMessage(
                            role = "user",
                            content = event.content
                        )
                    )
                    tokensUsed += eventTokens
                }

                is AssistantMessageEvent -> {
                    messages.add(
                        CloudMessage(
                            role = "assistant",
                            content = event.content
                        )
                    )
                    tokensUsed += eventTokens
                }

                is ToolCallEvent -> {
                    // Store for pairing with result
                    pendingToolCalls[event.callId] = event
                    tokensUsed += eventTokens
                }

                is ToolResultEvent -> {
                    // Find matching call
                    val call = pendingToolCalls.remove(event.callId)
                    if (call != null) {
                        // Create assistant message with tool call
                        val toolCallMsg = CloudMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(
                                ToolCall(
                                    id = call.callId,
                                    function = FunctionCall(
                                        name = call.toolName,
                                        arguments = call.arguments.toString()
                                    )
                                )
                            )
                        )
                        messages.add(toolCallMsg)

                        // Add result
                        val resultContent = if (event.spillRef != null && includeSpilled) {
                            spilledRefs.add(event.spillRef)
                            val preview = spillManager.getPreview(event.result)
                            "$preview\n\n[Full result spilled to ${event.spillRef.path}]"
                        } else if (event.spillRef != null) {
                            spilledRefs.add(event.spillRef)
                            spillManager.getPreview(event.result)
                        } else {
                            event.result
                        }

                        messages.add(
                            CloudMessage(
                                role = "tool",
                                content = resultContent,
                                toolCallId = call.callId
                            )
                        )
                    }
                    tokensUsed += eventTokens
                }

                is ContextCompactedEvent -> {
                    // Add summary as system message
                    compactedSummaries.add(event.summary)
                    messages.add(
                        CloudMessage(
                            role = "system",
                            content = "Previous context summary:\n${event.summary}"
                        )
                    )
                    tokensUsed += eventTokens
                }

                is ModelSwitchEvent -> {
                    // Informational, not included in context
                }
            }
        }

        return ContextBuildResult(
            messages = messages,
            tokenCount = tokensUsed,
            budget = budget.withUsed(history = tokensUsed),
            spilledResults = spilledRefs,
            compactedSummaries = compactedSummaries
        )
    }

    private suspend fun estimateEventTokens(
        event: SessionEvent,
        includeSpilled: Boolean,
        sessionId: String
    ): Int {
        return when (event) {
            is UserMessageEvent -> estimateTokens(event.content)
            is AssistantMessageEvent -> estimateTokens(event.content) + estimateTokens(event.reasoning ?: "")
            is ToolCallEvent -> estimateTokens(event.arguments.toString()) + 50
            is ToolResultEvent -> {
                if (event.spillRef != null && !includeSpilled) {
                    estimateTokens(spillManager.getPreview(event.result))
                } else {
                    estimateTokens(event.result)
                }
            }
            is ContextCompactedEvent -> estimateTokens(event.summary)
            is ModelSwitchEvent -> 0
        }
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: 1 token ≈ 4 characters
        return (text.length / 4).coerceAtLeast(1)
    }
}
