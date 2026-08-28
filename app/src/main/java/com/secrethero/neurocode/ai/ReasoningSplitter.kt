package com.secrethero.neurocode.ai

/**
 * Разделяет поток модели на ответ и размышления.
 *
 * Часть моделей отдаёт reasoning отдельным полем (`reasoning_content`/`reasoning`), а часть —
 * прямо в тексте, в тегах `<think>…</think>`. Этот разделитель обрабатывает второй случай и
 * корректно переживает разрыв тега между чанками стрима.
 */
class ReasoningSplitter {

    data class Chunk(val content: String, val reasoning: String) {
        val isEmpty: Boolean get() = content.isEmpty() && reasoning.isEmpty()
    }

    private val buffer = StringBuilder()
    private var closingTag: String? = null

    /** Обрабатывает очередной кусок текста модели. */
    fun push(text: String): Chunk {
        if (text.isEmpty()) return EMPTY
        buffer.append(text)
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var needsMoreInput = false
        while (!needsMoreInput) {
            val active = closingTag
            needsMoreInput = if (active == null) {
                scanForOpenTag(content)
            } else {
                scanForCloseTag(reasoning, active)
            }
        }
        return Chunk(content.toString(), reasoning.toString())
    }

    /** Отдаёт остаток буфера: незакрытый тег в конце ответа не должен потерять текст. */
    fun flush(): Chunk {
        if (buffer.isEmpty()) return EMPTY
        val rest = buffer.toString()
        buffer.setLength(0)
        return if (closingTag == null) Chunk(rest, "") else Chunk("", rest)
    }

    /** Ищет начало блока размышлений; возвращает true, когда нужен следующий чанк. */
    private fun scanForOpenTag(content: StringBuilder): Boolean {
        val open = findTag(OPEN_TAGS)
        if (open == null) {
            content.append(takeSafePrefix(OPEN_TAGS))
            return true
        }
        content.append(buffer, 0, open.index)
        buffer.delete(0, open.index + open.tag.length)
        closingTag = CLOSE_TAGS.getValue(open.tag)
        return false
    }

    /** Ищет конец блока размышлений; возвращает true, когда нужен следующий чанк. */
    private fun scanForCloseTag(reasoning: StringBuilder, activeTag: String): Boolean {
        val close = findTag(listOf(activeTag))
        if (close == null) {
            reasoning.append(takeSafePrefix(listOf(activeTag)))
            return true
        }
        reasoning.append(buffer, 0, close.index)
        buffer.delete(0, close.index + close.tag.length)
        closingTag = null
        return false
    }

    private class Found(val index: Int, val tag: String)

    private fun findTag(tags: List<String>): Found? =
        tags.mapNotNull { tag ->
            buffer.indexOf(tag).takeIf { it >= 0 }?.let { Found(it, tag) }
        }.minByOrNull { it.index }

    /**
     * Отдаёт из буфера всё, кроме хвоста, который может оказаться началом тега:
     * `"…текст <thi"` дожидается следующего чанка.
     */
    private fun takeSafePrefix(tags: List<String>): String {
        val keep = tags.maxOf { tag -> partialTagSuffix(tag) }
        val cut = buffer.length - keep
        if (cut <= 0) return ""
        val prefix = buffer.substring(0, cut)
        buffer.delete(0, cut)
        return prefix
    }

    private fun partialTagSuffix(tag: String): Int {
        val max = minOf(tag.length - 1, buffer.length)
        for (size in max downTo 1) {
            val start = buffer.length - size
            if (tag.regionMatches(0, buffer, start, size)) return size
        }
        return 0
    }

    private fun String.regionMatches(
        offset: Int,
        other: CharSequence,
        otherOffset: Int,
        length: Int,
    ): Boolean {
        for (i in 0 until length) {
            if (this[offset + i] != other[otherOffset + i]) return false
        }
        return true
    }

    private companion object {
        val OPEN_TAGS = listOf("<think>", "<thinking>")
        val CLOSE_TAGS = mapOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
        )
        val EMPTY = Chunk("", "")
    }
}
