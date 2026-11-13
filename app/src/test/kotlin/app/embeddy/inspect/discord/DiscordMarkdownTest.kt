package app.embeddy.inspect.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordMarkdownTest {

    @Test
    fun `block kinds`() {
        val blocks = parseDiscordMarkdown("# Big\n## Mid\n### Small\n-# sub\n> quote\n- a\n  - b\n2. two\nplain\nmore")
        assertEquals(
            listOf(
                MdBlockKind.H1, MdBlockKind.H2, MdBlockKind.H3, MdBlockKind.SUBTEXT, MdBlockKind.QUOTE,
                MdBlockKind.LIST_ITEM, MdBlockKind.LIST_ITEM, MdBlockKind.LIST_ITEM, MdBlockKind.PARAGRAPH,
            ),
            blocks.map { it.kind },
        )
        assertEquals(1, blocks[6].indent)
        assertEquals("2.", blocks[7].marker)
        assertEquals("plain\nmore", blocks[8].spans.joinToString("") { it.text })
    }

    @Test
    fun `inline styles nest and links are http only`() {
        val spans = parseInline("**bold *both*** __u__ ~~s~~ `c **x**` ||sp|| [site](https://e.com) [bad](javascript:x)")
        val bold = spans.first { it.text == "bold " }
        assertTrue(bold.bold)
        assertTrue(spans.first { it.text == "both" }.let { it.bold && it.italic })
        assertTrue(spans.first { it.text == "u" }.underline)
        assertTrue(spans.first { it.text == "s" }.strike)
        assertTrue(spans.first { it.text == "c **x**" }.code)
        assertTrue(spans.first { it.text == "sp" }.spoiler)
        assertEquals("https://e.com", spans.first { it.text == "site" }.link)
        assertNull(spans.firstOrNull { it.text.contains("bad") }?.link)
    }

    @Test
    fun `escapes and timestamps`() {
        val spans = parseInline("\\*not italic\\* <t:1700000000:R>")
        assertEquals("*not italic* ", spans.first().text)
        assertEquals(MdTimestamp(1_700_000_000, 'R'), spans.last().timestamp)
    }

    @Test
    fun `ansi fences are coloured and other fences are plain code`() {
        val ansi = parseDiscordMarkdown("```ansi\n\u001b[1;35m3000\u001b[0m bytes\n```").single()
        assertEquals(MdBlockKind.CODE_BLOCK, ansi.kind)
        assertEquals(MdSpan("3000", bold = true, code = true, color = 0xd33682), ansi.spans[0])
        assertEquals(MdSpan(" bytes", code = true), ansi.spans[1])
        val js = parseDiscordMarkdown("```js\nlet a = 1\n```").single()
        assertEquals(listOf(MdSpan("let a = 1", code = true)), js.spans)
    }
}
