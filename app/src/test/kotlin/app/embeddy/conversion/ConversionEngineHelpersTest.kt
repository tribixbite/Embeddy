package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the pure, top-level helpers extracted from [ConversionEngine]:
 * FFmpeg command construction, rotation-aware dimension normalization,
 * FFprobe JSON scalar extraction, and loop-count resolution.
 */
class ConversionEngineHelpersTest {

    private val script = File("/tmp/frames/frames.txt")

    private fun command(
        edit: EditSpec = EditSpec(),
        concatScript: File? = null,
        sourceLoopCount: Int? = null,
        quality: Int = 70,
        config: ConversionConfig = ConversionConfig(),
    ): String = buildFfmpegCommand(
        CommandRequest(
            input = if (concatScript != null) {
                InputSpec(path = concatScript.absolutePath, isConcatScript = true)
            } else {
                InputSpec(path = "/in/input.mp4")
            },
            outputPath = "/out/output.webp",
            target = OutputTarget.AnimatedWebp,
            config = config,
            edit = edit,
            quality = quality,
            sourceLoopCount = sourceLoopCount,
        ),
    )

    // ── concat-script input (pre-decoded animated WebP) ──

    @Test
    fun `concat script input uses the concat demuxer and the script path`() {
        val cmd = command(concatScript = script)
        assertTrue(cmd, "-f concat -safe 0" in cmd)
        assertTrue(cmd, script.absolutePath in cmd)
        assertFalse(cmd, "/in/input.mp4" in cmd)
    }

    @Test
    fun `preview of an animated WebP uses the concat demuxer when a script is passed`() {
        // Mirrors generatePreview: single-segment trim override + concat script (M24)
        val previewConfig = EditSpec(trimStartMs = 0, trimEndMs = 3000)
        val cmd = command(previewConfig, concatScript = script)
        assertTrue(cmd, "-f concat -safe 0" in cmd)
        assertTrue(cmd, script.absolutePath in cmd)
    }

    // ── multi-segment stitching over a concat script (M25) ──

    @Test
    fun `all segments survive stitching when the input is a concat script`() {
        val edit = EditSpec(
            segments = listOf(
                TrimSegment(0, 1000),
                TrimSegment(2000, 3000),
                TrimSegment(4000, 5000),
            ),
        )
        val cmd = command(edit, concatScript = script)
        // Previously the concat branch won and used only segments.first()
        assertTrue(cmd, "concat=n=3" in cmd)
        assertTrue(cmd, "[v2]" in cmd)
        assertTrue(cmd, "-filter_complex" in cmd)
        assertTrue(cmd, "-f concat -safe 0" in cmd)
        assertTrue(cmd, script.absolutePath in cmd)
        // All three trim ranges appear in the filtergraph
        assertTrue(cmd, "trim=start=0.000:end=1.000" in cmd)
        assertTrue(cmd, "trim=start=2.000:end=3.000" in cmd)
        assertTrue(cmd, "trim=start=4.000:end=5.000" in cmd)
    }

