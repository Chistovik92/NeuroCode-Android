package com.secrethero.neurocode

import com.secrethero.neurocode.ui.components.Markdown
import com.secrethero.neurocode.ui.components.MdBlock
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Случайные и крайние входы: разбор ответа модели не должен бросать исключения и не должен тормозить. */
class MarkdownFuzzTest {
    private val tokens = listOf(
        "**", "*", "_", "__", "`", "```", "```kotlin", "[", "]", "(", ")", "](", "http://", "https://a.b/c",
        "\\", "#", "## ", "- ", "* ", "1. ", "12) ", "> ", "---", "***", "   ", "\t", "\n", "\n\n", "\r\n",
        "текст", "word", "snake_case", "😀", "\u0000", "|", "<", ">", "&", "~", "9999999999. ",
    )

    @Test
    fun randomMarkdownNeverThrows() {
        val random = Random(42)
        repeat(3_000) {
            val text = buildString { repeat(random.nextInt(40)) { append(tokens[random.nextInt(tokens.size)]) } }
            Markdown.parse(text)
        }
    }

    @Test
    fun edgeCasesNeverThrow() {
        listOf(
            "", "\n", "```", "```\n", "- ", "1.", "[", "[]", "[](", "[a](", "[a](http", "[a](http://", "**", "*",
            "_ _", "`", "``", "\\", "> ", ">", "#", "# ", "   - a\n b", "99999999999999999999. a", "- \n- \n",
            "http://", "https://" + "a".repeat(10_000), "[".repeat(5_000), "*".repeat(10_001), "`".repeat(5_001),
        ).forEach { Markdown.parse(it) }
    }

    @Test
    fun horizontalRulesAreRecognizedAsBefore() {
        listOf("---", "***", "___", "* * *", "- - -", "  ---", "-- -", "----------").forEach {
            assertTrue("«$it» должна быть линией", Markdown.parse(it) == listOf(MdBlock.Rule))
        }
        listOf("--", "---a", "    ---", "a---", "-*-").forEach {
            assertTrue("«$it» не линия", MdBlock.Rule !in Markdown.parse(it))
        }
    }

    @Test
    fun degenerateRepeatedOutputDoesNotCrashAndIsSplit() {
        val dashes = Markdown.parse("-".repeat(300_000))
        val equals = Markdown.parse("=".repeat(300_000))
        val mixed = Markdown.parse("текст ".repeat(100_000))

        assertTrue(dashes == listOf(MdBlock.Rule))
        listOf(equals, mixed).forEach { blocks ->
            assertTrue(blocks.size > 1)
            blocks.forEach { block ->
                val length = (block as MdBlock.Paragraph).spans.sumOf { it.text.length }
                assertTrue("блок длиной $length", length <= Markdown.MAX_BLOCK_CHARS)
            }
        }
    }

    @Test
    fun hugeCodeBlockIsSplitIntoSafeParts() {
        val manyLines = "```\n" + (1..2_000).joinToString("\n") { "line $it" } + "\n```"
        val longLine = "```\n" + "x".repeat(50_000) + "\n```"

        assertTrue(Markdown.parse(manyLines).size > 1)
        listOf(manyLines, longLine).forEach { source ->
            val blocks = Markdown.parse(source)
            blocks.forEach { block ->
                val code = (block as MdBlock.Code).code.split('\n')
                assertTrue(code.size <= Markdown.MAX_CODE_LINES)
                assertTrue(code.all { it.length <= Markdown.MAX_CODE_LINE_CHARS })
            }
        }
    }

    @Test
    fun ordinaryAnswersAreNotSplit() {
        val blocks = Markdown.parse("Привет, **мир**!\n\n```kotlin\nfun main() {}\n```")

        assertTrue(blocks.size == 2)
    }

    @Test
    fun manyBracketsAndLinksAreParsedQuickly() {
        val started = System.nanoTime()

        Markdown.parse("[".repeat(100_000))
        Markdown.parse("[a](".repeat(50_000))
        Markdown.parse("[a](https://e.x) ".repeat(20_000))

        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("разбор скобок занял $millis мс", millis < 3_000)
    }

    @Test
    fun longAnswerIsParsedQuickly() {
        val paragraph = "слово *курсив* и **жирный** `код` [ссылка](https://example.com) ".repeat(2_000)
        val started = System.nanoTime()

        Markdown.parse(paragraph)

        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("разбор длинного абзаца занял $millis мс", millis < 1_500)
    }

    @Test
    fun hugeSingleLineIsParsedQuickly() {
        val line = "a".repeat(300_000)
        val started = System.nanoTime()

        Markdown.parse(line)

        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("разбор длинной строки занял $millis мс", millis < 1_500)
    }
}
