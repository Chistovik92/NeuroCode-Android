package com.secrethero.neurocode

import com.secrethero.neurocode.data.ChatMigration
import com.secrethero.neurocode.data.ChatStorage
import com.secrethero.neurocode.data.MigrationResult
import com.secrethero.neurocode.data.SplitChatStore
import com.secrethero.neurocode.model.ChatMessage
import com.secrethero.neurocode.model.ChatSession
import com.secrethero.neurocode.model.MessageRole
import com.secrethero.neurocode.model.ModelRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateDir get() = tmp.root
    private val legacyFile get() = File(stateDir, "chats.json")
    private val chatsDir get() = File(stateDir, "chats")

    private fun session(
        id: String,
        title: String = "Диалог $id",
        project: String? = "p1",
        messages: Int = 2,
    ): ChatSession =
        ChatSession(
            id = id,
            title = title,
            projectId = project,
            createdAt = 1_000,
            updatedAt = 2_000 + id.hashCode().toLong().mod(1_000L),
            model = ModelRef.cloud("prov", "m-$id"),
            messages = (1..messages).map {
                ChatMessage(
                    id = "$id-m$it",
                    role = if (it % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                    content = "сообщение $it",
                    generatedBy = if (it % 2 == 0) ModelRef.local("/m/x.gguf", "x.gguf") else null,
                )
            },
        )

    private fun writeLegacy(sessions: List<ChatSession>) =
        legacyFile.writeText(json.encodeToString(ListSerializer(ChatSession.serializer()), sessions))

    private fun migratedFile() = File(stateDir, "chats.json.migrated")

    @Test
    fun legacyFileIsMigratedWithBackupAndNothingIsLost() = runBlocking {
        val original = listOf(session("a"), session("b", project = null), session("c", messages = 5))
        writeLegacy(original)
        val storage = ChatStorage(stateDir)

        val loaded = storage.loadAll()

        assertEquals(original.sortedBy { it.id }, loaded.sortedBy { it.id })
        assertTrue(File(chatsDir, "a.json").isFile)
        assertTrue(File(chatsDir, "index.json").isFile)
        assertTrue(File(stateDir, "chats.json.bak-0.8.0").isFile)
        assertTrue(migratedFile().isFile)
        assertFalse(legacyFile.exists())
    }

    @Test
    fun secondLaunchReadsMigratedFilesAndDoesNotMigrateAgain() = runBlocking {
        writeLegacy(listOf(session("a"), session("b")))
        ChatStorage(stateDir).loadAll()

        val again = ChatStorage(stateDir).loadAll()

        assertEquals(setOf("a", "b"), again.map { it.id }.toSet())
        assertEquals(1, stateDir.listFiles()!!.count { it.name.startsWith("chats.json.migrated") })
    }

    @Test
    fun emptyAndMissingLegacyFilesGiveEmptyList() = runBlocking {
        assertTrue(ChatStorage(stateDir).loadAll().isEmpty())
        legacyFile.writeText("[]")
        assertTrue(ChatStorage(tmp.newFolder()).loadAll().isEmpty())
    }

    @Test
    fun corruptLegacyFileIsQuarantinedNotMigratedAndNotLost() = runBlocking {
        legacyFile.writeText("[{ broken")
        val reported = mutableListOf<File>()

        val loaded = ChatStorage(stateDir, onCorrupt = { reported += it }).loadAll()

        assertTrue(loaded.isEmpty())
        assertEquals(1, reported.size)
        assertEquals("[{ broken", reported.single().readText())
        assertFalse(File(chatsDir, "index.json").exists())
    }

    @Test
    fun writingOneChatDoesNotTouchOtherFiles() = runBlocking {
        val a = session("a")
        val b = session("b")
        writeLegacy(listOf(a, b))
        val storage = ChatStorage(stateDir)
        val loaded = storage.loadAll()
        val fileB = File(chatsDir, "b.json")
        fileB.setLastModified(123_000)
        val before = fileB.readText()

        val updatedA = loaded.first { it.id == "a" }.copy(title = "Новое имя")
        storage.apply(listOf(updatedA, loaded.first { it.id == "b" }), listOf(updatedA), emptySet())

        assertEquals(123_000, fileB.lastModified())
        assertEquals(before, fileB.readText())
        assertTrue(File(chatsDir, "a.json").readText().contains("Новое имя"))
    }

    @Test
    fun removedChatFileIsDeletedAndOthersStay() = runBlocking {
        writeLegacy(listOf(session("a"), session("b")))
        val storage = ChatStorage(stateDir)
        val loaded = storage.loadAll()

        storage.apply(loaded.filter { it.id == "b" }, emptyList(), setOf("a"))

        assertFalse(File(chatsDir, "a.json").exists())
        assertEquals(listOf("b"), ChatStorage(stateDir).loadAll().map { it.id })
    }

    @Test
    fun corruptSingleChatFileDoesNotBlockOthers() = runBlocking {
        writeLegacy(listOf(session("a"), session("b"), session("c")))
        ChatStorage(stateDir).loadAll()
        File(chatsDir, "b.json").writeText("{ truncated")
        val reported = mutableListOf<File>()

        val loaded = ChatStorage(stateDir, onCorrupt = { reported += it }).loadAll()

        assertEquals(setOf("a", "c"), loaded.map { it.id }.toSet())
        assertEquals(1, reported.size)
        assertTrue(reported.single().name.startsWith("b.json.corrupt-"))
    }

    @Test
    fun lostIndexIsRebuiltFromChatFiles() = runBlocking {
        writeLegacy(listOf(session("a"), session("b")))
        ChatStorage(stateDir).loadAll()
        File(chatsDir, "index.json").delete()

        val loaded = ChatStorage(stateDir).loadAll()

        assertEquals(2, loaded.size)
        assertTrue(File(chatsDir, "index.json").isFile)
    }

    @Test
    fun failedMigrationLeavesLegacyFileAndKeepsWorkingOnIt() = runBlocking {
        val original = listOf(session("a"), session("b"))
        writeLegacy(original)
        // «chats» занят обычным файлом: каталог для диалогов создать нельзя, запись упадёт.
        File(stateDir, "chats").writeText("not a directory")
        val storage = ChatStorage(stateDir)

        val loaded = storage.loadAll()
        storage.apply(loaded + session("z"), listOf(session("z")), emptySet())

        assertEquals(original.map { it.id }.toSet(), loaded.map { it.id }.toSet())
        assertTrue(legacyFile.isFile)
        val saved = json.decodeFromString(ListSerializer(ChatSession.serializer()), legacyFile.readText())
        assertEquals(3, saved.size)
    }

    @Test
    fun migrationResultsAreReported() = runBlocking {
        val store = SplitChatStore(chatsDir)
        assertEquals(MigrationResult.NOT_NEEDED, ChatMigration.run(legacyFile, store))

        writeLegacy(listOf(session("a")))
        assertEquals(MigrationResult.DONE, ChatMigration.run(legacyFile, store))
        assertEquals(MigrationResult.NOT_NEEDED, ChatMigration.run(legacyFile, store))
    }

    @Test
    fun fiveHundredChatsMigrateAndLoad() = runBlocking {
        val many = (1..500).map { session("id$it", messages = 4) }
        writeLegacy(many)

        val started = System.nanoTime()
        val loaded = ChatStorage(stateDir).loadAll()
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(500, loaded.size)
        assertEquals(2_000, loaded.sumOf { it.messages.size })
        assertTrue("миграция 500 диалогов заняла $millis мс", millis < 20_000)
    }

    @Test
    fun chatsWithIdenticalTitlesStaySeparateAndDeletingOneKeepsTheOther() = runBlocking {
        val first = session("one", title = "Задача", project = "p1")
        val second = session("two", title = "Задача", project = "p2")
        val storage = ChatStorage(stateDir)
        storage.loadAll()
        storage.apply(listOf(first, second), listOf(first, second), emptySet())

        storage.apply(listOf(second), emptyList(), setOf("one"))

        val reloaded = ChatStorage(stateDir).loadAll()
        assertEquals(listOf("two"), reloaded.map { it.id })
        assertEquals("p2", reloaded.single().projectId)
        assertEquals(second.messages, reloaded.single().messages)
    }

    @Test
    fun savedModelSignaturesAndSandboxSurviveRestart() = runBlocking {
        val withSandbox = session("a", project = null).copy(sandboxProjectId = "sandbox-7")
        val storage = ChatStorage(stateDir)
        storage.loadAll()
        storage.apply(listOf(withSandbox), listOf(withSandbox), emptySet())

        val reloaded = ChatStorage(stateDir).loadAll().single()

        assertEquals("sandbox-7", reloaded.sandboxProjectId)
        assertNull(reloaded.projectId)
        assertEquals(withSandbox.messages[1].generatedBy, reloaded.messages[1].generatedBy)
        assertEquals(withSandbox.model, reloaded.model)
    }

    @Test
    fun legacyFileFromVersion077WithoutNewFieldsStillMigrates() = runBlocking {
        val message = """{"id":"m1","role":"USER","content":"привет"}"""
        legacyFile.writeText("""[{"id":"s1","title":"Старый","projectId":"p","messages":[$message]}]""")

        val loaded = ChatStorage(stateDir).loadAll()

        assertEquals("Старый", loaded.single().title)
        assertNull(loaded.single().sandboxProjectId)
        assertEquals("привет", loaded.single().messages.single().content)
    }

    @Test
    fun unsafeChatIdsCannotEscapeTheChatsDirectory() = runBlocking {
        val storage = ChatStorage(stateDir)
        storage.loadAll()
        val evil = session("../../evil")

        storage.apply(listOf(evil), listOf(evil), emptySet())

        assertFalse(File(stateDir.parentFile, "evil.json").exists())
        assertTrue(chatsDir.listFiles()!!.any { it.name == "evil.json" })
        assertNull(chatsDir.listFiles()!!.firstOrNull { it.name.contains("/") })
    }
}
