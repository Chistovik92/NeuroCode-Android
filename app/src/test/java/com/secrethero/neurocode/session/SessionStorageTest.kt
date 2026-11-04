package com.secrethero.neurocode.session

import com.secrethero.neurocode.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: FileSessionStorage
    private lateinit var projectsRoot: File

    @Before
    fun setup() {
        projectsRoot = tempFolder.newFolder("projects")
        storage = FileSessionStorage(projectsRoot)
    }

    @Test
    fun `append event creates session directory and files`() = runBlocking {
        val sessionId = "test-session"
        val event = UserMessageEvent(
            sessionId = sessionId,
            content = "Hello, world!"
        )

        storage.append(event)

        val sessionDir = File(projectsRoot, ".neurocode/session/$sessionId")
        assertTrue("Session directory should exist", sessionDir.exists())
        assertTrue("Events file should exist", File(sessionDir, "events.jsonl").exists())
        assertTrue("Metadata file should exist", File(sessionDir, "metadata.json").exists())
    }

    @Test
    fun `load session returns appended events in order`() = runBlocking {
        val sessionId = "test-session"
        val events = listOf(
            UserMessageEvent(sessionId = sessionId, content = "First"),
            AssistantMessageEvent(sessionId = sessionId, content = "Second", modelId = "gpt-4"),
            UserMessageEvent(sessionId = sessionId, content = "Third")
        )

        events.forEach { storage.append(it) }

        val loaded = storage.loadSession(sessionId)
        assertEquals("Should load all events", events.size, loaded.size)
        assertEquals("Should maintain order", events.map { it.id }, loaded.map { it.id })
    }

    @Test
    fun `query by text search finds matching events`() = runBlocking {
        val sessionId = "test-session"
        storage.append(UserMessageEvent(sessionId = sessionId, content = "Hello world"))
        storage.append(AssistantMessageEvent(sessionId = sessionId, content = "Goodbye", modelId = "gpt-4"))
        storage.append(UserMessageEvent(sessionId = sessionId, content = "Hello again"))

        val query = SessionQuery(sessionId = sessionId, textSearch = "hello")
        val results = storage.query(query)

        assertEquals("Should find 2 matching events", 2, results.size)
        assertTrue("All results should contain 'hello'",
            results.all { event ->
                when (event) {
                    is UserMessageEvent -> event.content.contains("hello", ignoreCase = true)
                    else -> false
                }
            }
        )
    }

    @Test
    fun `query by event type filters correctly`() = runBlocking {
        val sessionId = "test-session"
        storage.append(UserMessageEvent(sessionId = sessionId, content = "User 1"))
        storage.append(AssistantMessageEvent(sessionId = sessionId, content = "Assistant", modelId = "gpt-4"))
        storage.append(UserMessageEvent(sessionId = sessionId, content = "User 2"))

        val query = SessionQuery(sessionId = sessionId, eventType = UserMessageEvent::class)
        val results = storage.query(query)

        assertEquals("Should find 2 user messages", 2, results.size)
        assertTrue("All results should be UserMessageEvent",
            results.all { it is UserMessageEvent }
        )
    }

    @Test
    fun `metadata tracks event count`() = runBlocking {
        val sessionId = "test-session"
        
        storage.append(UserMessageEvent(sessionId = sessionId, content = "1"))
        storage.append(UserMessageEvent(sessionId = sessionId, content = "2"))
        storage.append(UserMessageEvent(sessionId = sessionId, content = "3"))

        val metadata = storage.getMetadata(sessionId)
        assertNotNull("Metadata should exist", metadata)
        assertEquals("Event count should match", 3, metadata!!.eventCount)
    }

    @Test
    fun `delete events removes specified events`() = runBlocking {
        val sessionId = "test-session"
        val event1 = UserMessageEvent(id = "e1", sessionId = sessionId, content = "Keep")
        val event2 = UserMessageEvent(id = "e2", sessionId = sessionId, content = "Delete")
        val event3 = UserMessageEvent(id = "e3", sessionId = sessionId, content = "Keep")

        storage.append(event1)
        storage.append(event2)
        storage.append(event3)

        storage.deleteEvents(sessionId, listOf("e2"))

        val remaining = storage.loadSession(sessionId)
        assertEquals("Should have 2 events", 2, remaining.size)
        assertEquals("Should keep e1", "e1", remaining[0].id)
        assertEquals("Should keep e3", "e3", remaining[1].id)
    }
}
