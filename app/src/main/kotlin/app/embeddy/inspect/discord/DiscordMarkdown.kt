package app.embeddy.inspect.discord

/**
 * Discord-flavoured markdown → a small block/span model, for previewing Text
 * Display components and `og:type=rich` descriptions. The screen turns the
 * model into an AnnotatedString; keeping the parsing here makes it testable on
 * the JVM. Mirrors site/src/lib/inspect/discord-markdown.ts:
 *
 *   blocks — `# / ## / ###` headings, `-# ` subtext, `> ` and `>>> ` quotes,
 *            `- ` / `* ` / `1. ` lists, ``` code fences (```ansi coloured)
 *   inline — ***bold italic***, **bold**, __underline__, *italic*, _italic_,
 *            ~~strike~~, ||spoiler||, `code`, [masked](https://links),
 *            bare http(s) URLs, <t:unix:style> timestamps, backslash escapes
 */

enum class MdBlockKind { PARAGRAPH, H1, H2, H3, SUBTEXT, QUOTE, LIST_ITEM, CODE_BLOCK }

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val spoiler: Boolean = false,
    /**
     * Which `||…||` a spoiler span belongs to, so the screen can reveal one spoiler
     * (all of its styled spans) on tap without revealing the others in the block.
     */
    val spoilerGroup: Int? = null,
    /** http(s) target when the span is a link */
    val link: String? = null,
    /** ANSI foreground / background as 0xRRGGBB (```ansi blocks) */
    val color: Int? = null,
    val background: Int? = null,
    /** `<t:unix:style>` — the screen formats it in the viewer's locale and zone */
    val timestamp: MdTimestamp? = null,
)

data class MdTimestamp(val epochSeconds: Long, val style: Char)

data class MdBlock(
    val kind: MdBlockKind,
    val spans: List<MdSpan>,
    /** List marker ("•" or "1.") for LIST_ITEM */
    val marker: String? = null,
    /** List nesting level (0-based) */
    val indent: Int = 0,
)

/** Discord's ```ansi palette, by SGR code */
val ANSI_FOREGROUND = mapOf(
    30 to 0x4f545c, 31 to 0xdc322f, 32 to 0x859900, 33 to 0xb58900,
    34 to 0x268bd2, 35 to 0xd33682, 36 to 0x2aa198, 37 to 0xffffff,
)
val ANSI_BACKGROUND = mapOf(
    40 to 0x002b36, 41 to 0xcb4b16, 42 to 0x586e75, 43 to 0x657b83,
    44 to 0x839496, 45 to 0x6c71c4, 46 to 0x93a1a1, 47 to 0xfdf6e3,
)

private val FENCE = Regex("```([\\w+-]*)\\n?([\\s\\S]*?)```")
private val ORDERED = Regex("""^(\s*)(\d{1,9})\.\s+(.*)$""")
// TODO: Discord mobile (2026-11-14) drew "1. a\n2. b\n - c" as an ordered list 1–3: a bullet
// indented one space under ordered items continued the numbering instead of nesting. The exact
// indent rule (and whether desktop agrees) is unconfirmed, so it is not modelled yet.
private val BULLET = Regex("""^(\s*)[-*]\s+(.*)$""")

/** Parse a markdown document into blocks. */
fun parseDiscordMarkdown(src: String): List<MdBlock> {
    val text = src.replace("\r\n", "\n").replace('\r', '\n')
    val blocks = mutableListOf<MdBlock>()
    var last = 0
    for (m in FENCE.findAll(text)) {
        blocks += parseLines(text.substring(last, m.range.first).removeSuffix("\n"))
        val lang = m.groupValues[1].lowercase()
        val body = m.groupValues[2].removeSuffix("\n")
        blocks += MdBlock(MdBlockKind.CODE_BLOCK, if (lang == "ansi") parseAnsi(body) else listOf(MdSpan(body, code = true)))
        last = m.range.last + 1
    }
    blocks += parseLines(text.substring(last).removePrefix("\n"))
    return blocks
}

private fun parseLines(chunk: String): List<MdBlock> {
    if (chunk.isEmpty()) return emptyList()
    val out = mutableListOf<MdBlock>()
    val lines = chunk.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // >>> quotes everything to the end of the chunk
            line.startsWith(">>> ") -> {
                val rest = (listOf(line.removePrefix(">>> ")) + lines.drop(i + 1)).joinToString("\n")
                out += MdBlock(MdBlockKind.QUOTE, parseInline(rest))
                return out
            }
            line.startsWith("> ") -> out += MdBlock(MdBlockKind.QUOTE, parseInline(line.removePrefix("> ")))
            line.startsWith("### ") -> out += MdBlock(MdBlockKind.H3, parseInline(line.removePrefix("### ")))
            line.startsWith("## ") -> out += MdBlock(MdBlockKind.H2, parseInline(line.removePrefix("## ")))
            line.startsWith("# ") -> out += MdBlock(MdBlockKind.H1, parseInline(line.removePrefix("# ")))
            line.startsWith("-# ") -> out += MdBlock(MdBlockKind.SUBTEXT, parseInline(line.removePrefix("-# ")))
            ORDERED.matches(line) -> {
                val m = ORDERED.find(line)!!
                out += MdBlock(MdBlockKind.LIST_ITEM, parseInline(m.groupValues[3]), "${m.groupValues[2]}.", m.groupValues[1].length / 2)
            }
            BULLET.matches(line) -> {
                val m = BULLET.find(line)!!
                out += MdBlock(MdBlockKind.LIST_ITEM, parseInline(m.groupValues[2]), "•", m.groupValues[1].length / 2)
            }
            else -> {
                // Consecutive plain lines form one paragraph with line breaks
                val para = StringBuilder(line)
                while (i + 1 < lines.size && !startsBlock(lines[i + 1])) {
                    i++
                    para.append('\n').append(lines[i])
                }
                out += MdBlock(MdBlockKind.PARAGRAPH, parseInline(para.toString()))
            }
        }
        i++
    }
    return out
}