    @Test
    fun `multi-segment stitching over a direct input is unchanged`() {
        val cmd = command(EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 3000))))
        assertTrue(cmd, "concat=n=2" in cmd)
        assertTrue(cmd, "-i \"/in/input.mp4\"" in cmd)
    }

    // ── single-segment trim ──

    @Test
    fun `single trim seeks and limits duration as input options`() {
        val cmd = command(EditSpec(trimStartMs = 1000, trimEndMs = 3000))
        assertTrue(cmd, "-ss 1.000" in cmd)
        assertTrue(cmd, "-t 2.000" in cmd)
    }

    // ── stitch edge cases (segment list robustness) ──

    @Test
    fun `single segment uses the seek path, not a filtergraph`() {
        val cmd = command(EditSpec(segments = listOf(TrimSegment(1000, 4000))))
        assertTrue(cmd, "-ss 1.000" in cmd)
        assertTrue(cmd, "-t 3.000" in cmd)
        assertFalse(cmd, "-filter_complex" in cmd)
    }

    @Test
    fun `many segments all appear in the filtergraph in order`() {
        val segments = (0 until 6).map { TrimSegment(it * 2000L, it * 2000L + 1000L) }
        val cmd = command(EditSpec(segments = segments))
        assertTrue(cmd, "concat=n=6" in cmd)
        (0 until 6).forEach { i ->
            assertTrue(cmd, "[v$i]" in cmd)
            assertTrue(cmd, "trim=start=${i * 2}.000:end=${i * 2 + 1}.000" in cmd)
        }
    }

    @Test
    fun `segment end beyond the probed duration passes through unclamped`() {
        // The builder has no duration knowledge; FFmpeg's trim simply stops at
        // EOF, so the values must be emitted as given rather than dropped.
        val cmd = command(
            EditSpec(segments = listOf(TrimSegment(0, 1000), TrimSegment(2000, 125_000))),
        )
        assertTrue(cmd, "trim=start=2.000:end=125.000" in cmd)
    }

    @Test
    fun `zero-length segments are dropped from the filtergraph`() {
        val cmd = command(
            EditSpec(
                segments = listOf(
                    TrimSegment(0, 1000),
                    TrimSegment(3000, 3000), // empty trim stream would abort concat
                    TrimSegment(4000, 5000),
                ),
            ),
        )
        assertTrue(cmd, "concat=n=2" in cmd)
        assertFalse(cmd, "trim=start=3.000:end=3.000" in cmd)
    }

    @Test
    fun `dropping zero-length segments can collapse stitching to the seek path`() {
        val cmd = command(
            EditSpec(segments = listOf(TrimSegment(1000, 4000), TrimSegment(6000, 6000))),
        )
        assertFalse(cmd, "-filter_complex" in cmd)
        assertTrue(cmd, "-ss 1.000" in cmd)
        assertTrue(cmd, "-t 3.000" in cmd)
    }

    @Test
    fun `unsorted segments are emitted in start-time order`() {
        val cmd = command(
            EditSpec(segments = listOf(TrimSegment(4000, 5000), TrimSegment(0, 1000))),
        )
        val first = cmd.indexOf("trim=start=0.000:end=1.000")
        val second = cmd.indexOf("trim=start=4.000:end=5.000")
        assertTrue(cmd, first in 0 until second)
    }

    @Test
    fun `zero-length segments are dropped on the concat-script input too`() {
        val cmd = command(
            EditSpec(
                segments = listOf(
                    TrimSegment(0, 1000),
                    TrimSegment(2000, 2000),
                    TrimSegment(3000, 4000),
                ),
            ),
            concatScript = script,
        )
        assertTrue(cmd, "concat=n=2" in cmd)
        assertTrue(cmd, "-f concat -safe 0" in cmd)
        assertFalse(cmd, "trim=start=2.000:end=2.000" in cmd)
    }

    // ── encoder flags: keyframeInterval removed (M64), colorSpace mapping (M39) ──

    @Test
    fun `no -g flag is ever emitted`() {
        // libwebp_anim never reads gop_size; the flag was a silent no-op
        assertFalse("-g " in command())
    }

    @Test
    fun `colorSpace AUTO emits no pix_fmt, YUV420 emits yuv420p`() {
        assertFalse("-pix_fmt" in command(config = ConversionConfig(colorSpace = ColorSpace.AUTO)))
        assertTrue("-pix_fmt yuv420p" in command(config = ConversionConfig(colorSpace = ColorSpace.YUV420)))
    }

    // ── loop-count resolution (M70) ──

    @Test
    fun `default loop with a finite source loop preserves the source value`() {
        assertEquals(3, resolveLoopCount(configLoop = 0, sourceLoopCount = 3))
    }

    @Test
    fun `explicit user loop wins over the source loop`() {
        assertEquals(5, resolveLoopCount(configLoop = 5, sourceLoopCount = 3))
    }

    @Test
    fun `infinite source loop stays infinite`() {
        assertEquals(0, resolveLoopCount(configLoop = 0, sourceLoopCount = 0))
    }

    @Test
    fun `no source loop info keeps the config value`() {
        assertEquals(0, resolveLoopCount(configLoop = 0, sourceLoopCount = null))
    }

    @Test
    fun `source loop count reaches the -loop flag`() {
        val cmd = command(concatScript = script, sourceLoopCount = 2)
        assertTrue(cmd, "-loop 2" in cmd)
    }

    // ── normalizeDimensions (M67) ──

    @Test
    fun `rotation 90 swaps probed dimensions`() {
        assertEquals(1080 to 1920, normalizeDimensions(1920, 1080, 90))
    }

    @Test
    fun `rotation 270 swaps probed dimensions`() {
        assertEquals(1080 to 1920, normalizeDimensions(1920, 1080, 270))
    }

    @Test
    fun `negative rotation is normalized before the swap check`() {
        assertEquals(1080 to 1920, normalizeDimensions(1920, 1080, -90))
    }

    @Test
    fun `rotation 0 and 180 leave dimensions untouched`() {
        assertEquals(1920 to 1080, normalizeDimensions(1920, 1080, 0))
        assertEquals(1920 to 1080, normalizeDimensions(1920, 1080, 180))
    }

    // ── FfprobeJsonParser (M66) ──

    private val portraitJson = """
        {
          "streams": [
            {
              "index": 0,
              "codec_name": "h264",
              "codec_type": "video",
              "width": 1920,
              "height": 1080,
              "nb_frames": "300",
              "side_data_list": [
                { "side_data_type": "Display Matrix", "displaymatrix": "...", "rotation": -90 }
              ]
            },
            { "index": 1, "codec_type": "audio", "nb_frames": "551" }
          ],
          "format": { "duration": "10.000000" }
        }
    """.trimIndent()

    @Test
    fun `ffprobe side-data rotation -90 maps to display rotation 90`() {
        assertEquals(90, FfprobeJsonParser.rotationDegrees(portraitJson))
    }

    @Test
    fun `ffprobe rotation 90 maps to display rotation 270`() {
        val json = """{"streams":[{"side_data_list":[{"rotation": 90}]}]}"""
        assertEquals(270, FfprobeJsonParser.rotationDegrees(json))
    }

    @Test
    fun `quoted rotation values parse too`() {
        val json = """{"streams":[{"side_data_list":[{"rotation": "-180"}]}]}"""
        assertEquals(180, FfprobeJsonParser.rotationDegrees(json))
    }

    @Test
    fun `missing rotation yields 0`() {
        assertEquals(0, FfprobeJsonParser.rotationDegrees("""{"streams":[{"width":640}]}"""))
    }

    @Test
    fun `nb_frames of the first stream is extracted`() {
        assertEquals(300, FfprobeJsonParser.frameCount(portraitJson))
    }

    @Test
    fun `missing nb_frames yields 0`() {
        assertEquals(0, FfprobeJsonParser.frameCount("""{"streams":[{"width":640}]}"""))
    }
}
