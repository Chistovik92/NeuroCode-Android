package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * SpillManager handles large tool results that would consume too much context.
 * Results exceeding SPILL_THRESHOLD_CHARS are written to disk, and only a preview
 * is kept in the event log.
 */
interface SpillManager {
    /**
     * Check if content should be spilled to disk.
     */
    fun shouldSpill(content: String): Boolean

    /**
     * Write content to spill storage and return reference.
     */
    suspend fun spill(sessionId: String, eventId: String, content: String): SpillRef

    /**
     * Read content from spill storage with optional offset/limit.
     */
    suspend fun read(sessionId: String, ref: SpillRef, offset: Int = 0, limit: Int = 2048): String

    /**
     * Clean up spill files for a session.
     */
    suspend fun cleanup(sessionId: String)

    /**
     * Get preview of spilled content (first N chars).
     */
    fun getPreview(content: String, length: Int = SessionConstants.SPILL_PREVIEW_CHARS): String
}

class FileSpillManager(
    private val projectsRoot: File
) : SpillManager {

    private fun spillDir(sessionId: String) =
        File(projectsRoot, ".neurocode/session/$sessionId/${SessionConstants.SPILL_DIR}")

    private fun spillFile(sessionId: String, spillId: String) =
        File(spillDir(sessionId), "$spillId.txt")

    override fun shouldSpill(content: String): Boolean =
        content.length > SessionConstants.SPILL_THRESHOLD_CHARS

    override suspend fun spill(
        sessionId: String,
        eventId: String,
        content: String
    ): SpillRef = withContext(Dispatchers.IO) {
        val spillId = "$eventId-${UUID.randomUUID().toString().take(8)}"
        val spillDir = spillDir(sessionId)
        spillDir.mkdirs()

        val file = spillFile(sessionId, spillId)
        file.writeText(content)

        val hash = computeHash(content)

        SpillRef(
            id = spillId,
            path = "$spillId.txt",
            sizeBytes = file.length(),
            previewLength = SessionConstants.SPILL_PREVIEW_CHARS,
            contentHash = hash
        )
    }

    override suspend fun read(
        sessionId: String,
        ref: SpillRef,
        offset: Int,
        limit: Int
    ): String = withContext(Dispatchers.IO) {
        val file = spillFile(sessionId, ref.id)
        if (!file.exists()) return@withContext "[Spilled content not found]"

        val content = file.readText()
        val endIndex = minOf(offset + limit, content.length)
        
        if (offset >= content.length) {
            return@withContext ""
        }

        content.substring(offset, endIndex)
    }

    override suspend fun cleanup(sessionId: String) = withContext(Dispatchers.IO) {
        val spillDir = spillDir(sessionId)
        if (spillDir.exists() && spillDir.isDirectory) {
            spillDir.listFiles()?.forEach { it.delete() }
            spillDir.delete()
        }
    }

    override fun getPreview(content: String, length: Int): String {
        if (content.length <= length) return content
        return content.take(length) + "\n\n[... ${content.length - length} more chars spilled to disk ...]"
    }

    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
