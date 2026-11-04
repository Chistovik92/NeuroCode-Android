package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.SessionConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SpillManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var spillManager: FileSpillManager
    private lateinit var projectsRoot: File

    @Before
    fun setup() {
        projectsRoot = tempFolder.newFolder("projects")
        spillManager = FileSpillManager(projectsRoot)
    }

    @Test
    fun `shouldSpill returns true for large content`() {
        val largeContent = "x".repeat(SessionConstants.SPILL_THRESHOLD_CHARS + 1)
        assertTrue("Should spill large content", spillManager.shouldSpill(largeContent))
    }

    @Test
    fun `shouldSpill returns false for small content`() {
        val smallContent = "x".repeat(100)
        assertFalse("Should not spill small content", spillManager.shouldSpill(smallContent))
    }

    @Test
    fun `spill creates file and returns reference`() = runBlocking {
        val sessionId = "test-session"
        val eventId = "event-1"
        val content = "x".repeat(10000)

        val ref = spillManager.spill(sessionId, eventId, content)

        assertNotNull("Should return spill reference", ref)
        assertTrue("Spill file should exist",
            File(projectsRoot, ".neurocode/session/$sessionId/spill/${ref.id}.txt").exists()
        )
        assertEquals("Size should match", content.length.toLong(), ref.sizeBytes)
    }

    @Test
    fun `read returns full content when offset and limit cover it`() = runBlocking {
        val sessionId = "test-session"
        val eventId = "event-1"
        val content = "Hello, world! This is a test."

        val ref = spillManager.spill(sessionId, eventId, content)
        val read = spillManager.read(sessionId, ref, offset = 0, limit = content.length)

        assertEquals("Should read full content", content, read)
    }

    @Test
    fun `read returns substring when offset is used`() = runBlocking {
        val sessionId = "test-session"
        val eventId = "event-1"
        val content = "0123456789"

        val ref = spillManager.spill(sessionId, eventId, content)
        val read = spillManager.read(sessionId, ref, offset = 2, limit = 5)

        assertEquals("Should read substring", "23456", read)
    }

    @Test
    fun `getPreview truncates long content`() {
        val longContent = "x".repeat(1000)
        val preview = spillManager.getPreview(longContent, length = 100)

        assertTrue("Preview should be shorter", preview.length < longContent.length)
        assertTrue("Preview should contain truncation notice", preview.contains("more chars spilled"))
    }

    @Test
    fun `cleanup removes all spill files for session`() = runBlocking {
        val sessionId = "test-session"
        
        spillManager.spill(sessionId, "e1", "x".repeat(10000))
        spillManager.spill(sessionId, "e2", "y".repeat(10000))
        spillManager.spill(sessionId, "e3", "z".repeat(10000))

        val spillDir = File(projectsRoot, ".neurocode/session/$sessionId/spill")
        assertTrue("Spill directory should exist before cleanup", spillDir.exists())
        assertEquals("Should have 3 files", 3, spillDir.listFiles()?.size ?: 0)

        spillManager.cleanup(sessionId)

        assertFalse("Spill directory should be removed after cleanup", spillDir.exists())
    }
}
