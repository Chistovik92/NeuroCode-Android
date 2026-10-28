package com.secrethero.neurocode

import com.secrethero.neurocode.data.ChatCopies
import com.secrethero.neurocode.model.AttachmentKind
import com.secrethero.neurocode.model.ChatAttachment
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ModelRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCopiesTest {
    private fun attachment(id: String) = ChatAttachment(
        id = id,
        name = "$id.txt",
        mimeType = "text/plain",
        sizeBytes = 3,
        relativePath = ".neurocode/attachments/$id/$id.txt",
        kind = AttachmentKind.TEXT,
    )

    private val original = ChatSession(
        id = "orig",
        title = "Задача",
        projectId = "p1",
        model = ModelRef.cloud("prov", "m"),
        sandboxProjectId = "sandbox-1",
        messages = listOf(
            ChatMessage(
                id = "m1",
                role = MessageRole.USER,
                content = "привет",
                attachments = listOf(attachment("a1")),
            ),
            ChatMessage(
                id = "m2",
                role = MessageRole.ASSISTANT,
                content = "ответ",
                generatedBy = ModelRef.cloud("prov", "m"),
            ),
        ),
    )

    private fun counter(): () -> String {
        var next = 0
        return { "new-${next++}" }
    }

    @Test
    fun copyHasNewIdsAndSameContent() = runBlocking {
        val copy = ChatCopies.duplicate(original, " (копия)", now = 5, newId = counter()) { it.copy(id = "c-${it.id}") }

        assertNotEquals(original.id, copy.id)
        assertEquals("Задача (копия)", copy.title)
        assertEquals(original.messages.map { it.content }, copy.messages.map { it.content })
        assertTrue(copy.messages.map { it.id }.none { it in original.messages.map { m -> m.id } })
        assertEquals(copy.messages.size, copy.messages.map { it.id }.toSet().size)
        assertEquals(original.model, copy.model)
        assertEquals(original.messages[1].generatedBy, copy.messages[1].generatedBy)
        assertEquals(5, copy.createdAt)
    }

    @Test
    fun attachmentsAreCopiedToNewFilesAndOriginalIsUntouched() = runBlocking {
        val copy = ChatCopies.duplicate(original, "", newId = counter()) {
            it.copy(id = "copy-of-${it.id}", relativePath = ".neurocode/attachments/copy-of-${it.id}/${it.name}")
        }

        val copied = copy.messages[0].attachments.single()
        assertEquals("copy-of-a1", copied.id)
        assertNotEquals(original.messages[0].attachments.single().relativePath, copied.relativePath)
        assertEquals("a1", original.messages[0].attachments.single().id)
    }

    @Test
    fun missingAttachmentFilesAreDroppedFromTheCopyOnly() = runBlocking {
        val copy = ChatCopies.duplicate(original, "", newId = counter()) { null }

        assertTrue(copy.messages[0].attachments.isEmpty())
        assertEquals(1, original.messages[0].attachments.size)
    }

    @Test
    fun sandboxIsNotInherited() = runBlocking {
        val copy = ChatCopies.duplicate(original, "", newId = counter()) { it }

        assertNull(copy.sandboxProjectId)
        assertEquals("sandbox-1", original.sandboxProjectId)
    }
}
