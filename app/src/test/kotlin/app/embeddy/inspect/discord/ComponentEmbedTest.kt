package app.embeddy.inspect.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentEmbedTest {

    private fun kinds(raw: String) = validateComponentEmbed(raw).issues.map { it.kind }

    @Test
    fun `json reader keeps key order, integers and escapes`() {
        val doc = parseJson("""{"b":1,"a":[2.5,true,null,"xA\n"],"c":{"d":-3e2}}""") as Json.Obj
        assertEquals(listOf("b", "a", "c"), doc.keys.toList())
        assertEquals(Json.Num(1.0, integral = true), doc["b"])
        val arr = doc["a"] as Json.Arr
        assertEquals(Json.Num(2.5, integral = false), arr.items[0])
        assertEquals(Json.Str("xA\n"), arr.items[3])
        assertEquals(-300L, ((doc["c"] as Json.Obj)["d"] as Json.Num).asLong)
    }

    @Test
    fun `json reader rejects malformed input with a position`() {
        for (bad in listOf("{", "{\"a\":}", "[1,]", "01", "\"\u0001\"", "{} x", "tru")) {
            try {
                parseJson(bad)
                throw AssertionError("parsed: $bad")
            } catch (e: JsonParseException) {
                assertTrue(e.offset >= 0)
            }
        }
    }

    @Test
    fun `the showcase payload sits exactly at every limit, like the web validator says`() {
        val raw = javaClass.getResource("/discord/showcase.json")!!.readText()
        val v = validateComponentEmbed(raw)
        assertEquals(emptyList<EmbedIssue>(), v.issues)
        assertTrue(v.ok)
        assertEquals(ComponentEmbedRules.MAX_BYTES, v.bytes)
        assertEquals(ComponentEmbedRules.MAX_COMPONENTS, v.componentCount)
        assertEquals(ComponentEmbedRules.MAX_GALLERY_ITEMS, v.galleryItemCount)
        assertNotNull(v.root)
    }

    @Test
    fun `structure errors`() {
        assertEquals(listOf(EmbedIssueKind.NOT_JSON), kinds("{"))
        assertEquals(listOf(EmbedIssueKind.NOT_OBJECT), kinds("[]"))
        assertEquals(listOf(EmbedIssueKind.MISSING_COMPONENT), kinds("""{"x":1}""").filter { it != EmbedIssueKind.UNKNOWN_TOP_LEVEL_KEY })
        assertEquals(listOf(EmbedIssueKind.ROOT_NOT_CONTAINER), kinds("""{"component":{"type":10,"content":"x"}}"""))
        assertEquals(
            listOf(EmbedIssueKind.NESTED_CONTAINER, EmbedIssueKind.FILE_UNSUPPORTED, EmbedIssueKind.BUTTON_OUTSIDE_ROW, EmbedIssueKind.UNSUPPORTED_TYPE),
            kinds("""{"component":{"type":17,"components":[{"type":17,"components":[]},{"type":13},{"type":2,"style":5,"url":"https://a.b","label":"x"},{"type":3}]}}"""),
        )
    }

    @Test
    fun `buttons must be link buttons with only the allowed keys`() {
        val k = kinds("""{"component":{"type":17,"components":[{"type":1,"components":[{"type":2,"style":1,"custom_id":"x"}]}]}}""")
        assertTrue(EmbedIssueKind.BUTTON_EXTRA_KEY in k)
        assertTrue(EmbedIssueKind.BUTTON_NOT_LINK in k)
        assertTrue(EmbedIssueKind.BUTTON_NO_URL in k)
        assertTrue(EmbedIssueKind.BUTTON_NO_LABEL_OR_EMOJI in k)
        // An emoji alone is enough
        assertTrue(validateComponentEmbed("""{"component":{"type":17,"components":[{"type":1,"components":[{"type":2,"style":5,"url":"https://a.b","emoji":{"name":"🔥"}}]}]}}""").ok)
    }

    @Test
    fun `media rules and the gallery total across the embed`() {
        val items = (1..6).joinToString(",") { """{"media":{"url":"https://a.b/$it.webp"}}""" }
        val v = validateComponentEmbed("""{"component":{"type":17,"components":[{"type":12,"items":[$items]},{"type":12,"items":[$items]}]}}""")
        assertEquals(12, v.galleryItemCount)
        assertTrue(EmbedIssueKind.TOO_MANY_GALLERY_ITEMS in v.issues.map { it.kind })
        assertFalse(v.ok)
        val media = kinds(
            """{"component":{"type":17,"components":[{"type":9,"components":[{"type":10,"content":"x"}],"accessory":{"type":11,"media":{"url":"attachment://a.png"}}},""" +
                """{"type":9,"components":[{"type":10,"content":"x"}],"accessory":{"type":11,"media":{"url":"https://a.b/v.mp4","width":1}}}]}}""",
        )
        assertTrue(EmbedIssueKind.MEDIA_ATTACHMENT in media)
        assertTrue(EmbedIssueKind.THUMBNAIL_IS_VIDEO in media)
        assertTrue(EmbedIssueKind.MEDIA_EXTRA_KEY in media)
    }

    @Test
    fun `size counts the payload as written, escapes included`() {
        val pad = "\\u0041".repeat(500) // 3,000 bytes of escapes, 500 characters once decoded
        val v = validateComponentEmbed("""{"component":{"type":17,"components":[{"type":10,"content":"$pad"}]}}""")
        assertTrue(v.bytes > ComponentEmbedRules.MAX_BYTES)
        assertEquals(EmbedIssueKind.PAYLOAD_TOO_LARGE, v.issues.first().kind)
    }

    @Test
    fun `accent colour range and component limit`() {
        assertTrue(EmbedIssueKind.BAD_ACCENT_COLOR in kinds("""{"component":{"type":17,"accent_color":16777216,"components":[{"type":14}]}}"""))
        val many = (1..40).joinToString(",") { """{"type":14}""" }
        val v = validateComponentEmbed("""{"component":{"type":17,"components":[$many]}}""")
        assertEquals(41, v.componentCount)
        assertTrue(EmbedIssueKind.TOO_MANY_COMPONENTS in v.issues.map { it.kind })
    }
}
