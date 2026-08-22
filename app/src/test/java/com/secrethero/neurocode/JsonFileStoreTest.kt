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

    @Test
    fun writeReplacesPreviousContentAtomically() = runBlocking {
        val store = createStore("atomic.json")
        store.write(listOf(SampleEntry("old", 0)))
        store.write(listOf(SampleEntry("new", 9)))
        assertEquals(listOf(SampleEntry("new", 9)), store.read())
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
