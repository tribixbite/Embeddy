package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Tests for the video output pipeline: container resolution (MP4 / Original),
 * quality-to-bitrate mapping, and the audio-aware trim/concat filtergraphs
 * with per-segment volume.
 */
class VideoOutputCommandTest {

    private val script = File("/tmp/frames/frames.txt")

    private fun command(
        config: ConversionConfig = ConversionConfig(),
        edit: EditSpec = EditSpec(),
        container: VideoContainer? = VideoContainer.MP4,
        hasAudio: Boolean = true,
        quality: Int = 70,
        concatScript: File? = null,
        sourceWidth: Int = 1920,
        sourceHeight: Int = 1080,
    ): String = buildFfmpegCommand(
        CommandRequest(
            input = InputSpec(
                path = concatScript?.absolutePath ?: "/in/input.mp4",
                isConcatScript = concatScript != null,
                hasAudio = hasAudio,
                width = sourceWidth,
                height = sourceHeight,
            ),
            outputPath = "/out/output.${container?.extension ?: "webp"}",
            target = container?.let { OutputTarget.Video(it) } ?: OutputTarget.AnimatedWebp,
            config = config,
            edit = edit,
            quality = quality,
        ),
    )

    // ── container resolution ──

    @Test
    fun `WEBP format resolves to no container`() {
        assertEquals(null, resolveOutputContainer(OutputMediaFormat.WEBP, "clip.mp4"))
    }

