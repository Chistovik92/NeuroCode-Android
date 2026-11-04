package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * SessionMigration handles conversion from v1 (ChatMessage) to v2 (SessionEvent) format.
 */
object SessionMigration {

    /**
     * Convert a ChatMessage to SessionEvent(s).
     * A single ChatMessage may produce multiple events (message + tool calls + results).
     */
    fun convertMessage(message: ChatMessage, sessionId: String): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        val timestamp = message.timestamp

        when (message) {
            is UserMessage -> {
                events.add(
                    UserMessageEvent(
                        id = message.id,
                        timestamp = timestamp,
                        sessionId = sessionId,
                        content = message.content,
                        attachments = message.attachments.map { convertAttachment(it) }
                    )
                )
            }

            is AssistantMessage -> {
                // Add assistant message event
                events.add(
                    AssistantMessageEvent(
                        id = message.id,
                        timestamp = timestamp,
                        sessionId = sessionId,
                        content = message.content,
                        modelId = message.modelId ?: "unknown",
                        reasoning = message.reasoning,
                        finishReason = null
                    )
                )

                // Add tool call and result events
                message.toolCalls.forEachIndexed { index, toolCall ->
                    val callId = toolCall.id ?: UUID.randomUUID().toString()
                    val callTimestamp = timestamp + index * 100  // slight offset for ordering

                    // Tool call event
                    events.add(
                        ToolCallEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = callTimestamp,
                            sessionId = sessionId,
                            toolName = toolCall.function.name,
                            arguments = parseArguments(toolCall.function.arguments),
                            callId = callId
                        )
                    )

                    // Tool result event (if we have it in toolResults)
                    message.toolResults[callId]?.let { result ->
                        events.add(
                            ToolResultEvent(
                                id = UUID.randomUUID().toString(),
                                timestamp = callTimestamp + 50,
                                sessionId = sessionId,
                                callId = callId,
                                result = result,
                                spillRef = null,
                                success = true
                            )
                        )
                    }
                }
            }
        }

        return events
    }

    /**
     * Convert a ChatSession to SessionMetadata + List<SessionEvent>.
     */
    fun convertSession(session: ChatSession): Pair<SessionMetadata, List<SessionEvent>> {
        val events = mutableListOf<SessionEvent>()
        
        // Convert all messages
        session.messages.forEach { message ->
            events.addAll(convertMessage(message, session.id))
        }

        // Add model switch events when model changes
        var previousModelId: String? = null
        session.messages.filterIsInstance<AssistantMessage>().forEach { msg ->
            val currentModelId = msg.modelId ?: "unknown"
            if (previousModelId != null && previousModelId != currentModelId) {
                events.add(
                    ModelSwitchEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = msg.timestamp,
                        sessionId = session.id,
                        fromModelId = previousModelId,
                        toModelId = currentModelId,
                        reason = "Model changed during session"
                    )
                )
            }
            previousModelId = currentModelId
        }

        // Sort events by timestamp
        val sortedEvents = events.sortedBy { it.timestamp }

        val metadata = SessionMetadata(
            version = SessionConstants.VERSION,
            sessionId = session.id,
            createdAt = session.createdAt,
            lastModifiedAt = session.messages.maxOfOrNull { it.timestamp } ?: session.createdAt,
            lastEventId = sortedEvents.lastOrNull()?.id,
            eventCount = sortedEvents.size,
            compactionCount = 0,
            totalTokensEstimate = 0
        )

        return metadata to sortedEvents
    }

    private fun convertAttachment(attachment: com.secrethero.neurocode.model.Attachment): AttachmentRef {
        return AttachmentRef(
            id = UUID.randomUUID().toString(),
            path = attachment.path,
            type = when {
                attachment.mimeType?.startsWith("image/") == true -> AttachmentType.IMAGE
                attachment.mimeType?.startsWith("text/") == true -> AttachmentType.TEXT
                attachment.name?.endsWith(".kt") == true ||
                attachment.name?.endsWith(".java") == true ||
                attachment.name?.endsWith(".py") == true -> AttachmentType.CODE
                else -> AttachmentType.OTHER
            },
            sizeBytes = attachment.sizeBytes,
            originalName = attachment.name
        )
    }

    private fun parseArguments(argumentsJson: String): JsonObject {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson) as? JsonObject
                ?: buildJsonObject {}
        } catch (e: Exception) {
            buildJsonObject {
                put("raw", argumentsJson)
            }
        }
    }
}
