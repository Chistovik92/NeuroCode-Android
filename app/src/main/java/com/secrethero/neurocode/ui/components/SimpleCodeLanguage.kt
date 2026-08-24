package com.secrethero.neurocode.ui.components

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import com.secrethero.neurocode.util.LanguageSpec

class SimpleCodeLanguage(private val spec: LanguageSpec) : EmptyLanguage() {
    override fun getAnalyzeManager(): AnalyzeManager = SimpleAnalyzeManager(spec)

    companion object {
        fun forFileName(fileName: String?): SimpleCodeLanguage? {
            val spec = fileName?.let { com.secrethero.neurocode.util.CodeLanguages.byFileName(it) }
            return spec?.let(::SimpleCodeLanguage)
        }
    }
}

internal class SimpleAnalyzeManager(
    private val spec: LanguageSpec,
) : AnalyzeManager {
    private var receiver: StyleReceiver? = null
    private var reference: ContentReference? = null

    override fun setReceiver(receiver: StyleReceiver?) {
        this.receiver = receiver
    }

    override fun reset(content: ContentReference, extras: Bundle) {
        reference = content
        publish()
    }

    override fun insert(start: CharPosition, end: CharPosition, inserted: CharSequence) =
        publish()

    override fun delete(start: CharPosition, end: CharPosition, deleted: CharSequence) =
        publish()

    override fun rerun() = publish()

    override fun destroy() {
        receiver = null
        reference = null
    }

    private fun publish() {
        val ref = reference ?: return
        val styles = runCatching { CodeHighlighter(spec).analyze(ref) }.getOrNull() ?: return
        receiver?.setStyles(this, styles)
    }
}

private class Token(
    val line: Int,
    val column: Int,
    val length: Int,
    val colorId: Int,
    val bold: Boolean = false,
)

private class CodeHighlighter(private val spec: LanguageSpec) {

    private val tokens = mutableListOf<Token>()
    private var blockOpen: String? = null
    private var blockClose: String? = null
    private var blockStyle: Int = EditorColorScheme.COMMENT
    private var xmlTagOpen = false
    private var mdInFence = false

    fun analyze(ref: ContentReference): Styles {
        val builder = MappedSpans.Builder()
        val lineCount = ref.lineCount
        var totalChars = 0L
        for (index in 0 until lineCount) totalChars += ref.getColumnCount(index)
        if (totalChars <= MAX_ANALYZE_CHARS) {
            when (spec.mode) {
                LanguageSpec.Mode.CODE -> highlightCode(ref)
                LanguageSpec.Mode.XML -> highlightXml(ref)
                LanguageSpec.Mode.MARKDOWN -> highlightMarkdown(ref)
            }
            emit(builder)
        }
        builder.addNormalIfNull()
        return Styles(builder.build())
    }

    private fun emit(builder: MappedSpans.Builder) {
        var lastLine = -1
        tokens.forEach { token ->
            for (line in lastLine + 1 until token.line) builder.determine(line)
            val style = if (token.bold) {
                TextStyle.makeStyle(token.colorId, true)
            } else {
                TextStyle.makeStyle(token.colorId)
            }
            builder.addIfNeeded(token.line, token.column, style)
            builder.determine(token.line)
            lastLine = token.line
        }
    }

    private fun add(line: Int, column: Int, text: String, colorId: Int, bold: Boolean = false) {
        if (text.isEmpty()) return
        tokens.add(Token(line, column, text.length, colorId, bold))
    }

    private fun highlightCode(ref: ContentReference) {
        for (lineIndex in 0 until ref.lineCount) {
            val line = ref.getLine(lineIndex)
            scanCodeLine(lineIndex, line)
        }
    }

