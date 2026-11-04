package com.secrethero.neurocode.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Session Event Log — unified event-based format for chat sessions.
 * Inspired by DeepSeek Harness session architecture.
 *
 * Version history:
 * - v1: Legacy ChatMessage format (chat.json)
 * - v2: Event-based format (events.jsonl) with spill and compaction support
 */

// ============================================================================
// Base Event
// ============================================================================

@Serializable
sealed interface SessionEvent {
    val id: String
    val timestamp: Long
    val sessionId: String
}

// ============================================================================
// User Interaction Events
// ============================================================================

@Serializable
data class UserMessageEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val content: String,
    val attachments: List<AttachmentRef> = emptyList()
) : SessionEvent

@Serializable
data class AttachmentRef(
    val id: String,
    val path: String,
    val type: AttachmentType,
    val sizeBytes: Long,
    val originalName: String? = null
)

@Serializable
enum class AttachmentType {
    IMAGE,
    TEXT,
    CODE,
    DOCUMENT,
    OTHER
}

// ============================================================================
// Assistant Response Events
// ============================================================================

@Serializable
data class AssistantMessageEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val content: String,
    val modelId: String,
    val reasoning: String? = null,
    val finishReason: String? = null
) : SessionEvent

// ============================================================================
// Tool Execution Events
// ============================================================================

@Serializable
data class ToolCallEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val toolName: String,
    val arguments: JsonObject,
    val callId: String = UUID.randomUUID().toString()
) : SessionEvent

@Serializable
data class ToolResultEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val callId: String,
    val result: String,
    val spillRef: SpillRef? = null,
    val success: Boolean = true,
    val error: String? = null,
    val durationMs: Long? = null
) : SessionEvent

@Serializable
data class SpillRef(
    val id: String,
    val path: String,  // relative to .neurocode/spill/{sessionId}/
    val sizeBytes: Long,
    val previewLength: Int = 500,
    val contentHash: String? = null
)

// ============================================================================
// Session Management Events
// ============================================================================

@Serializable
data class ModelSwitchEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val fromModelId: String?,
    val toModelId: String,
    val reason: String? = null
) : SessionEvent

@Serializable
data class ContextCompactedEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    override val sessionId: String,
    val compactedEventIds: List<String>,
    val summary: String,
    val tokensFreed: Int,
    val method: CompactionMethod = CompactionMethod.SIMPLE_SUMMARY
) : SessionEvent

@Serializable
enum class CompactionMethod {
    SIMPLE_SUMMARY,     // текстовая сводка старых событий
    LLM_SUMMARY,        // сводка через модель
    TOOL_ONLY,          // только результаты инструментов
    METADATA_ONLY       // только метаданные без текста
}

// ============================================================================
// Session Metadata
// ============================================================================

@Serializable
data class SessionMetadata(
    val version: Int = 2,
    val sessionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val lastEventId: String? = null,
    val eventCount: Int = 0,
    val compactionCount: Int = 0,
    val totalTokensEstimate: Int = 0
)

// ============================================================================
// Session Query
// ============================================================================

data class SessionQuery(
    val sessionId: String? = null,
    val eventType: KClass<out SessionEvent>? = null,
    val textSearch: String? = null,
    val modelId: String? = null,
    val toolName: String? = null,
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null,
    val limit: Int = 100,
    val offset: Int = 0
)

// ============================================================================
// Context Building
// ============================================================================

data class TokenBudget(
    val total: Int,
    val system: Int = 0,
    val skills: Int = 0,
    val project: Int = 0,
    val history: Int = 0,
    val attachments: Int = 0,
    val reserved: Int = 1024
) {
    val available: Int
        get() = total - (system + skills + project + history + attachments + reserved)

    fun withUsed(
        system: Int = this.system,
        skills: Int = this.skills,
        project: Int = this.project,
        history: Int = this.history,
        attachments: Int = this.attachments
    ) = copy(
        system = system,
        skills = skills,
        project = project,
        history = history,
        attachments = attachments
    )
}

data class ContextBuildResult(
    val messages: List<CloudMessage>,
    val tokenCount: Int,
    val budget: TokenBudget,
    val spilledResults: List<SpillRef> = emptyList(),
    val compactedSummaries: List<String> = emptyList()
)

// ============================================================================
// Compaction Result
// ============================================================================

data class CompactionResult(
    val summary: String,
    val removedEventIds: List<String>,
    val tokensFreed: Int,
    val summaryEvent: ContextCompactedEvent
)

// ============================================================================
// Constants
// ============================================================================

object SessionConstants {
    const val VERSION = 2
    const val EVENTS_FILE = "events.jsonl"
    const val METADATA_FILE = "metadata.json"
    const val SPILL_DIR = "spill"
    
    // Политика spill
    const val SPILL_THRESHOLD_CHARS = 8000  // ~2000 tokens
    const val SPILL_PREVIEW_CHARS = 500
    
    // Политика compaction
    const val COMPACTION_THRESHOLD_PERCENT = 80  // процент от окна модели
    const val MIN_EVENTS_BEFORE_COMPACTION = 20
    const val COMPACTION_TARGET_PERCENT = 50  // освободить до этого процента
}
