package app.embeddy.inspect.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordUnfurlTest {

    private val url = "https://example.com/post"
    private fun og(key: String, value: String) = MetaTag(key, value, "property")
    private fun name(key: String, value: String) = MetaTag(key, value, "name")
    private fun analyze(
        vararg tags: MetaTag,
        title: String = "",
        embeds: List<ComponentEmbedSource> = emptyList(),
        oembed: OEmbedSource? = null,
        jsonLd: List<String> = emptyList(),
    ) = analyzeUnfurl(PageFacts(url, title, tags.toList(), embeds, oembed, jsonLd))

    /** A top-level VideoObject; [extra] is spliced in as raw JSON members */
    private fun ld(extra: String = "", context: String? = "https://schema.org", name: Boolean = true) =
        "{" + (context?.let { "\"@context\":\"$it\"," } ?: "") +
            """"@type":"VideoObject",""" + (if (name) """"name":"LD name",""" else "") +
            """"description":"LD description","thumbnailUrl":"https://e.com/1.webp","uploadDate":"2026-01-02T03:04:05Z"$extra}"""

    @Test
    fun `a VideoObject is read only with @context https schema org exactly`() {
        for (context in listOf("http://schema.org", "https://schema.org/", null)) {
            val r = analyze(name("description", "PAIR description"), title = "PAIR title", jsonLd = listOf(ld(context = context)))
            assertEquals("PAIR title", r.preview.title?.text)
            assertNull(r.preview.timestamp)
            assertTrue(CheckKind.JSONLD_CONTEXT_IGNORED in r.kinds())
        }
    }

    @Test
    fun `JSON-LD uploadDate beats og pubdate`() {
        val r = analyze(og("og:title", "t"), og("og:pubdate", "2025-05-06T07:08:09Z"), jsonLd = listOf(ld()))
        assertEquals("2026-01-02T03:04:05Z", r.preview.timestamp)
        assertEquals(listOf("JSON-LD uploadDate"), r.checks.single { it.kind == CheckKind.TIMESTAMP }.args)
    }

    @Test
    fun `a JSON-LD name beats the oEmbed title`() {
        val oembed = OEmbedSource("https://e.com/oembed", """{"type":"link","title":"OEMBED title"}""", null, 1)
        val r = analyze(oembed = oembed, jsonLd = listOf(ld()))
        assertEquals("LD name", r.preview.title?.text)
    }

    @Test
    fun `a VideoObject without a name leaves the title empty instead of using title`() {
        val r = analyze(name("description", "PAIR description"), title = "PAIR title", jsonLd = listOf(ld(name = false)))
        assertNull(r.preview.title)
        assertEquals("LD description", r.preview.description?.text)
    }

    @Test
    fun `JSON-LD name and description fill empty text and beat the title pair`() {
        val r = analyze(name("description", "PAIR description"), title = "PAIR title", jsonLd = listOf(ld()))
        assertEquals("LD name", r.preview.title?.text)
        assertEquals("LD description", r.preview.description?.text)
        assertEquals("https://e.com/1.webp", r.preview.images.first().url)
        assertEquals("2026-01-02T03:04:05Z", r.preview.timestamp)
        assertTrue(CheckKind.JSONLD_VIDEO_OBJECT in r.kinds())
        assertFalse(CheckKind.TITLE_DESCRIPTION_PAIR_USED in r.kinds())
    }

    @Test
    fun `Open Graph text and image beat JSON-LD, the upload date still applies`() {
        val r = analyze(
            og("og:title", "t"), og("og:description", "d"), og("og:image", "https://e.com/0.webp"),
            jsonLd = listOf(ld()),
        )
        assertEquals("t", r.preview.title?.text)
        assertEquals("d", r.preview.description?.text)
        assertEquals("https://e.com/0.webp", r.preview.images.first().url)
        assertEquals("2026-01-02T03:04:05Z", r.preview.timestamp)
    }

    @Test
    fun `a top-level JSON-LD VideoObject supplies the video`() {
        val r = analyze(
            og("og:type", "video"), og("og:title", "t"),
            jsonLd = listOf(ld(""","embedUrl":"https://e.com/v.webm","thumbnailUrl":["https://e.com/t.jpg"]""")),
        )
        assertEquals(PreviewLayout.VIDEO, r.preview.layout)
        assertEquals("https://e.com/v.webm", r.preview.video?.url)
        assertEquals("JSON-LD embedUrl", r.preview.video?.key)
        assertEquals("https://e.com/t.jpg", r.preview.images.first().url)
    }

    @Test
    fun `JSON-LD in an array or graph is flagged and not read, bad JSON is flagged`() {
        val r = analyze(jsonLd = listOf("[${ld()}]", """{"@graph":[${ld()}]}""", "{nope"))
        assertNull(r.preview.title)
        assertEquals(2, r.kinds().count { it == CheckKind.JSONLD_NOT_TOP_LEVEL })
        assertTrue(CheckKind.JSONLD_INVALID in r.kinds())
        assertFalse(CheckKind.JSONLD_VIDEO_OBJECT in r.kinds())
    }

    private fun DiscordReport.kinds() = checks.map { it.kind }

    @Test
    fun `first non-empty title wins, layout tags last wins`() {
        val r = analyze(
            og("og:title", ""),
            og("og:title", "First"),
            og("og:title", "Second"),
            name("twitter:card", "summary"),
            name("twitter:card", "summary_large_image"),
            og("og:image", "https://example.com/a.png"),
            og("og:image:width", "1200"),
            og("og:image:height", "630"),
        )
        assertEquals("First", r.preview.title?.text)
        assertEquals(PreviewLayout.LARGE_IMAGE, r.preview.layout)
        assertEquals(1200, r.preview.images.single().width)
    }

    @Test
    fun `title and meta description are used only as a pair`() {
        val pair = analyze(name("description", "Desc"), title = "Page")
        assertEquals("Page", pair.preview.title?.text)
        assertTrue(CheckKind.TITLE_DESCRIPTION_PAIR_USED in pair.kinds())
        val alone = analyze(title = "Page")
        assertEquals(PreviewLayout.NONE, alone.preview.layout)
        assertTrue(CheckKind.TITLE_DESCRIPTION_PAIR_INCOMPLETE in alone.kinds())
    }

    @Test
    fun `trimming respects bytes and code points`() {
        val t = trimForDiscord("é".repeat(40), DiscordLimits.TITLE_BYTES, DiscordLimits.TITLE_LINES)
        assertTrue(t.trimmed)
        assertEquals(80, t.bytes)
        assertTrue(utf8Length(t.text) <= DiscordLimits.TITLE_BYTES)
        assertTrue(t.text.endsWith("…"))
        assertFalse(trimForDiscord("short", 70, 3).trimmed)
    }

    @Test
    fun `relative image URLs must start with a slash, and size tags attach to plain og image`() {
        val r = analyze(
            og("og:title", "T"),
            og("og:image:width", "10"),
            og("og:image", "img/a.png"),
            og("og:image", "/b.png"),
            og("og:image:height", "20"),
        )
        assertTrue(CheckKind.RELATIVE_IMAGE in r.kinds())
        assertTrue(CheckKind.ORPHAN_IMAGE_META in r.kinds())
        assertEquals("https://example.com/b.png", r.preview.images.single().url)
        assertEquals(20, r.preview.images.single().height)
    }

    @Test
    fun `two og images make a gallery and a long site name blocks the preview`() {
        val gallery = analyze(og("og:title", "T"), og("og:image", "https://e.com/1.png"), og("og:image", "https://e.com/2.png"))
        assertEquals(PreviewLayout.GALLERY, gallery.preview.layout)
        assertEquals(2, gallery.preview.images.size)
        val blocked = analyze(og("og:title", "T"), og("og:site_name", "x".repeat(257)))
        assertEquals(PreviewLayout.NONE, blocked.preview.layout)
        assertTrue(CheckKind.NO_PREVIEW_BLOCKED in blocked.kinds())
    }

    @Test
    fun `video needs a video type, https, a file format and an og image`() {
        val ok = analyze(
            og("og:type", "video.other"),
            og("og:title", "T"),
            og("og:image", "https://e.com/p.png"),
            og("og:video", "https://e.com/v.mp4"),
        )
        assertEquals(PreviewLayout.VIDEO, ok.preview.layout)
        val bad = analyze(og("og:title", "T"), og("og:video", "http://e.com/v.avi"))
        assertTrue(bad.kinds().containsAll(listOf(CheckKind.VIDEO_NEEDS_TYPE, CheckKind.VIDEO_NEEDS_HTTPS, CheckKind.VIDEO_NEEDS_IMAGE, CheckKind.VIDEO_EXTENSION_UNSUPPORTED)))
        val player = analyze(og("og:title", "T"), og("og:video", "https://e.com/embed"), og("og:video:type", "text/html"))
        assertTrue(CheckKind.VIDEO_PLAYER_PAGE in player.kinds())
    }

    @Test
    fun `theme colour and dates follow the Discord formats`() {
        val r = analyze(og("og:title", "T"), name("theme-color", "red"), name("theme-color", "#5865F2"), og("og:pubdate", "2026-09-15T12:00:00Z"))
        assertEquals(0x5865F2, r.preview.color)
        assertEquals("2026-09-15T12:00:00Z", r.preview.timestamp)
        val bad = analyze(og("og:title", "T"), name("theme-color", "#abc"), og("og:pubdate", "2026-09-15"))
        assertNull(bad.preview.color)
        assertTrue(bad.kinds().containsAll(listOf(CheckKind.THEME_COLOR_IGNORED, CheckKind.DATE_IGNORED)))
    }

    @Test
    fun `oEmbed supplies the author and overrides the site name`() {
        val r = analyze(
            og("og:title", "T"),
            og("og:site_name", "Site"),
            oembed = OEmbedSource("https://e.com/oembed", """{"author_name":"Ann","provider_name":"Prov","html":"<iframe>"}""", null, 1),
        )
        assertEquals("Ann", r.preview.authorName)
        assertEquals("Prov", r.preview.siteName)
        assertTrue(CheckKind.OEMBED_IGNORED_FIELDS in r.kinds())
    }

    @Test
    fun `a valid inline component embed in head is active, and placement and type are enforced`() {
        val raw = """{"component":{"type":17,"components":[{"type":10,"content":"hi"}]}}"""
        val good = ComponentEmbedSource(EmbedSourceKind.INLINE, "application/json", raw, utf8Length(raw), inHead = true)
        val r = analyze(og("og:title", "T"), embeds = listOf(good))
        assertEquals(good, r.activeComponentEmbed?.element)
        assertTrue(CheckKind.COMPONENT_VALID in r.kinds())
        assertTrue(CheckKind.OG_FALLBACK_INCOMPLETE in r.kinds())

        val misplaced = good.copy(inHead = false, type = "text/json")
        val bad = analyze(og("og:title", "T"), embeds = listOf(misplaced))
        assertNull(bad.activeComponentEmbed)
        assertTrue(bad.kinds().containsAll(listOf(CheckKind.ELEMENT_NOT_IN_HEAD, CheckKind.ELEMENT_BAD_TYPE)))
    }

    @Test
    fun `linked embeds must stay on the same site as the page`() {
        assertTrue(isLinkedEmbedHostAllowed("https://cdn.example.com/e.json", url))
        assertTrue(isLinkedEmbedHostAllowed("https://example.com/e.json", "https://www.example.com/"))
        assertFalse(isLinkedEmbedHostAllowed("https://evil.com/e.json", url))
        assertFalse(isLinkedEmbedHostAllowed("/e.json", url))
        assertFalse(isLinkedEmbedHostAllowed("https://a.example.com/e.json", "https://b.example.com/"))
    }

    // ── Precedence checked in Discord's Embed Debugger (2026-11-15), mirroring the web tests ──

    private val ogBase = arrayOf(
        og("og:title", "t"),
        og("og:description", "d"),
        og("og:image", "https://example.com/0.webp"),
    )
    private val videoTags = arrayOf(
        og("og:video", "https://example.com/v.mp4"),
        og("og:video:type", "video/mp4"),
        og("og:video:width", "640"),
        og("og:video:height", "360"),
    )
    private fun layoutOf(vararg extra: MetaTag) = analyze(*ogBase, *extra).preview.layout
    private fun card(value: String) = name("twitter:card", value)
    private fun type(value: String) = og("og:type", value)

    @Test
    fun `the last layout-changing og type or twitter card wins across both keys`() {
        assertEquals(PreviewLayout.LARGE_IMAGE, layoutOf(card("summary_large_image"), card("summary")))
        assertEquals(PreviewLayout.PHOTO, layoutOf(card("summary_large_image"), card("photo")))
        assertEquals(PreviewLayout.LARGE_IMAGE, layoutOf(card("photo"), card("summary_large_image")))
        assertEquals(PreviewLayout.LARGE_IMAGE, layoutOf(type("rich"), card("summary_large_image")))
        assertEquals(PreviewLayout.RICH, layoutOf(card("summary_large_image"), type("rich")))
        assertEquals(PreviewLayout.PHOTO, layoutOf(type("rich"), card("photo")))
        assertEquals(PreviewLayout.RICH, layoutOf(card("photo"), type("rich")))
        assertEquals(PreviewLayout.RICH, layoutOf(type("rich"), type("website")))
        val conflict = analyze(*ogBase, type("rich"), card("summary_large_image"))
        val note = conflict.checks.single { it.kind == CheckKind.LAYOUT_TAGS_CONFLICT }
        assertEquals(listOf("2", "twitter:card=\"summary_large_image\""), note.args)
    }

    @Test
    fun `video plays only when a video or rich signal is the last layout tag`() {
        assertEquals(PreviewLayout.LARGE_IMAGE, layoutOf(*videoTags, type("video.other"), card("summary_large_image")))
        assertEquals(PreviewLayout.VIDEO, layoutOf(*videoTags, card("summary_large_image"), type("video.other")))
        assertEquals(PreviewLayout.VIDEO, layoutOf(*videoTags, card("player")))
        assertEquals(PreviewLayout.VIDEO, layoutOf(*videoTags, type("rich")))
        assertEquals(PreviewLayout.THUMBNAIL, layoutOf(*videoTags, card("summary")))
        val photo = analyze(*ogBase, *videoTags, type("video.other"), card("photo"))
        assertEquals(PreviewLayout.PHOTO, photo.preview.layout)
        assertTrue(CheckKind.VIDEO_LAYOUT_OVERRIDDEN in photo.kinds())
        assertFalse(CheckKind.VIDEO_LAYOUT_NO_FILE in analyze(*ogBase, type("rich")).kinds())
    }

    @Test
    fun `two og images are a gallery even with a photo card`() {
        assertEquals(PreviewLayout.GALLERY, layoutOf(og("og:image", "https://example.com/1.webp"), card("photo")))
    }

    private fun payload(text: String) = """{"component":{"type":17,"components":[{"type":10,"content":"$text"}]}}"""
    private fun inline(text: String, type: String = "application/json", inHead: Boolean = true) =
        ComponentEmbedSource(EmbedSourceKind.INLINE, type, payload(text), utf8Length(payload(text)), inHead = inHead)
    private fun linked(text: String) = ComponentEmbedSource(
        EmbedSourceKind.LINKED, "application/json", payload(text), utf8Length(payload(text)),
        href = "https://example.com/e.json", hostAllowed = true,
    )
    private fun activeOf(vararg embeds: ComponentEmbedSource) = analyze(*ogBase, embeds = embeds.toList()).activeComponentEmbed?.element

    @Test
    fun `an inline script beats a linked one in either order`() {
        val i = inline("INLINE")
        val l = linked("LINKED")
        assertEquals(i, activeOf(i, l))
        assertEquals(i, activeOf(l, i))
    }

    @Test
    fun `the first inline script wins and a failing first one is not skipped`() {
        val first = inline("FIRST")
        assertEquals(first, activeOf(first, inline("SECOND")))
        val badRaw = """{"component":{"type":17,"components":[{"type":99}]}}"""
        val bad = ComponentEmbedSource(EmbedSourceKind.INLINE, "application/json", badRaw, utf8Length(badRaw), inHead = true)
        val r = analyze(*ogBase, embeds = listOf(bad, inline("VALID")))
        assertNull(r.activeComponentEmbed)
        assertEquals(bad, r.chosenComponentEmbed?.element)
        assertEquals(PreviewLayout.THUMBNAIL, r.preview.layout)
        assertTrue(CheckKind.COMPONENT_IGNORED in r.kinds())
    }

    @Test
    fun `a script with another type is not a candidate, and a script in body still renders`() {
        val l = linked("LINKED")
        assertEquals(l, activeOf(inline("BADTYPE", type = "text/plain"), l))
        val body = inline("BODY", inHead = false)
        val r = analyze(*ogBase, embeds = listOf(body))
        assertEquals(body, r.activeComponentEmbed?.element)
        assertEquals(CheckStatus.WARN, r.checks.single { it.kind == CheckKind.ELEMENT_NOT_IN_HEAD }.status)
    }
}
