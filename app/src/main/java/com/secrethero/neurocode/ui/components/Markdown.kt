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
    private val ruleRegex = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val listRegex = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
    private val quoteRegex = Regex("^\\s{0,3}>\\s?(.*)$")
    private val urlRegex = Regex("^https?://[^\\s<>]+")
    private const val INDENT_STEP = 2
    private const val FENCE = "```"

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
                ruleRegex.matches(raw) -> {
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
        return blocks
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
        while (i < text.length) {
            val c = text[i]
            val rest = text.substring(i)
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
                rest.startsWith("**") || rest.startsWith("__") -> {
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
                    val link = parseLink(text, i)
                    if (link != null) {
                        flush()
                        out += MdSpan(link.first, bold = bold, italic = italic, url = link.second)
                        i = link.third
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                c == 'h' && urlRegex.containsMatchIn(rest) -> {
                    val url = urlRegex.find(rest)!!.value.trimEnd('.', ',', ';', ':', '!', '?', ')', '\'', '"')
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

    /** Возвращает (подпись, адрес, индекс после ссылки) для `[текст](адрес)`. */
    @Suppress("ReturnCount")
    private fun parseLink(text: String, start: Int): Triple<String, String, Int>? {
        val close = text.indexOf(']', start + 1)
        if (close < 0 || text.getOrNull(close + 1) != '(') return null
        val end = text.indexOf(')', close + 2)
        if (end < 0) return null
        val url = text.substring(close + 2, end).trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return Triple(text.substring(start + 1, close), url, end + 1)
    }
}
