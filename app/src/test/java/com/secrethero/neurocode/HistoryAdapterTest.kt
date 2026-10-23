package com.secrethero.neurocode

import com.secrethero.neurocode.ai.HistoryAdapter
import com.secrethero.neurocode.ai.TargetCapabilities
import com.secrethero.neurocode.data.ProjectManifest
import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ModelRef
import com.secrethero.neurocode.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAdapterTest {
    private val cloud = TargetCapabilities(supportsTools = true, supportsImages = true, contextTokens = null)
    private val local = TargetCapabilities(supportsTools = false, supportsImages = false, contextTokens = null)

    private fun message(role: MessageRole, text: String, id: String = text) =
        ChatMessage(id = id, role = role, content = text)

    @Test
    fun reasoningAndModelSignatureAreNotRepeated() {
        val history = listOf(
            message(MessageRole.USER, "q"),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "a",
                reasoning = "thinking",
                generatedBy = ModelRef.cloud("p", "m"),
            ),
        )

        val adapted = HistoryAdapter.adapt(history, cloud)

        assertNull(adapted.messages[1].reasoning)
        assertNull(adapted.messages[1].generatedBy)
    }

    @Test
    fun toolResultsBecomeTextSummariesForModelsWithoutTools() {
        val history = listOf(
            message(MessageRole.USER, "q"),
            ChatMessage(role = MessageRole.TOOL, content = "x".repeat(2_000), toolName = "read_file"),
        )

        val adapted = HistoryAdapter.adapt(history, local)

        val summary = adapted.messages[1]
        assertEquals(MessageRole.USER, summary.role)
        assertNull(summary.toolName)
        assertTrue(summary.content.startsWith("[сводка: результат инструмента read_file]"))
        assertTrue(summary.content.length < 700)
    }

    @Test
    fun toolResultsStayForModelsWithTools() {
        val history = listOf(ChatMessage(role = MessageRole.TOOL, content = "ok", toolName = "t"))

        assertEquals(MessageRole.TOOL, HistoryAdapter.adapt(history, cloud).messages.single().role)
    }

    @Test
    fun imagesAreRemovedForModelsWithoutVision() {
        val image = ChatAttachment(
            name = "a.png",
            mimeType = "image/png",
            sizeBytes = 1,
            relativePath = "p",
            kind = AttachmentKind.IMAGE,
        )
        val history = listOf(ChatMessage(role = MessageRole.USER, content = "look", attachments = listOf(image)))

        val adapted = HistoryAdapter.adapt(history, local)

        assertTrue(adapted.imagesRemoved)
        assertTrue(adapted.messages.single().attachments.isEmpty())
        assertTrue(adapted.messages.single().content.contains("a.png"))
    }

    @Test
    fun historyIsShortenedToFitSmallerContextWindowKeepingNewest() {
        val history = (1..20).map { message(MessageRole.USER, "m$it " + "x".repeat(300), id = "id$it") }

        val adapted = HistoryAdapter.adapt(history, cloud.copy(contextTokens = 400))

        assertTrue(adapted.droppedMessages > 0)
        assertEquals("id20", adapted.messages.last().id)
        assertEquals(history.size, adapted.messages.size + adapted.droppedMessages)
    }

    @Test
    fun lastMessageIsKeptEvenIfItAloneExceedsTheWindow() {
        val history = listOf(message(MessageRole.USER, "x".repeat(10_000)))

        val adapted = HistoryAdapter.adapt(history, cloud.copy(contextTokens = 100))

        assertEquals(1, adapted.messages.size)
        assertEquals(0, adapted.droppedMessages)
    }

    @Test
    fun unknownWindowKeepsWholeHistory() {
        val history = (1..50).map { message(MessageRole.USER, "m$it") }

        assertEquals(50, HistoryAdapter.adapt(history, cloud).messages.size)
    }

    @Test
    fun transcriptTakesNewestMessagesWithinLimit() {
        val history = listOf(
            message(MessageRole.USER, "old question"),
            message(MessageRole.ASSISTANT, "old answer"),
            message(MessageRole.USER, "new question"),
            message(MessageRole.ASSISTANT, "new answer"),
        )

        val text = HistoryAdapter.transcript(history, maxChars = 40)

        assertFalse(text.contains("old question"))
        assertTrue(text.endsWith("Ассистент: new answer"))
    }

    @Test
    fun projectManifestKeepsModelButDropsLocalPath() {
        val project = Project(
            id = "a",
            name = "Demo",
            rootPath = "/r",
            model = ModelRef.local("/data/user/0/app/files/models/q.gguf", "q.gguf"),
        )

        val decoded = ProjectManifest.decode(ProjectManifest.forExport(project).encode())

        assertEquals("Demo", decoded?.name)
        assertEquals("q.gguf", decoded?.model?.localName)
        assertNull(decoded?.model?.localPath)
    }

    @Test
    fun brokenManifestIsIgnored() {
        assertNull(ProjectManifest.decode("not json"))
    }
}