    @Test
    fun `MP4 format always resolves to the MP4 container`() {
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.MP4, "clip.webm"))
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.MP4, "clip.mkv"))
    }

    @Test
    fun `ORIGINAL maps mp4-family inputs to the MP4 container`() {
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.mp4"))
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.MOV"))
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.m4v"))
    }

    @Test
    fun `ORIGINAL keeps webm and mkv containers`() {
        assertEquals(VideoContainer.WEBM, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.webm"))
        assertEquals(VideoContainer.MKV, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.mkv"))
    }

    @Test
    fun `ORIGINAL falls back to MP4 for unrecognized or missing extensions`() {
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "clip.avi"))
        assertEquals(VideoContainer.MP4, resolveOutputContainer(OutputMediaFormat.ORIGINAL, "noextension"))
    }

    @Test
    fun `container codec pairs match what this ffmpeg-kit build ships`() {
        // Non-GPL full build: no libx264/x265 — H.264 must come from libopenh264.
        assertEquals("libopenh264", VideoContainer.MP4.videoCodec)
        assertEquals("aac", VideoContainer.MP4.audioCodec)
        assertEquals("libvpx", VideoContainer.WEBM.videoCodec)
        assertEquals("libopus", VideoContainer.WEBM.audioCodec)
        assertEquals("libvpx", VideoContainer.MKV.videoCodec)
        assertEquals("libopus", VideoContainer.MKV.audioCodec)
    }

    // ── quality → bitrate mapping ──
    // Formula (integer-exact): bppMilli = 30 + quality * 3 / 2 (0.030 bpp at
    // q=0 rising linearly to 0.180 bpp at q=100); bitrate = w*h*fps*bppMilli/1000,
    // clamped to [200 kbps, 20 Mbps].

    @Test
    fun `bitrate formula is exact at the documented anchor points`() {
        // 720p30 at q=100: 1280*720*30 px/s * 0.180 bpp = 4,976,640 bps
        assertEquals(4_976_640L, videoBitrateBps(1280, 720, 30, 100))
        // 720x405 at 12 fps, q=70: 3,499,200 px/s * 0.135 bpp = 472,392 bps
        assertEquals(472_392L, videoBitrateBps(720, 405, 12, 70))
    }

    @Test
    fun `bitrate is monotonically non-decreasing in quality`() {
        val rates = (0..100 step 5).map { q -> videoBitrateBps(1280, 720, 30, q) }
        assertEquals(rates, rates.sorted())
    }

    @Test
    fun `bitrate clamps at both ends`() {
        assertEquals(200_000L, videoBitrateBps(16, 16, 1, 0))
        assertEquals(20_000_000L, videoBitrateBps(3840, 2160, 60, 100))
        // Degenerate dimensions land on the floor instead of zero
        assertEquals(200_000L, videoBitrateBps(0, 0, 30, 70))
    }

    // ── plain video encode (no trim) ──

    @Test
    fun `mp4 output uses openh264 plus aac and drops webp-only flags`() {
        val cmd = command(ConversionConfig(ditherMode = DitherMode.BAYER, colorSpace = ColorSpace.YUV420))
        assertTrue(cmd, "-c:v libopenh264" in cmd)
        assertTrue(cmd, "-c:a aac" in cmd)
        assertTrue(cmd, "-b:a 128k" in cmd)
        assertTrue(cmd, "-movflags +faststart" in cmd)
        assertTrue(cmd, "-b:v " in cmd)
        // WebP/GIF-only options must not leak into video encodes
        assertFalse(cmd, "-an" in cmd)
        assertFalse(cmd, "libwebp" in cmd)
        assertFalse(cmd, "-loop" in cmd)
        assertFalse(cmd, "-quality" in cmd)
        assertFalse(cmd, "-compression_level" in cmd)
        assertFalse(cmd, "palettegen" in cmd)
        assertFalse(cmd, "-vsync vfr" in cmd)
    }

    @Test
    fun `webm output uses libvpx plus libopus without movflags`() {
        val cmd = command(container = VideoContainer.WEBM)
        assertTrue(cmd, "-c:v libvpx" in cmd)
        assertTrue(cmd, "-c:a libopus" in cmd)
        assertFalse(cmd, "-movflags" in cmd)
    }

    @Test
    fun `video output without an audio track strips audio`() {
        val cmd = command(hasAudio = false)
        assertTrue(cmd, "-an" in cmd)
        assertFalse(cmd, "-c:a" in cmd)
    }

    @Test
    fun `scale and fps filters still apply to video outputs`() {
        val cmd = command(ConversionConfig(maxDimension = 480, fps = 24, sharpen = true))
        assertTrue(cmd, "scale=" in cmd)
        assertTrue(cmd, "fps=24" in cmd)
        assertTrue(cmd, "unsharp=" in cmd)
    }

    @Test
    fun `quality slider drives the video bitrate through the same formula`() {
        val config = ConversionConfig(maxDimension = 720, fps = 12)
        val cmdHigh = command(config, quality = 90)
        val cmdLow = command(config, quality = 40)
        // 1920x1080 at maxDimension 720 is 720x405, forced even (M3) to 720x404
        assertTrue(cmdHigh, "-b:v ${videoBitrateBps(720, 404, 12, 90)}" in cmdHigh)
        assertTrue(cmdLow, "-b:v ${videoBitrateBps(720, 404, 12, 40)}" in cmdLow)
    }

    // ── single-segment / legacy trim for video outputs ──

    @Test
    fun `single trim on a video output keeps the seek path and audio`() {
        val cmd = command(edit = EditSpec(trimStartMs = 1000, trimEndMs = 3000))
        assertTrue(cmd, "-ss 1.000" in cmd)
        assertTrue(cmd, "-t 2.000" in cmd)
        assertTrue(cmd, "-c:a aac" in cmd)
        assertFalse(cmd, "-filter_complex" in cmd)
    }

    @Test
    fun `single segment with non-unity volume adds an audio filter`() {
        val cmd = command(edit = EditSpec(segments = listOf(TrimSegment(0, 2000, volume = 0.5f))))
        assertTrue(cmd, "-af \"volume=0.500\"" in cmd)
    }

    @Test
    fun `single segment at unity volume emits no audio filter`() {
        val cmd = command(edit = EditSpec(segments = listOf(TrimSegment(0, 2000, volume = 1f))))
        assertFalse(cmd, "-af" in cmd)
    }

    @Test
    fun `single segment volume is ignored without an audio track`() {
        val cmd = command(
            edit = EditSpec(segments = listOf(TrimSegment(0, 2000, volume = 0.5f))),
            hasAudio = false,
        )
        assertFalse(cmd, "-af" in cmd)
        assertTrue(cmd, "-an" in cmd)
    }

    // ── multi-segment stitching for video outputs ──

    @Test
    fun `stitched video with audio builds paired trim-atrim streams`() {
        val cmd = command(
            edit = EditSpec(
                segments = listOf(
                    TrimSegment(0, 1000, volume = 1f),
                    TrimSegment(2000, 3000, volume = 1.5f),
                ),
            ),
        )
        assertTrue(cmd, "[0:v]trim=start=0.000:end=1.000,setpts=PTS-STARTPTS[v0]" in cmd)
        assertTrue(cmd, "[0:a]atrim=start=0.000:end=1.000,asetpts=PTS-STARTPTS,volume=1.000[a0]" in cmd)
        assertTrue(cmd, "[0:a]atrim=start=2.000:end=3.000,asetpts=PTS-STARTPTS,volume=1.500[a1]" in cmd)
        assertTrue(cmd, "[v0][a0][v1][a1]concat=n=2:v=1:a=1[cat][acat]" in cmd)
        assertTrue(cmd, "-map \"[vout]\"" in cmd)
        assertTrue(cmd, "-map \"[acat]\"" in cmd)
        assertTrue(cmd, "-c:v libopenh264" in cmd)
        assertTrue(cmd, "-c:a aac" in cmd)
    }

    @Test
    fun `stitched video without audio builds a video-only graph`() {
        val cmd = command(
            edit = EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 3000))),
            hasAudio = false,
        )
        assertTrue(cmd, "concat=n=2:v=1:a=0" in cmd)
        assertFalse(cmd, "atrim" in cmd)
        assertFalse(cmd, "[acat]" in cmd)
        assertTrue(cmd, "-an" in cmd)
    }

    @Test
    fun `stitched video applies scale-fps filters after the concat`() {
        val cmd = command(
            ConversionConfig(fps = 24),
            edit = EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 3000))),
        )
        assertTrue(cmd, "[cat]" in cmd)
        assertTrue(cmd, "fps=24" in cmd)
        assertTrue(cmd, "[vout]" in cmd)
    }

    @Test
    fun `stitched video drops zero-length segments`() {
        val cmd = command(
            edit = EditSpec(
                segments = listOf(
                    TrimSegment(0, 1000),
                    TrimSegment(1500, 1500),
                    TrimSegment(2000, 3000),
                ),
            ),
        )
        assertTrue(cmd, "concat=n=2:v=1:a=1" in cmd)
        assertFalse(cmd, "atrim=start=1.500" in cmd)
    }

    @Test
    fun `volume is clamped to the 0 to 2 range in the graph`() {
        val cmd = command(
            edit = EditSpec(
                segments = listOf(
                    TrimSegment(0, 1000, volume = -1f),
                    TrimSegment(2000, 3000, volume = 9f),
                ),
            ),
        )
        assertTrue(cmd, "volume=0.000[a0]" in cmd)
        assertTrue(cmd, "volume=2.000[a1]" in cmd)
    }

    @Test
    fun `volume formatting is locale-safe`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE) // decimal comma locale
            val cmd = command(
                edit = EditSpec(
                    segments = listOf(
                        TrimSegment(0, 1000, volume = 1.5f),
                        TrimSegment(2000, 3000, volume = 0.5f),
                    ),
                ),
            )
            assertTrue(cmd, "volume=1.500" in cmd)
            assertTrue(cmd, "volume=0.500" in cmd)
            assertFalse(cmd, "1,500" in cmd)
        } finally {
            Locale.setDefault(original)
        }
    }

    // ── video output over a pre-decoded animation (GIF or animated WebP) ──

    @Test
    fun `concat-script input can be stitched to a video container without audio filters`() {
        val cmd = command(
            edit = EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 3000))),
            hasAudio = false,
            concatScript = script,
        )
        assertTrue(cmd, "-f concat -safe 0" in cmd)
        assertTrue(cmd, script.absolutePath in cmd)
        assertTrue(cmd, "concat=n=2:v=1:a=0" in cmd)
        assertTrue(cmd, "-c:v libopenh264" in cmd)
    }

    // ── the webp path must be unaffected by video-only parameters ──

    @Test
    fun `audio flag and source dimensions do not perturb the animated webp command`() {
        val edit = EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 3000)))
        val plain = command(edit = edit, container = null, hasAudio = false, sourceWidth = 0, sourceHeight = 0)
        val withVideoInputs = command(edit = edit, container = null, hasAudio = true)
        assertEquals(plain, withVideoInputs)
        assertTrue(plain, "-c:v libwebp_anim" in plain)
        assertTrue(plain, "-an" in plain)
        assertFalse(plain, "atrim" in plain)
    }
}
