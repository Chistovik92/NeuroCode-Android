package com.secrethero.neurocode

import com.secrethero.neurocode.ai.uniqueModelFileName
import org.junit.Assert.assertEquals
import org.junit.Test

/** Уникальные имена при повторном импорте GGUF (план 0.9.0, часть 2.4). */
class ModelFileNamesTest {

    @Test
    fun keepsFreeName() {
        assertEquals("llama.gguf", uniqueModelFileName("llama.gguf") { false })
    }

    @Test
    fun appendsCounterWhenTaken() {
        assertEquals(
            "llama-2.gguf",
            uniqueModelFileName("llama.gguf") { it == "llama.gguf" },
        )
    }

    @Test
    fun skipsTakenCounters() {
        val taken = setOf("qwen.gguf", "qwen-2.gguf", "qwen-3.gguf")
        assertEquals(
            "qwen-4.gguf",
            uniqueModelFileName("qwen.gguf") { it in taken },
        )
    }

    @Test
    fun doubleImportNeverOverwrites() {
        val taken = mutableSetOf("gpt.gguf")
        val first = uniqueModelFileName("gpt.gguf") { it in taken }
        taken += first
        val second = uniqueModelFileName("gpt.gguf") { it in taken }
        assertEquals("gpt-2.gguf", first)
        assertEquals("gpt-3.gguf", second)
    }
}
