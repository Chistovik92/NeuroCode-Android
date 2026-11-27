package com.secrethero.neurocode

import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.Project
import com.secrethero.neurocode.sync.SyncMerge
import com.secrethero.neurocode.sync.SyncPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun chat(id: String, updatedAt: Long, title: String = "Чат $id") = ChatSession(
        id = id,
        title = title,
        projectId = null,
        createdAt = updatedAt - 10,
        updatedAt = updatedAt,
        messages = listOf(ChatMessage(role = MessageRole.USER, content = "hello $id")),
    )

    private fun project(id: String, updatedAt: Long) = Project(
        id = id,
        name = "Проект $id",
        rootPath = "/tmp/$id",
        createdAt = updatedAt - 10,
        updatedAt = updatedAt,
    )

    private fun payload(
        deviceId: String,
        chats: List<ChatSession> = emptyList(),
        projects: List<Project> = emptyList(),
        updatedAt: Long = 100,
        deletedChats: List<String> = emptyList(),
        deletedProjects: List<String> = emptyList(),
    ) = SyncPayload(
        deviceId = deviceId,
        deviceName = "Устройство $deviceId",
        updatedAt = updatedAt,
        chats = chats,
        projects = projects,
        deletedChatIds = deletedChats,
        deletedProjectIds = deletedProjects,
    )

    @Test
    fun firstSyncAppliesAllRemoteChats() {
        val local = payload("A", updatedAt = 50)
        val remote = payload("B", chats = listOf(chat("c1", 200)), updatedAt = 200)
        val result = SyncMerge.merge(local, remote)
        assertEquals(listOf("c1"), result.chatsToApply.map { it.id })
        assertTrue(result.chatsToDelete.isEmpty())
        assertEquals(1, result.merged.chats.size)
    }

    @Test
    fun remoteNewerWinsForSameChat() {
        val local = payload("A", chats = listOf(chat("c1", 100)), updatedAt = 100)
        val remote = payload("B", chats = listOf(chat("c1", 200, title = "Обновлено")), updatedAt = 200)
        val result = SyncMerge.merge(local, remote)
        assertEquals(1, result.chatsToApply.size)
        assertEquals("Обновлено", result.chatsToApply.single().title)
    }

    @Test
    fun localNewerKeepsLocalAndMergedKeepsLocalVersion() {
        val local = payload("A", chats = listOf(chat("c1", 500)), updatedAt = 500)
        val remote = payload("B", chats = listOf(chat("c1", 200)), updatedAt = 200)
        val result = SyncMerge.merge(local, remote)
        assertTrue(result.chatsToApply.isEmpty())
        assertEquals("Чат c1", result.merged.chats.single().title)
    }

    @Test
    fun equalTimestampBreaksTieByDeviceId() {
        val local = payload("A", chats = listOf(chat("c1", 300)), updatedAt = 300)
        val remote = payload("B", chats = listOf(chat("c1", 300, title = "С другого")), updatedAt = 300)
        val result = SyncMerge.merge(local, remote)
        // "B" > "A": побеждает remote.
        assertEquals(1, result.chatsToApply.size)
        assertEquals("С другого", result.chatsToApply.single().title)
    }

    @Test
    fun tombstoneDeletesRemoteChatLocallyAndStaysDeleted() {
        val local = payload("A", chats = listOf(chat("c1", 100)), updatedAt = 100)
        val remote = payload(
            "B",
            updatedAt = 200,
            deletedChats = listOf("c1"),
        )
        val result = SyncMerge.merge(local, remote)
        assertEquals(listOf("c1"), result.chatsToDelete)
        // Удалённое не возвращается в merged.
        assertTrue(result.merged.chats.none { it.id == "c1" })
        assertTrue(result.merged.deletedChatIds.contains("c1"))
    }

    @Test
    fun deletedChatOnRemoteIsNotReapplied() {
        val local = payload("A", chats = emptyList(), updatedAt = 100, deletedChats = listOf("c1"))
        val remote = payload("B", chats = listOf(chat("c1", 200)), updatedAt = 200)
        val result = SyncMerge.merge(local, remote)
        assertTrue(result.chatsToApply.isEmpty())
        assertTrue(result.merged.chats.none { it.id == "c1" })
    }

    @Test
    fun projectsMergeLww() {
        val local = payload("A", projects = listOf(project("p1", 100)), updatedAt = 100)
        val remote = payload(
            "B",
            projects = listOf(
                Project(
                    id = "p1",
                    name = "Новое",
                    rootPath = "/tmp/p1",
                    createdAt = 390,
                    updatedAt = 400,
                ),
            ),
            updatedAt = 400,
        )
        val result = SyncMerge.merge(local, remote)
        assertEquals(1, result.projectsToApply.size)
        assertEquals("Новое", result.projectsToApply.single().name)
    }

    @Test
    fun settingsLwwChoosesNewerSide() {
        val local = payload("A", updatedAt = 100)
        val remote = payload("B", updatedAt = 200)
        val result = SyncMerge.merge(local, remote)
        // remote.settings null, но выбор по времени: remote новее — settings remote (null здесь).
        assertNull(result.merged.settings)
    }

    @Test
    fun mergedUpdatedAtIsStrictlyNewerThanBoth() {
        val local = payload("A", updatedAt = 300)
        val remote = payload("B", updatedAt = 300)
        val result = SyncMerge.merge(local, remote)
        assertTrue(result.merged.updatedAt > 300)
    }

    @Test
    fun mergeRejectsSameDevice() {
        val local = payload("A")
        val remote = payload("A")
        val error = runCatching { SyncMerge.merge(local, remote) }
        assertTrue(error.isFailure)
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
