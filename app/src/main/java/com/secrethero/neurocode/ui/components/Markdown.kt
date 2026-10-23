package com.secrethero.neurocode.ui.components

/** Фрагмент строки с оформлением: жирный, курсив, код или ссылка. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

/** Блок Markdown-разметки ответа модели. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** Пункт списка: [ordinal] == null — маркер «•», иначе номер; [indent] — уровень вложенности. */
    data class ListItem(val ordinal: Int?, val indent: Int, val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

/**
 * Небольшой разбор Markdown для ответов моделей: заголовки, списки, цитаты, блоки кода,
 * горизонтальная линия, **жирный**, *курсив*, `код` и ссылки. Не падает на недописанной
 * разметке — важно для потокового вывода.
 */
object Markdown {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val listRegex = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val quoteRegex = Regex("^\\s{0,3}>\\s?(.*)$")
    private val urlRegex = Regex("https?://[^\\s<>]+")
    private const val INDENT_STEP = 2
    private const val FENCE = "```"
    private const val RULE_MAX_INDENT = 3
    private const val RULE_MIN_MARKERS = 3
    private const val NO_CLOSE = Int.MAX_VALUE

    /** Самая длинная ссылка `[текст](адрес)`: дальше `)` не ищем, иначе разбор становится квадратичным. */
    private const val MAX_LINK_URL = 2_048

    /**
     * Compose не умеет рисовать один текст выше примерно 260 000 px и шире такого же размера: при
     * зацикливании модели («------…» на десятки тысяч символов) это падение приложения. Поэтому очень
     * длинные блоки делятся на части.
     */
    const val MAX_BLOCK_CHARS = 6_000
    const val MAX_CODE_LINES = 300
    const val MAX_CODE_LINE_CHARS = 1_000

    /** Горизонтальная линия: три и больше одинаковых `-`, `*` или `_`, между ними пробелы, отступ до 3. */
    private fun isRule(raw: String): Boolean {
        val line = raw.trim()
        val marker = line.firstOrNull()?.takeIf { it == '-' || it == '*' || it == '_' } ?: return false
        val indent = raw.length - raw.trimStart().length
        return indent <= RULE_MAX_INDENT &&
            line.all { it == marker || it.isWhitespace() } &&
            line.count { it == marker } >= RULE_MIN_MARKERS
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        var fenceLanguage: String? = null
        val fenceLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(inline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        for (raw in source.replace("\r\n", "\n").split('\n')) {
            val trimmed = raw.trim()
            if (fenceLanguage != null) {
                if (trimmed.startsWith(FENCE)) {
                    blocks += MdBlock.Code(fenceLanguage, fenceLines.joinToString("\n"))
                    fenceLanguage = null
                    fenceLines.clear()
                } else {
                    fenceLines += raw
                }
                continue
            }
            val heading = headingRegex.matchEntire(trimmed)
            val list = listRegex.matchEntire(raw)
            val quote = quoteRegex.matchEntire(raw)
            when {
                trimmed.startsWith(FENCE) -> {
                    flushParagraph()
                    fenceLanguage = trimmed.removePrefix(FENCE).trim()
                }
                trimmed.isEmpty() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    val (hashes, title) = heading.destructured
                    blocks += MdBlock.Heading(hashes.length, inline(title))
                }
                isRule(raw) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }
                list != null -> {
                    flushParagraph()
                    val (indent, marker, content) = list.destructured
                    blocks += MdBlock.ListItem(
                        ordinal = marker.takeIf { it.first().isDigit() }?.dropLast(1)?.toIntOrNull(),
                        indent = indent.length / INDENT_STEP,
                        spans = inline(content),
                    )
                }
                quote != null -> {
                    flushParagraph()
                    blocks += MdBlock.Quote(inline(quote.destructured.component1()))
                }
                else -> {
                    val last = blocks.lastOrNull()
                    if (paragraph.isEmpty() && last is MdBlock.ListItem && raw.startsWith(" ")) {
                        // Продолжение пункта списка на следующей строке.
                        blocks[blocks.lastIndex] = last.copy(spans = last.spans + inline(" $trimmed"))
                    } else {
                        paragraph += trimmed
                    }
                }
            }
        }
        // Недозакрытый блок кода (идёт потоковый вывод) показываем как есть.
        fenceLanguage?.let { blocks += MdBlock.Code(it, fenceLines.joinToString("\n")) }
        flushParagraph()
        return blocks.flatMap(::limitBlock)
    }

    private fun limitBlock(block: MdBlock): List<MdBlock> = when (block) {
        is MdBlock.Paragraph -> splitSpans(block.spans).map { MdBlock.Paragraph(it) }
        is MdBlock.Quote -> splitSpans(block.spans).map { MdBlock.Quote(it) }
        is MdBlock.Heading -> splitSpans(block.spans).mapIndexed { index, part ->
            if (index == 0) block.copy(spans = part) else MdBlock.Paragraph(part)
        }
        is MdBlock.ListItem -> splitSpans(block.spans).mapIndexed { index, part ->
            if (index == 0) block.copy(spans = part) else MdBlock.Paragraph(part)
        }
        is MdBlock.Code -> splitCode(block)
        MdBlock.Rule -> listOf(block)
    }

    /** Делит фрагменты на части не длиннее [max] символов; обычные абзацы возвращаются без изменений. */
    internal fun splitSpans(spans: List<MdSpan>, max: Int = MAX_BLOCK_CHARS): List<List<MdSpan>> {
        if (spans.sumOf { it.text.length } <= max) return listOf(spans)
        val parts = mutableListOf<List<MdSpan>>()
        var current = mutableListOf<MdSpan>()
        var used = 0
        for (span in spans) {
            var rest = span.text
            while (rest.isNotEmpty()) {
                val take = minOf(max - used, rest.length)
                current += span.copy(text = rest.substring(0, take))
                used += take
                rest = rest.substring(take)
                if (used >= max) {
                    parts += current
                    current = mutableListOf()
                    used = 0
                }
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    /** Огромный блок кода рисуется несколькими блоками: одна строка или колонка выше лимита Compose роняет приложение. */
    private fun splitCode(block: MdBlock.Code): List<MdBlock> {
        val lines = block.code.split('\n')
        if (lines.size <= MAX_CODE_LINES && lines.none { it.length > MAX_CODE_LINE_CHARS }) return listOf(block)
        val wrapped = lines.flatMap { line ->
            if (line.length <= MAX_CODE_LINE_CHARS) listOf(line) else line.chunked(MAX_CODE_LINE_CHARS)
        }
        return wrapped.chunked(MAX_CODE_LINES).map { MdBlock.Code(block.language, it.joinToString("\n")) }
    }

    /** Разбирает строчную разметку; незакрытые маркеры остаются обычным текстом. */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buffer = StringBuilder()
        var bold = false
        var italic = false

        fun flush() {
            if (buffer.isNotEmpty()) {
                out += MdSpan(buffer.toString(), bold = bold, italic = italic)
                buffer.clear()
            }
        }

        var i = 0

        // Ближайшая `]` после позиции: ищем один раз на отрезок, а не на каждую `[` (иначе «[[[[…» квадратично).
        var closeBracket = -1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_[]()#>-" -> {
                    buffer.append(text[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += MdSpan(text.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    flush()
                    bold = !bold
                    i += 2
                }
                c == '*' && isEmphasisBoundary(text, i, italic) -> {
                    flush()
                    italic = !italic
                    i++
                }
                c == '_' && isUnderscoreBoundary(text, i, italic) -> {
                    flush()
                    italic = !italic
                    i++
                }
                c == '[' -> {
                    if (closeBracket <= i) closeBracket = nextClose(text, i + 1)
                    val link = if (closeBracket == NO_CLOSE) null else parseLink(text, i, closeBracket)
                    if (link != null) {
                        flush()
                        out += MdSpan(link.first, bold = bold, italic = italic, url = link.second)
                        i = link.third
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                c == 'h' && urlRegex.matchAt(text, i) != null -> {
                    val matched = checkNotNull(urlRegex.matchAt(text, i)).value
                    val url = matched.trimEnd('.', ',', ';', ':', '!', '?', ')', '\'', '"')
                    flush()
                    out += MdSpan(url, bold = bold, italic = italic, url = url)
                    i += url.length
                }
                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    private fun nextClose(text: String, from: Int): Int {
        val found = text.indexOf(']', from)
        return if (found < 0) NO_CLOSE else found
    }

    /** `*` открывает курсив перед не-пробелом и закрывает его после не-пробела. */
    private fun isEmphasisBoundary(text: String, index: Int, italicOpen: Boolean): Boolean {
        val next = text.getOrNull(index + 1)
        val prev = text.getOrNull(index - 1)
        return if (italicOpen) prev != null && !prev.isWhitespace() else next != null && !next.isWhitespace()
    }

    /** `_` внутри слова (snake_case) курсивом не считается. */
    private fun isUnderscoreBoundary(text: String, index: Int, italicOpen: Boolean): Boolean {
        val next = text.getOrNull(index + 1)
        val prev = text.getOrNull(index - 1)
        return if (italicOpen) {
            prev != null && !prev.isWhitespace() && (next == null || !next.isLetterOrDigit())
        } else {
            next != null && !next.isWhitespace() && (prev == null || !prev.isLetterOrDigit())
        }
    }

    /** Возвращает (подпись, адрес, индекс после ссылки) для `[текст](адрес)`; [close] — индекс `]`. */
    @Suppress("ReturnCount")
    private fun parseLink(text: String, start: Int, close: Int): Triple<String, String, Int>? {
        if (text.getOrNull(close + 1) != '(') return null
        val limit = minOf(text.length, close + 2 + MAX_LINK_URL)
        var end = close + 2
        while (end < limit && text[end] != ')') end++
        if (end >= limit) return null
        val url = text.substring(close + 2, end).trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return Triple(text.substring(start + 1, close), url, end + 1)
    }
}