private fun startsBlock(line: String) =
    line.startsWith("> ") || line.startsWith(">>> ") || line.startsWith("# ") || line.startsWith("## ") ||
        line.startsWith("### ") || line.startsWith("-# ") || ORDERED.matches(line) || BULLET.matches(line)

/** Inline rules in priority order; group 1 is the inner text where one exists */
private enum class Rule(val regex: Regex) {
    ESCAPE(Regex("""\\([*_~`|\\>#\[\]()<:-])""")),
    CODE(Regex("`([^`]+)`")),
    TIMESTAMP(Regex("""<t:(-?\d{1,13})(?::([tTdDfFR]))?>""")),
    MASKED_LINK(Regex("""\[([^\]\n]+)]\(<?(https?://[^\s)>]+)>?\)""")),
    ANGLE_URL(Regex("""<(https?://[^\s>]+)>""")),
    BARE_URL(Regex("""https?://[^\s<]+[^\s<.,:;"')\]]""")),
    BOLD_ITALIC(Regex("""\*\*\*(.+?)\*\*\*""")),
    // The lookahead lets **bold *italic*** close on the last pair of stars
    BOLD(Regex("""\*\*(.+?)\*\*(?!\*)""")),
    UNDERLINE(Regex("__(.+?)__(?!_)")),
    ITALIC_STAR(Regex("""\*([^*\s](?:.*?[^*\s])?)\*""")),
    ITALIC_UNDER(Regex("""\b_(.+?)_\b""")),
    STRIKE(Regex("~~(.+?)~~")),
    SPOILER(Regex("""\|\|(.+?)\|\|""")),
}

/** Parse inline formatting into spans. */
fun parseInline(text: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    inline(text, MdSpan(""), out)
    // Merge neighbours with identical style so the screen builds fewer ranges
    return out.fold(mutableListOf()) { acc, span ->
        val prev = acc.lastOrNull()
        if (prev != null && prev.copy(text = "") == span.copy(text = "") && span.timestamp == null) {
            acc[acc.lastIndex] = prev.copy(text = prev.text + span.text)
        } else {
            acc += span
        }
        acc
    }
}

private fun inline(text: String, style: MdSpan, out: MutableList<MdSpan>) {
    var rest = text
    while (rest.isNotEmpty()) {
        // Earliest match wins; ties go to the rule listed first
        var best: Pair<Rule, MatchResult>? = null
        for (rule in Rule.entries) {
            if (style.code && rule != Rule.ESCAPE) continue
            val m = rule.regex.find(rest) ?: continue
            if (best == null || m.range.first < best.second.range.first) best = rule to m
        }
        if (best == null) {
            out += style.copy(text = rest)
            return
        }
        val (rule, m) = best
        if (m.range.first > 0) out += style.copy(text = rest.substring(0, m.range.first))
        val inner = m.groupValues.getOrElse(1) { "" }
        when (rule) {
            Rule.ESCAPE -> out += style.copy(text = inner)
            Rule.CODE -> out += style.copy(text = inner, code = true)
            Rule.TIMESTAMP -> out += style.copy(
                text = m.value,
                timestamp = MdTimestamp(inner.toLong(), m.groupValues[2].firstOrNull() ?: 'f'),
            )
            Rule.MASKED_LINK -> inline(inner, style.copy(link = m.groupValues[2]), out)
            Rule.ANGLE_URL -> out += style.copy(text = inner, link = inner)
            Rule.BARE_URL -> out += style.copy(text = m.value, link = m.value)
            Rule.BOLD_ITALIC -> inline(inner, style.copy(bold = true, italic = true), out)
            Rule.BOLD -> inline(inner, style.copy(bold = true), out)
            Rule.UNDERLINE -> inline(inner, style.copy(underline = true), out)
            Rule.ITALIC_STAR, Rule.ITALIC_UNDER -> inline(inner, style.copy(italic = true), out)
            Rule.STRIKE -> inline(inner, style.copy(strike = true), out)
            // out.size is where this spoiler's spans start, so it is unique within the block
            Rule.SPOILER -> inline(inner, style.copy(spoiler = true, spoilerGroup = out.size), out)
        }
        rest = rest.substring(m.range.last + 1)
    }
}

private val SGR = Regex("\u001b\\[([\\d;]*)m")

/** Body of an ```ansi block: SGR escapes become span colours and are removed */
fun parseAnsi(code: String): List<MdSpan> {
    var bold = false
    var underline = false
    var fg: Int? = null
    var bg: Int? = null
    val out = mutableListOf<MdSpan>()
    var last = 0
    fun emit(t: String) {
        // A stray ESC without a complete sequence is not printed by Discord either
        val clean = t.replace("\u001b", "")
        if (clean.isNotEmpty()) out += MdSpan(clean, bold = bold, underline = underline, code = true, color = fg, background = bg)
    }
    for (m in SGR.findAll(code)) {
        emit(code.substring(last, m.range.first))
        last = m.range.last + 1
        for (part in m.groupValues[1].ifEmpty { "0" }.split(';')) {
            when (val n = part.ifEmpty { "0" }.toIntOrNull() ?: continue) {
                0 -> {
                    bold = false
                    underline = false
                    fg = null
                    bg = null
                }
                1 -> bold = true
                4 -> underline = true
                in ANSI_FOREGROUND -> fg = ANSI_FOREGROUND[n]
                in ANSI_BACKGROUND -> bg = ANSI_BACKGROUND[n]
            }
        }
    }
    emit(code.substring(last))
    return out
}
