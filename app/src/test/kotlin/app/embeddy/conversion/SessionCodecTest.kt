package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trips of the SavedStateHandle session format (M10). */
class SessionCodecTest {

    private val clip = SourceClip(
        uri = "content://media/external/video/42?x=1|2%3",
        fileName = "weird|name%.mp4",
        fileSize = 12_345_678,
        durationMs = 61_000,
        width = 1080,
        height = 1920,
        hasAudio = true,
    )

    @Test
    fun `session with segments and volumes round-trips exactly`() {
        val session = ConvertSession(
            SourceMedia.single(clip),
            EditSpec(
                trimStartMs = 100,
                trimEndMs = 900,
                segments = listOf(TrimSegment(0, 1_000, 0.25f), TrimSegment(2_000, 3_000, 1.75f)),
            ),
        )
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun `separator, percent and newline characters in names survive`() {
        val tricky = clip.copy(fileName = "a|b%7Cc\nd\re.mp4")
        val session = ConvertSession(SourceMedia.single(tricky), EditSpec())
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test
    fun `unknown keys from a newer build are ignored`() {
        val text = SessionCodec.encode(ConvertSession(SourceMedia.single(clip), EditSpec())) + "future=1|2\n"
        assertEquals(clip, SessionCodec.decode(text)!!.source.clips.single())
    }

    @Test
    fun `missing, blank, clipless or wrong-version input decodes to null`() {
        assertNull(SessionCodec.decode(null))
        assertNull(SessionCodec.decode(""))
        assertNull(SessionCodec.decode("v=1\nkind=SINGLE\ntrim=0|0\n"))
        assertNull(SessionCodec.decode(SessionCodec.encode(ConvertSession(SourceMedia.single(clip), EditSpec())).replace("v=1", "v=99")))
    }

    @Test
    fun `truncated clip record decodes to null rather than a half-filled clip`() {
        assertNull(SessionCodec.decode("v=1\nkind=SINGLE\nclip=content://x|name\n"))
    }
}