    private fun scanCodeLine(lineIndex: Int, line: String) {
        var index = 0
        while (index < line.length) {
            if (blockClose != null) {
                val close = blockClose ?: return
                val end = line.indexOf(close, index)
                if (end < 0) {
                    add(lineIndex, index, line.substring(index), blockStyle)
                    index = line.length
                } else {
                    add(lineIndex, index, line.substring(index, end + close.length), blockStyle)
                    index = end + close.length
                    blockOpen = null
                    blockClose = null
                }
                continue
            }
            val block = spec.blockComments.firstOrNull { line.startsWith(it.first, index) }
            if (block != null) {
                val end = line.indexOf(block.second, index + block.first.length)
                if (end < 0) {
                    blockOpen = block.first
                    blockClose = block.second
                    blockStyle = EditorColorScheme.COMMENT
                    add(lineIndex, index, line.substring(index), EditorColorScheme.COMMENT)
                    index = line.length
                } else {
                    add(
                        lineIndex,
                        index,
                        line.substring(index, end + block.second.length),
                        EditorColorScheme.COMMENT,
                    )
                    index = end + block.second.length
                }
                continue
            }
            val lineComment = spec.lineComments.firstOrNull { line.startsWith(it, index) }
            if (lineComment != null) {
                add(lineIndex, index, line.substring(index), EditorColorScheme.COMMENT)
                break
            }
            val delim = spec.stringDelims.firstOrNull { line.startsWith(it, index) }
            if (delim != null) {
                index = scanString(lineIndex, line, index, delim)
                continue
            }
            val current = line[index]
            if (current.isDigit()) {
                index = scanNumber(lineIndex, line, index)
                continue
            }
            if (current == '@' && index + 1 < line.length &&
                (line[index + 1].isLetter() || line[index + 1] == '_')
            ) {
                val end = wordEnd(line, index + 1)
                add(lineIndex, index, line.substring(index, end), EditorColorScheme.ANNOTATION)
                index = end
                continue
            }
            if (current.isLetter() || current == '_') {
                val end = wordEnd(line, index)
                val word = line.substring(index, end)
                when {
                    spec.keywords.contains(word) ->
                        add(lineIndex, index, word, EditorColorScheme.KEYWORD)
                    nextMeaningful(line, end) == '(' ->
                        add(lineIndex, index, word, EditorColorScheme.FUNCTION_NAME)
                    word[0].isUpperCase() ->
                        add(lineIndex, index, word, EditorColorScheme.IDENTIFIER_NAME)
                }
                index = end
                continue
            }
            index++
        }
    }

    private fun scanString(lineIndex: Int, line: String, start: Int, delim: String): Int {
        val multiline = delim.length >= TRIPLE_QUOTE_LENGTH
        var index = start + delim.length
        while (index <= line.length) {
            if (!multiline && index == line.length) break
            if (line.startsWith(delim, index)) {
                val end = index + delim.length
                add(lineIndex, start, line.substring(start, end), EditorColorScheme.LITERAL)
                return end
            }
            if (spec.useEscape && index < line.length && line[index] == '\\' && !multiline) {
                index += ESCAPE_STEP
                continue
            }
            index++
        }
        if (multiline) {
            blockOpen = delim
            blockClose = delim
            blockStyle = EditorColorScheme.LITERAL
        }
        add(lineIndex, start, line.substring(start), EditorColorScheme.LITERAL)
        return line.length
    }

    private fun scanNumber(lineIndex: Int, line: String, start: Int): Int {
        var end = start
        while (end < line.length &&
            (line[end].isLetterOrDigit() || line[end] == '.' || line[end] == '_')
        ) {
            end++
        }
        add(lineIndex, start, line.substring(start, end), EditorColorScheme.LITERAL)
        return end
    }

