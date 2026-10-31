package app.embeddy.conversion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command building for the quick-editor features: GIF output, mute and audio
 * extraction, speed, rotate/flip, crop, reverse, joining separate files, and
 * image sequences.
 */
class EditFeatureCommandTest {

    private val video = InputSpec("/in/a.mp4", hasAudio = true, width = 1920, height = 1080, durationMs = 10_000)
    private val mp4 = OutputTarget.Video(VideoContainer.MP4)

    private fun cmd(
        target: OutputTarget = mp4,
        edit: EditSpec = EditSpec(),
        config: ConversionConfig = ConversionConfig(),
        inputs: List<InputSpec> = listOf(video),
        quality: Int = 70,
    ) = buildFfmpegCommand(CommandRequest(inputs, "/out/o.${target.extension}", target, config, edit, quality))

    // ── GIF ──

    @Test
    fun `gif output uses the gif encoder with a per-frame palette and loop`() {
        val c = cmd(OutputTarget.Gif, config = ConversionConfig(loop = 3, ditherMode = DitherMode.BAYER))
        assertTrue(c, "-c:v gif" in c)
        assertTrue(c, "-loop 3" in c)
        assertTrue(c, "stats_mode=single" in c)
        assertTrue(c, "paletteuse=new=1:dither=bayer" in c)
        assertTrue(c, "-an" in c)
        assertFalse(c, "libwebp" in c)
    }

    @Test
    fun `gif quality maps to palette size`() {
        assertEquals(256, gifMaxColors(100))
        assertEquals(32, gifMaxColors(0))
        assertTrue("max_colors=${gifMaxColors(40)}" in cmd(OutputTarget.Gif, quality = 40))
        assertEquals("image/gif", resolveOutputTarget(OutputMediaFormat.GIF, "x.mp4").mimeType)
    }

    // ── mute / extract audio ──

    @Test
    fun `muted video output drops audio`() {
        val c = cmd(edit = EditSpec(muted = true))
        assertTrue(c, "-an" in c)
        assertFalse(c, "-c:a" in c)
    }

    @Test
    fun `audio extraction writes aac m4a without video`() {
        val c = cmd(OutputTarget.AudioM4a, edit = EditSpec(trimStartMs = 1_000, trimEndMs = 4_000))
        assertTrue(c, "-vn" in c)
        assertTrue(c, "-c:a aac -b:a 192k" in c)
        assertTrue(c, "-ss 1.000" in c && "-t 3.000" in c)
        assertFalse(c, "-vf" in c)
        assertTrue(c, c.endsWith("\"/out/o.m4a\""))
    }

