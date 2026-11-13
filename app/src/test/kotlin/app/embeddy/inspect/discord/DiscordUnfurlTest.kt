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
    private fun analyze(vararg tags: MetaTag, title: String = "", embeds: List<ComponentEmbedSource> = emptyList(), oembed: OEmbedSource? = null) =
        analyzeUnfurl(PageFacts(url, title, tags.toList(), embeds, oembed))

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
}
