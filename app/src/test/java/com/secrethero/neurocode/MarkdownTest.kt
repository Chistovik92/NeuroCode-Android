package com.secrethero.neurocode

import com.secrethero.neurocode.ui.components.Markdown
import com.secrethero.neurocode.ui.components.MdBlock
import com.secrethero.neurocode.ui.components.MdSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun boldItalicAndCode() {
        val spans = Markdown.inline("a **b** *c* `d`")
        assertEquals(
            listOf(
                MdSpan("a "),
                MdSpan("b", bold = true),
                MdSpan(" "),
                MdSpan("c", italic = true),
                MdSpan(" "),
                MdSpan("d", code = true),
            ),
            spans,
        )
    }

    @Test
    fun snakeCaseAndMathStarsStayPlain() {
        assertEquals(listOf(MdSpan("my_var_name")), Markdown.inline("my_var_name"))
        assertEquals(listOf(MdSpan("2 * 3 * 4")), Markdown.inline("2 * 3 * 4"))
    }

    @Test
    fun unclosedMarkersDoNotCrash() {
        val spans = Markdown.inline("**жирный без конца")
        assertEquals(listOf(MdSpan("жирный без конца", bold = true)), spans)
        assertEquals(listOf(MdSpan("`код")), Markdown.inline("`код"))
    }

    @Test
    fun markdownLinkAndBareUrl() {
        val link = Markdown.inline("см. [сайт](https://example.com/a)")
        assertEquals(MdSpan("сайт", url = "https://example.com/a"), link.last())
        val bare = Markdown.inline("ссылка: https://example.com/x.")
        assertEquals("https://example.com/x", bare.first { it.url != null }.url)
        assertEquals(".", bare.last().text)
    }

    @Test
    fun javascriptLinksAreNotClickable() {
        val spans = Markdown.inline("[x](javascript:alert(1))")
        assertTrue(spans.all { it.url == null })
    }

    @Test
    fun headingsListsRuleAndQuote() {
        val blocks = Markdown.parse("# Заголовок\n\n- один\n- два\n1. первый\n\n---\n> цитата")
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Заголовок"))), blocks[0])
        assertEquals(MdBlock.ListItem(null, 0, listOf(MdSpan("один"))), blocks[1])
        assertEquals(MdBlock.ListItem(null, 0, listOf(MdSpan("два"))), blocks[2])
        assertEquals(MdBlock.ListItem(1, 0, listOf(MdSpan("первый"))), blocks[3])
        assertEquals(MdBlock.Rule, blocks[4])
        assertEquals(MdBlock.Quote(listOf(MdSpan("цитата"))), blocks[5])
    }

    @Test
    fun codeFenceKeepsContentAndLanguage() {
        val blocks = Markdown.parse("текст\n```kotlin\nval a = 1 * 2\n# не заголовок\n```\nконец")
        assertEquals(MdBlock.Code("kotlin", "val a = 1 * 2\n# не заголовок"), blocks[1])
        assertEquals(3, blocks.size)
    }

    @Test
    fun unclosedFenceIsShownAsCode() {
        val blocks = Markdown.parse("```\nпервая\nвторая")
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "первая\nвторая")), blocks)
    }

    @Test
    fun listContinuationJoinsPreviousItem() {
        val blocks = Markdown.parse("- Управление проектами:\n  импорт и экспорт")
        assertEquals(1, blocks.size)
        val item = blocks[0] as MdBlock.ListItem
        assertEquals("Управление проектами: импорт и экспорт", item.spans.joinToString("") { it.text })
    }

    @Test
    fun horizontalRuleVariants() {
        assertEquals(listOf<MdBlock>(MdBlock.Rule), Markdown.parse("***"))
        assertEquals(listOf<MdBlock>(MdBlock.Rule), Markdown.parse("- - -"))
    }
}