    private fun highlightXml(ref: ContentReference) {
        for (lineIndex in 0 until ref.lineCount) {
            val line = ref.getLine(lineIndex)
            var index = 0
            var inTag = xmlTagOpen
            while (index < line.length) {
                if (blockClose != null) {
                    val close = blockClose ?: break
                    val end = line.indexOf(close, index)
                    if (end < 0) {
                        add(lineIndex, index, line.substring(index), EditorColorScheme.COMMENT)
                        index = line.length
                    } else {
                        add(
                            lineIndex,
                            index,
                            line.substring(index, end + close.length),
                            EditorColorScheme.COMMENT,
                        )
                        index = end + close.length
                        blockClose = null
                    }
                    continue
                }
                val commentStart = "<!--"
                if (line.startsWith(commentStart, index)) {
                    val end = line.indexOf("-->", index)
                    if (end < 0) {
                        blockClose = "-->"
                        add(lineIndex, index, line.substring(index), EditorColorScheme.COMMENT)
                        index = line.length
                    } else {
                        add(
                            lineIndex,
                            index,
                            line.substring(index, end + COMMENT_CLOSE_LENGTH),
                            EditorColorScheme.COMMENT,
                        )
                        index = end + COMMENT_CLOSE_LENGTH
                    }
                    continue
                }
                val current = line[index]
                when {
                    current == '<' -> {
                        inTag = true
                        val nameEnd = tagNameEnd(line, index)
                        if (nameEnd > index + 1) {
                            add(
                                lineIndex,
                                index,
                                line.substring(index, nameEnd),
                                EditorColorScheme.HTML_TAG,
                            )
                            index = nameEnd
                            continue
                        }
                        add(lineIndex, index, "<", EditorColorScheme.HTML_TAG)
                        index++
                    }
                    inTag && current == '>' -> {
                        add(lineIndex, index, ">", EditorColorScheme.HTML_TAG)
                        inTag = false
                        index++
                    }
                    inTag && current.isLetter() -> {
                        val end = wordEnd(line, index)
                        val next = nextMeaningful(line, end)
                        val colorId = if (next == '=') {
                            EditorColorScheme.ATTRIBUTE_NAME
                        } else {
                            EditorColorScheme.IDENTIFIER_NAME
                        }
                        add(lineIndex, index, line.substring(index, end), colorId)
                        index = end
                    }
                    spec.stringDelims.any { line.startsWith(it, index) } && inTag -> {
                        val delim = spec.stringDelims.first { line.startsWith(it, index) }
                        index = scanXmlAttribute(lineIndex, line, index, delim)
                    }
                    else -> index++
                }
            }
            xmlTagOpen = inTag
        }
    }

    private fun scanXmlAttribute(lineIndex: Int, line: String, start: Int, delim: String): Int {
        var index = start + delim.length
        while (index < line.length) {
            if (line.startsWith(delim, index)) {
                val end = index + delim.length
                add(lineIndex, start, line.substring(start, end), EditorColorScheme.ATTRIBUTE_VALUE)
                return end
            }
            index++
        }
        add(lineIndex, start, line.substring(start), EditorColorScheme.ATTRIBUTE_VALUE)
        return line.length
    }

    private fun highlightMarkdown(ref: ContentReference) {
        for (lineIndex in 0 until ref.lineCount) {
            val line = ref.getLine(lineIndex)
            if (line.startsWith("```")) {
                mdInFence = !mdInFence
                add(lineIndex, 0, line, EditorColorScheme.LITERAL)
                continue
            }
            if (mdInFence) {
                add(lineIndex, 0, line, EditorColorScheme.LITERAL)
                continue
            }
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) {
                add(lineIndex, 0, line, EditorColorScheme.KEYWORD, bold = true)
                continue
            }
            var index = 0
            while (index < line.length) {
                when {
                    line[index] == '`' -> {
                        val end = line.indexOf('`', index + 1)
                        val stop = if (end < 0) line.length else end + INLINE_CODE_CLOSE
                        add(lineIndex, index, line.substring(index, stop), EditorColorScheme.LITERAL)
                        index = stop
                    }
                    line.startsWith("**", index) -> {
                        val end = line.indexOf("**", index + BOLD_MARKER_LENGTH)
                        val stop = if (end < 0) line.length else end + BOLD_MARKER_LENGTH
                        add(
                            lineIndex,
                            index,
                            line.substring(index, stop),
                            EditorColorScheme.TEXT_NORMAL,
                            bold = true,
                        )
                        index = stop
                    }
                    else -> index++
                }
            }
        }
    }

    private fun tagNameEnd(line: String, start: Int): Int {
        var index = start + 1
        while (index < line.length &&
            (line[index].isLetterOrDigit() || line[index] == '/' ||
                line[index] == '!' || line[index] == '?')
        ) {
            index++
        }
        return index
    }

    private fun wordEnd(line: String, start: Int): Int {
        var index = start
        while (index < line.length && (line[index].isLetterOrDigit() || line[index] == '_')) {
            index++
        }
        return index
    }

    private fun nextMeaningful(line: String, from: Int): Char? {
        for (index in from until line.length) {
            if (!line[index].isWhitespace()) return line[index]
        }
        return null
    }

    companion object {
        private const val MAX_ANALYZE_CHARS = 300_000L
        private const val TRIPLE_QUOTE_LENGTH = 3
        private const val ESCAPE_STEP = 2
        private const val COMMENT_CLOSE_LENGTH = 3
        private const val INLINE_CODE_CLOSE = 1
        private const val BOLD_MARKER_LENGTH = 2
    }
}
