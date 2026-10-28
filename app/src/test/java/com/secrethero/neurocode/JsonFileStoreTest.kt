package com.secrethero.neurocode

import com.secrethero.neurocode.data.JsonFileStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Serializable
data class SampleEntry(val id: String, val value: Int)

class JsonFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun createStore(name: String) = JsonFileStore(
        file = File(tmp.root, name),
        serializer = ListSerializer(SampleEntry.serializer()),
        defaultValue = { emptyList() },
    )

    @Test
    fun roundTripPreservesData() = runBlocking {
        val store = createStore("state.json")
        store.write(listOf(SampleEntry("a", 1), SampleEntry("b", 2)))
        assertEquals(listOf(SampleEntry("a", 1), SampleEntry("b", 2)), store.read())
    }

    @Test
    fun missingFileReturnsDefault() = runBlocking {
        assertTrue(createStore("absent.json").read().isEmpty())
    }

    @Test
    fun corruptedFileFallsBackToDefault() = runBlocking {
        val file = File(tmp.root, "broken.json")
        file.writeText("{ this is not json ")
        assertTrue(createStore("broken.json").read().isEmpty())
    }

    private fun quarantined(name: String) =
        tmp.root.listFiles()!!.filter { it.name.startsWith("$name.corrupt-") }

    @Test
    fun corruptedFileIsQuarantinedAndNotOverwrittenByNextWrite() = runBlocking {
        val file = File(tmp.root, "chats.json")
        file.writeText("{ truncated")
        val reported = mutableListOf<File>()
        val store = JsonFileStore(
            file = file,
            serializer = ListSerializer(SampleEntry.serializer()),
            defaultValue = { emptyList() },
            onCorrupt = { reported += it },
        )

        assertTrue(store.read().isEmpty())
        store.write(listOf(SampleEntry("fresh", 1)))

        assertEquals(1, reported.size)
        assertEquals(reported.single(), quarantined("chats.json").single())
        assertEquals("{ truncated", reported.single().readText())
        assertEquals(listOf(SampleEntry("fresh", 1)), store.read())
    }

    @Test
    fun blankAndMissingFilesAreNotReportedAsCorrupt() = runBlocking {
        File(tmp.root, "blank.json").writeText("  \n")
        var reports = 0
        val blank = JsonFileStore(
            File(tmp.root, "blank.json"),
            ListSerializer(SampleEntry.serializer()),
            { emptyList() },
            { reports++ },
        )
        val missing = JsonFileStore(
            File(tmp.root, "none.json"),
            ListSerializer(SampleEntry.serializer()),
            { emptyList() },
            { reports++ },
        )

        assertTrue(blank.read().isEmpty())
        assertTrue(missing.read().isEmpty())
        assertEquals(0, reports)
        assertTrue(quarantined("blank.json").isEmpty())
    }

    @Test
    fun onlyFiveQuarantinedCopiesAreKept() = runBlocking {
        val file = File(tmp.root, "state.json")
        val store = createStore("state.json")
        repeat(8) { index ->
            file.writeText("broken $index")
            store.read()
            Thread.sleep(3)
        }

        assertEquals(JsonFileStore.MAX_QUARANTINED, quarantined("state.json").size)
    }

    @Test
    fun goodFileIsNeverQuarantined() = runBlocking {
        val store = createStore("good.json")
        store.write(listOf(SampleEntry("a", 1)))

        assertEquals(1, store.read().size)
        assertTrue(quarantined("good.json").isEmpty())
    }

    @Test
    fun writeReplacesPreviousContentAtomically() = runBlocking {
        val store = createStore("atomic.json")
        store.write(listOf(SampleEntry("old", 0)))
        store.write(listOf(SampleEntry("new", 9)))
        assertEquals(listOf(SampleEntry("new", 9)), store.read())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
