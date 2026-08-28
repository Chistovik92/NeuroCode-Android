package com.secrethero.neurocode

import com.secrethero.neurocode.ai.ReasoningSplitter
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningSplitterTest {

    @Test
    fun `plain text goes to content`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("Привет, вот ответ.")
        assertEquals("Привет, вот ответ.", chunk.content + splitter.flush().content)
        assertEquals("", chunk.reasoning)
    }

    @Test
    fun `think block goes to reasoning`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("<think>сначала подумаю</think>ответ")
        assertEquals("сначала подумаю", chunk.reasoning)
        assertEquals("ответ", chunk.content + splitter.flush().content)
    }

    @Test
    fun `tag split across chunks is not lost`() {
        val splitter = ReasoningSplitter()
        val first = splitter.push("до <thi")
        val second = splitter.push("nk>мысли</think>после")
        val tail = splitter.flush()
        assertEquals("до ", first.content)
        assertEquals("мысли", second.reasoning)
        assertEquals("после", second.content + tail.content)
    }

    @Test
    fun `closing tag split across chunks is not lost`() {
        val splitter = ReasoningSplitter()
        splitter.push("<think>раз")
        val second = splitter.push("два</thi")
        val third = splitter.push("nk>готово")
        assertEquals("два", second.reasoning)
        assertEquals("готово", third.content + splitter.flush().content)
    }

    @Test
    fun `unclosed think block is kept as reasoning`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("<think>обрыв связи")
        val tail = splitter.flush()
        assertEquals("обрыв связи", chunk.reasoning + tail.reasoning)
        assertEquals("", chunk.content + tail.content)
    }

    @Test
    fun `partial closing tag at the end stays in reasoning`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("<think>мысль</thi")
        val tail = splitter.flush()
        assertEquals("мысль</thi", chunk.reasoning + tail.reasoning)
        assertEquals("", chunk.content + tail.content)
    }

    @Test
    fun `thinking tag variant is supported`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("<thinking>план</thinking>итог")
        assertEquals("план", chunk.reasoning)
        assertEquals("итог", chunk.content + splitter.flush().content)
    }

    @Test
    fun `several blocks are concatenated`() {
        val splitter = ReasoningSplitter()
        val chunk = splitter.push("a<think>1</think>b<think>2</think>c")
        val tail = splitter.flush()
        assertEquals("abc", chunk.content + tail.content)
        assertEquals("12", chunk.reasoning)
    }
}
