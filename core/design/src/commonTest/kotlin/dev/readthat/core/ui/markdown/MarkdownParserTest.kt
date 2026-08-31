package dev.readthat.core.ui.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownParserTest {
    @Test
    fun `bold italic links and surrounding text retain order and nesting`() {
        val blocks = MarkdownParser.parse(
            "Read ***the [design notes](https://example.com/notes)*** _carefully_.",
        )

        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals("Read ", (paragraph.content[0] as MarkdownInline.Plain).value)
        val strong = paragraph.content[1] as MarkdownInline.Strong
        val emphasis = strong.content.single() as MarkdownInline.Emphasis
        assertEquals("the ", (emphasis.content[0] as MarkdownInline.Plain).value)
        assertEquals(
            MarkdownInline.Link(
                listOf(MarkdownInline.Plain("design notes")),
                "https://example.com/notes",
            ),
            emphasis.content[1],
        )
        assertTrue(paragraph.content[2] is MarkdownInline.Plain)
        assertTrue(paragraph.content[3] is MarkdownInline.Emphasis)
        assertEquals(".", (paragraph.content[4] as MarkdownInline.Plain).value)
    }

    @Test
    fun `nested bullets ordered items indented paragraphs and quotes preserve structure`() {
        val blocks = MarkdownParser.parse(
            "- root\n  - child\n    1. ordered\n      continuation\n\n> quoted",
        )

        assertEquals(6, blocks.size)
        assertEquals(0, (blocks[0] as MarkdownBlock.ListItem).indentLevel)
        assertEquals(1, (blocks[1] as MarkdownBlock.ListItem).indentLevel)
        assertEquals("1.", (blocks[2] as MarkdownBlock.ListItem).marker)
        assertEquals(2, (blocks[2] as MarkdownBlock.ListItem).indentLevel)
        assertEquals(3, (blocks[3] as MarkdownBlock.Paragraph).indentLevel)
        assertTrue(blocks[4] is MarkdownBlock.Blank)
        assertTrue(blocks[5] is MarkdownBlock.Quote)
    }

    @Test
    fun `only http links become interactive and balanced url parentheses are retained`() {
        val safe = MarkdownParser.parseInline("[docs](https://example.com/a_(b)) then https://example.com/path.)")
        val unsafe = MarkdownParser.parseInline("[tap](javascript:alert(1))")

        assertEquals(
            MarkdownInline.Link(listOf(MarkdownInline.Plain("docs")), "https://example.com/a_(b)"),
            safe[0],
        )
        assertEquals(
            MarkdownInline.Link(listOf(MarkdownInline.Plain("https://example.com/path")), "https://example.com/path"),
            safe[2],
        )
        assertEquals(".)", (safe[3] as MarkdownInline.Plain).value)
        assertFalse(unsafe.any { it is MarkdownInline.Link })
    }

    @Test
    fun `underscores inside identifiers are not treated as emphasis`() {
        val inline = MarkdownParser.parseInline("cache_key_name and _intentional emphasis_")

        assertEquals("cache_key_name and ", (inline[0] as MarkdownInline.Plain).value)
        assertEquals("intentional emphasis", ((inline[1] as MarkdownInline.Emphasis).content.single() as MarkdownInline.Plain).value)
    }

    @Test
    fun `collapsed preview flattens blocks and strips supported markdown`() {
        val preview = markdownPlainText(
            "Read the **design [notes](https://example.com/notes)**.\n\n> Then *ship it*.\n- Carefully",
        )

        assertEquals("Read the design notes. Then ship it. Carefully", preview)
    }
}
