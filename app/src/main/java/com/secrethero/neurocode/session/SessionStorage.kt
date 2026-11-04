package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Storage for session events using JSON Lines format.
 * Each event is written as a single line in events.jsonl.
 *
 * File structure:
 * ```
 * .neurocode/session/{sessionId}/
 *   ├── metadata.json        (SessionMetadata)
 *   ├── events.jsonl         (one SessionEvent per line)
 *   └── spill/              (large tool results)
 *       ├── {eventId}.txt
 *       └── ...
 * ```
 */
interface SessionStorage {
    /**
     * Append a new event to the session log.
     */
    suspend fun append(event: SessionEvent)

    /**
     * Load all events for a session.
     */
    suspend fun loadSession(sessionId: String): List<SessionEvent>

    /**
     * Query events with filters.
     */
    suspend fun query(filter: SessionQuery): List<SessionEvent>

    /**
     * Get session metadata.
     */
    suspend fun getMetadata(sessionId: String): SessionMetadata?

    /**
     * Update session metadata.
     */
    suspend fun updateMetadata(metadata: SessionMetadata)

    /**
     * Delete events (used during compaction).
     */
    suspend fun deleteEvents(sessionId: String, eventIds: List<String>)

    /**
     * Check if session exists and is in v2 format.
     */
    suspend fun exists(sessionId: String): Boolean
}

class FileSessionStorage(
    private val projectsRoot: File
) : SessionStorage {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun sessionDir(sessionId: String) =
        File(projectsRoot, ".neurocode/session/$sessionId")

    private fun eventsFile(sessionId: String) =
        File(sessionDir(sessionId), SessionConstants.EVENTS_FILE)

    private fun metadataFile(sessionId: String) =
        File(sessionDir(sessionId), SessionConstants.METADATA_FILE)

    override suspend fun append(event: SessionEvent) = withContext(Dispatchers.IO) {
        val sessionDir = sessionDir(event.sessionId)
        sessionDir.mkdirs()

        // Append event as JSON line
        val eventsFile = eventsFile(event.sessionId)
        val eventLine = json.encodeToString<SessionEvent>(event)
        eventsFile.appendText("$eventLine\n")

        // Update metadata
        val metadata = getMetadata(event.sessionId) ?: SessionMetadata(
            sessionId = event.sessionId,
            version = SessionConstants.VERSION
        )
        updateMetadata(
            metadata.copy(
                lastModifiedAt = System.currentTimeMillis(),
                lastEventId = event.id,
                eventCount = metadata.eventCount + 1
            )
        )
    }

    override suspend fun loadSession(sessionId: String): List<SessionEvent> = withContext(Dispatchers.IO) {
        val eventsFile = eventsFile(sessionId)
        if (!eventsFile.exists()) return@withContext emptyList()

        eventsFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    json.decodeFromString<SessionEvent>(line)
                } catch (e: Exception) {
                    null  // skip malformed lines
                }
            }
    }

    override suspend fun query(filter: SessionQuery): List<SessionEvent> = withContext(Dispatchers.IO) {
        val sessionId = filter.sessionId ?: return@withContext emptyList()
        val events = loadSession(sessionId)

        events
            .asSequence()
            .filter { event ->
                // Type filter
                filter.eventType?.let { type ->
                    type.isInstance(event)
                } ?: true
            }
            .filter { event ->
                // Time range filter
                val afterStart = filter.fromTimestamp?.let { event.timestamp >= it } ?: true
                val beforeEnd = filter.toTimestamp?.let { event.timestamp <= it } ?: true
                afterStart && beforeEnd
            }
            .filter { event ->
                // Text search
                filter.textSearch?.let { query ->
                    val text = when (event) {
                        is UserMessageEvent -> event.content
                        is AssistantMessageEvent -> event.content + (event.reasoning ?: "")
                        is ToolResultEvent -> event.result
                        is ContextCompactedEvent -> event.summary
                        else -> ""
                    }
                    text.contains(query, ignoreCase = true)
                } ?: true
            }
            .filter { event ->
                // Model filter
                filter.modelId?.let { modelId ->
                    when (event) {
                        is AssistantMessageEvent -> event.modelId == modelId
                        is ModelSwitchEvent -> event.toModelId == modelId
                        else -> true
                    }
                } ?: true
            }
            .filter { event ->
                // Tool filter
                filter.toolName?.let { toolName ->
                    when (event) {
                        is ToolCallEvent -> event.toolName == toolName
                        else -> true
                    }
                } ?: true
            }
            .drop(filter.offset)
            .take(filter.limit)
            .toList()
    }

    override suspend fun getMetadata(sessionId: String): SessionMetadata? = withContext(Dispatchers.IO) {
        val metadataFile = metadataFile(sessionId)
        if (!metadataFile.exists()) return@withContext null

        try {
            json.decodeFromString<SessionMetadata>(metadataFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun updateMetadata(metadata: SessionMetadata) = withContext(Dispatchers.IO) {
        val metadataFile = metadataFile(metadata.sessionId)
        metadataFile.parentFile?.mkdirs()
        metadataFile.writeText(json.encodeToString(metadata))
    }

    override suspend fun deleteEvents(sessionId: String, eventIds: List<String>) = withContext(Dispatchers.IO) {
        val events = loadSession(sessionId)
        val filtered = events.filterNot { it.id in eventIds }

        // Rewrite events file
        val eventsFile = eventsFile(sessionId)
        eventsFile.writeText("")
        filtered.forEach { event ->
            val eventLine = json.encodeToString<SessionEvent>(event)
            eventsFile.appendText("$eventLine\n")
        }

        // Update metadata
        val metadata = getMetadata(sessionId) ?: return@withContext
        updateMetadata(
            metadata.copy(
                lastModifiedAt = System.currentTimeMillis(),
                eventCount = filtered.size
            )
        )
    }

    override suspend fun exists(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val metadata = getMetadata(sessionId)
        metadata?.version == SessionConstants.VERSION
    }
}