    @Test
    fun `stitched audio extraction builds an audio-only graph`() {
        val c = cmd(OutputTarget.AudioM4a, edit = EditSpec(segments = listOf(TrimSegment(0, 1_000), TrimSegment(2_000, 3_000, 0.5f))))
        assertTrue(c, "[a0][a1]concat=n=2:v=0:a=1[acat]" in c)
        assertFalse(c, "[0:v]" in c)
        assertTrue(c, "-map \"[acat]\"" in c)
        assertFalse(c, "-map \"[vout]\"" in c)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `audio extraction from a silent input is rejected`() {
        cmd(OutputTarget.AudioM4a, inputs = listOf(video.copy(hasAudio = false)))
    }

    // ── speed ──

    @Test
    fun `atempo stages stay within 0_5 to 2`() {
        assertEquals(emptyList<String>(), atempoChain(1f))
        assertEquals(listOf("atempo=1.5"), atempoChain(1.5f))
        assertEquals(listOf("atempo=2", "atempo=2"), atempoChain(4f))
        assertEquals(listOf("atempo=0.5", "atempo=0.5"), atempoChain(0.25f))
        assertEquals(listOf("atempo=2", "atempo=1.5"), atempoChain(3f))
    }

    @Test
    fun `speed scales video timestamps and audio tempo`() {
        val c = cmd(edit = EditSpec(speed = 2f))
        assertTrue(c, "setpts=PTS/2" in c)
        assertTrue(c, "-af \"atempo=2\"" in c)
    }

    @Test
    fun `speed keeps the trim duration on the source timeline`() {
        val c = cmd(edit = EditSpec(trimStartMs = 2_000, trimEndMs = 6_000, speed = 2f))
        // -t is an input option: 4 s of source, which plays as 2 s.
        assertTrue(c, "-ss 2.000 -t 4.000 -i" in c)
        assertEquals(2_000L, outputDurationMs(EditSpec(trimStartMs = 2_000, trimEndMs = 6_000, speed = 2f), 10_000))
    }

    @Test
    fun `stitched speed change applies after the concat`() {
        val c = cmd(edit = EditSpec(speed = 0.5f, segments = listOf(TrimSegment(0, 1_000), TrimSegment(2_000, 3_000))))
        assertTrue(c, "[cat]setpts=PTS/0.5" in c)
        assertTrue(c, "[acat]atempo=0.5[aout]" in c)
        assertTrue(c, "-map \"[aout]\"" in c)
    }

    // ── rotate / flip ──

    @Test
    fun `rotation and flips map to transpose and flip filters before scaling`() {
        val c = cmd(edit = EditSpec(rotationDegrees = 90, flipHorizontal = true))
        val chain = c.substringAfter("-vf \"").substringBefore('"')
        assertTrue(chain, chain.startsWith("transpose=clock,hflip,scale="))
        assertEquals(listOf("hflip", "vflip"), orientationFilters(EditSpec(rotationDegrees = 180)))
        assertEquals(listOf("transpose=cclock", "vflip"), orientationFilters(EditSpec(rotationDegrees = -90, flipVertical = true)))
    }

    @Test
    fun `a quarter turn swaps the estimated output dimensions`() {
        val req = CommandRequest(video, "/o.mp4", mp4, ConversionConfig(maxDimension = 720), EditSpec(rotationDegrees = 270))
        assertEquals(404 to 720, estimatedOutputSize(req))
    }

    // ── crop ──

    @Test
    fun `crop fractions become even pixel crops on the display frame`() {
        assertEquals(CropPixels(960, 540, 480, 270), cropPixels(CropRect(0.25f, 0.25f, 0.75f, 0.75f), 1920, 1080))
        val odd = cropPixels(CropRect(0.1f, 0.1f, 0.9f, 0.9f), 1001, 777)
        assertTrue(odd.width % 2 == 0 && odd.height % 2 == 0 && odd.x % 2 == 0 && odd.y % 2 == 0)
        assertTrue(odd.x + odd.width <= 1001 && odd.y + odd.height <= 777)
    }

    @Test
    fun `crop is emitted before rotation and scaling`() {
        val c = cmd(edit = EditSpec(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f), rotationDegrees = 90))
        assertTrue(c, "-vf \"crop=960:540:480:270,transpose=clock,scale=" in c)
        assertEquals(540 to 960, editedDimensions(1920, 1080, EditSpec(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f), rotationDegrees = 90)))
    }

    @Test
    fun `full-frame crop and unknown size are handled`() {
        assertNull(cropFilter(EditSpec(crop = CropRect.FULL), 1920, 1080))
        assertEquals("crop=iw*0.5000:ih*0.5000:iw*0.2500:ih*0.2500", cropFilter(EditSpec(crop = CropRect(0.25f, 0.25f, 0.75f, 0.75f)), 0, 0))
    }

    @Test
    fun `crop rects are ordered, clamped and kept above the minimum size`() {
        val r = CropRect(0.9f, 1.2f, 0.1f, -0.5f).normalized()
        assertEquals(CropRect(0.1f, 0f, 0.9f, 1f), r)
        val tiny = CropRect(0.5f, 0.5f, 0.5f, 0.5f).normalized()
        assertEquals(CropRect.MIN_CROP_FRACTION, tiny.right - tiny.left, 1e-6f)
    }

    // ── reverse ──

    @Test
    fun `reverse buffers after scale and fps, audio reversed too`() {
        val c = cmd(edit = EditSpec(reverse = true, trimEndMs = 4_000))
        val chain = c.substringAfter("-vf \"").substringBefore('"')
        assertTrue(chain, chain.indexOf("reverse") > chain.indexOf("fps="))
        assertTrue(c, "-af \"areverse\"" in c)
    }

    @Test
    fun `reverse is allowed only for short outputs`() {
        assertTrue(reverseAllowed(10_000))
        assertFalse(reverseAllowed(10_001))
        assertFalse(reverseAllowed(0))
    }

    // ── join ──

    private val clipB = InputSpec("/in/b.mov", hasAudio = false, width = 1080, height = 1920, durationMs = 4_000)

    @Test
    fun `joined inputs are normalized to one canvas and fps, silent clips get generated audio`() {
        val c = cmd(inputs = listOf(video, clipB), config = ConversionConfig(maxDimension = 720, fps = 24))
        assertTrue(c, "-i \"/in/a.mp4\" -i \"/in/b.mov\"" in c)
        assertTrue(c, "[0:v]setpts=PTS-STARTPTS,scale=720:404:force_original_aspect_ratio=decrease:flags=lanczos,pad=720:404:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=24[v0]" in c)
        assertTrue(c, "[1:v]setpts=PTS-STARTPTS,scale=720:404" in c)
        assertTrue(c, "[0:a]asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo[a0]" in c)
        assertTrue(c, "anullsrc=r=48000:cl=stereo,atrim=duration=4.000[a1]" in c)
        assertTrue(c, "[v0][a0][v1][a1]concat=n=2:v=1:a=1[cat][acat]" in c)
        // Scale/fps already happened per input; the post chain must not rescale.
        assertFalse(c, "[cat]scale" in c)
    }

    @Test
    fun `joined clips are trimmed by their own ranges`() {
        val edit = EditSpec(clipRanges = listOf(TrimSegment(1_000, 3_000), TrimSegment(0, 4_000)))
        val c = cmd(inputs = listOf(video, clipB), edit = edit)
        assertTrue(c, "[0:v]trim=start=1.000:end=3.000,setpts" in c)
        assertTrue(c, "[0:a]atrim=start=1.000:end=3.000,asetpts" in c)
        // Clip 2 is kept whole: no trim filter.
        assertTrue(c, "[1:v]setpts=PTS-STARTPTS" in c)
        assertEquals(6_000L, outputDurationMs(edit, listOf(video, clipB)))
    }

    @Test
    fun `a join of silent clips has no audio graph`() {
        val c = cmd(inputs = listOf(clipB, clipB.copy(path = "/in/c.mov")))
        assertFalse(c, "anullsrc" in c)
        assertTrue(c, "concat=n=2:v=1:a=0[cat]" in c)
        assertTrue(c, "-an" in c)
    }

    @Test
    fun `join applies rotation and speed after the concat`() {
        val c = cmd(inputs = listOf(video, clipB), edit = EditSpec(speed = 2f, rotationDegrees = 180))
        assertTrue(c, "[cat]setpts=PTS/2,hflip,vflip" in c)
        assertTrue(c, "[acat]atempo=2[aout]" in c)
    }

    // ── preview windows for multi-file jobs ──

    private fun clip(uri: String, ms: Long) = SourceClip(uri, "$uri.mp4", 1, durationMs = ms)

    @Test
    fun `join preview keeps only the clips inside the window`() {
        val job = ConvertJob(
            SourceMedia(SourceKind.JOIN, listOf(clip("a", 2_000), clip("b", 5_000), clip("c", 5_000))),
            ConversionConfig(),
            EditSpec(),
        )
        val preview = previewJob(job, 3_000)!!
        assertEquals(listOf("a", "b"), preview.source.clips.map { it.uri })
        assertEquals(listOf(TrimSegment(0, 2_000), TrimSegment(0, 1_000)), preview.edit.clipRanges)
    }

    @Test
    fun `join preview inside the first clip collapses to a single trimmed input`() {
        val job = ConvertJob(
            SourceMedia(SourceKind.JOIN, listOf(clip("a", 8_000), clip("b", 5_000))),
            ConversionConfig(),
            EditSpec(clipRanges = listOf(TrimSegment(1_000, 8_000), TrimSegment(0, 5_000))),
        )
        val preview = previewJob(job, 3_000)!!
        assertEquals(SourceKind.SINGLE, preview.source.kind)
        assertEquals(1_000L to 4_000L, preview.edit.trimStartMs to preview.edit.trimEndMs)
    }

    @Test
    fun `sequence preview keeps the frames that fit`() {
        val images = (1..10).map { SourceClip("i$it", "i$it.jpg", 1, width = 10, height = 10) }
        val job = ConvertJob(SourceMedia(SourceKind.IMAGE_SEQUENCE, images), ConversionConfig(), EditSpec(frameDurationMs = 500))
        assertEquals(6, previewJob(job, 3_000)!!.source.clips.size)
    }

    @Test
    fun `speed-adjusted previews cover more source time`() {
        val window = previewEdit(EditSpec(speed = 2f), 30_000, 3_000)!!
        assertEquals(listOf(TrimSegment(0, 6_000)), window.keptRanges(30_000))
    }

    // ── image sequences ──

    @Test
    fun `sequence canvas keeps aspect, caps the long edge and is even`() {
        assertEquals(1920 to 1440, sequenceCanvas(4032, 3024))
        assertEquals(400 to 300, sequenceCanvas(401, 301))
    }

    @Test
    fun `an image sequence encodes from its concat script like any frame sequence`() {
        val seq = InputSpec("/tmp/images.txt", isConcatScript = true, width = 800, height = 600, durationMs = 1_500)
        val c = cmd(target = mp4, inputs = listOf(seq))
        assertTrue(c, "-f concat -safe 0 -i \"/tmp/images.txt\"" in c)
        assertTrue(c, "-an" in c)
    }

    // ── session persistence of the edit tools ──

    @Test
    fun `all edit tools round-trip through the session codec`() {
        val session = ConvertSession(
            SourceMedia(SourceKind.JOIN, listOf(clip("a", 1_000), clip("b", 2_000))),
            EditSpec(
                muted = true, speed = 1.5f, rotationDegrees = 270, flipHorizontal = true, flipVertical = true,
                crop = CropRect(0.1f, 0.2f, 0.8f, 0.9f), reverse = true,
                clipRanges = listOf(TrimSegment(0, 500), TrimSegment(100, 2_000)), frameDurationMs = 750,
            ),
        )
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }
}
