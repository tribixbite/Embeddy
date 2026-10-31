package app.embeddy.viewmodel

import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionProgress
import app.embeddy.conversion.ConversionState
import app.embeddy.conversion.ConvertEngine
import app.embeddy.conversion.ConvertJob
import app.embeddy.conversion.ConvertSession
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.ErrorKind
import app.embeddy.conversion.OutputMediaFormat
import app.embeddy.conversion.Preset
import app.embeddy.conversion.SessionCodec
import app.embeddy.conversion.SourceClip
import app.embeddy.conversion.SourceKind
import app.embeddy.conversion.SourceMedia
import app.embeddy.conversion.TrimSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for [ConvertController] with a scripted fake engine:
 * per-input edit reset (H3), preset/edit independence, return-to-input flows
 * (M1), crash-proofing against a throwing engine (H1), session persistence
 * across process death (M10) and debounced preference writes (M7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvertControllerTest {

    private val video = SourceClip("content://v/1", "clip.mp4", 1_000, durationMs = 10_000, width = 1920, height = 1080, hasAudio = true)
    private val other = SourceClip("content://v/2", "other.mp4", 2_000, durationMs = 4_000, width = 640, height = 360)
    private val gif = SourceClip("content://g/1", "loop.gif", 500, durationMs = 2_000, width = 320, height = 240)

    /** Engine whose behaviour each test scripts. */
    private class FakeEngine(val clips: Map<String, SourceClip>) : ConvertEngine {
        var convertFlow: (ConvertJob) -> Flow<ConversionProgress> = { flow { awaitCancellation() } }
        var previewFlow: (ConvertJob) -> Flow<ConversionProgress> = { flow { awaitCancellation() } }
        val jobs = mutableListOf<ConvertJob>()
        val deleted = mutableListOf<String>()
        var describeGate: CompletableDeferred<Unit>? = null

        override suspend fun describe(uri: String): SourceClip {
            describeGate?.await()
            return clips[uri] ?: throw IllegalStateException("unreadable $uri")
        }

        override suspend fun describeImage(uri: String): SourceClip = describe(uri)

        var grabResult: () -> String = { "/frame.png" }
        override suspend fun grabFrame(uri: String, positionMs: Long): String = grabResult()

        override fun convert(job: ConvertJob): Flow<ConversionProgress> {
            jobs += job
            return convertFlow(job)
        }

        override fun preview(job: ConvertJob): Flow<ConversionProgress> = previewFlow(job)

        override suspend fun deleteFile(path: String) {
            deleted += path
        }
    }

    private class FakeConfigStore(var stored: ConversionConfig = ConversionConfig.fromPreset(Preset.DISCORD)) : ConfigStore {
        val saves = mutableListOf<ConversionConfig>()
        override suspend fun load() = stored
        override suspend fun save(config: ConversionConfig) {
            saves += config
        }
    }

    private class FakeSessionStore(var value: String? = null) : SessionStore {
        override fun read() = value
        override fun write(value: String?) {
            this.value = value
        }
    }

    private val engine = FakeEngine(mapOf(video.uri to video, other.uri to other, gif.uri to gif))
    private val configStore = FakeConfigStore()
    private val sessions = FakeSessionStore()

    private fun TestScope.controller() =
        ConvertController(backgroundScope, engine, configStore, sessions).also { runCurrent() }

    private fun TestScope.pick(c: ConvertController, clip: SourceClip): ConversionState.Ready {
        c.onFilePicked(clip.uri)
        runCurrent()
        return c.state.value as ConversionState.Ready
    }

    @Test
    fun `picking a file probes it and shows Ready`() = runTest {
        val c = controller()
        val ready = pick(c, video)
        assertEquals(video, ready.primary)
    }

    @Test
    fun `an unreadable input becomes a READ_FILE error, not a crash`() = runTest {
        val c = controller()
        c.onFilePicked("content://missing")
        runCurrent()
        val error = c.state.value as ConversionState.Error
        assertEquals(ErrorKind.READ_FILE, error.kind)
        assertNull(error.returnTo)
    }

    @Test
    fun `a new input starts with a fresh edit`() = runTest {
        val c = controller()
        pick(c, video)
        c.setSegments(listOf(TrimSegment(4_000, 9_000)))
        c.setTrim(1_000, 2_000)
        pick(c, other)
        assertEquals(EditSpec(), c.edit.value)
    }

    @Test
    fun `choosing a preset keeps the edit and the output format`() = runTest {
        val c = controller()
        pick(c, video)
        c.updateConfig { copy(outputFormat = OutputMediaFormat.MP4) }
        c.setSegments(listOf(TrimSegment(0, 1_000), TrimSegment(3_000, 4_000)))
        c.setPreset(Preset.TELEGRAM)
        assertEquals(2, c.edit.value.segments.size)
        assertEquals(OutputMediaFormat.MP4, c.config.value.outputFormat)
        assertEquals(Preset.TELEGRAM, c.config.value.preset)
    }

    @Test
    fun `animated image input forces WebP without forgetting the video choice`() = runTest {
        val c = controller()
        pick(c, video)
        c.updateConfig { copy(outputFormat = OutputMediaFormat.MP4) }
        pick(c, gif)
        assertEquals(OutputMediaFormat.WEBP, c.config.value.outputFormat)
        pick(c, video)
        assertEquals(OutputMediaFormat.MP4, c.config.value.outputFormat)
    }

    @Test
    fun `conversion receives the current edit and ends in Done with a way back`() = runTest {
        engine.convertFlow = {
            flow {
                emit(ConversionProgress.Attempt(70, 1))
                emit(ConversionProgress.Progress(0.5f, 70, 1, 100))
                emit(ConversionProgress.Complete("/out.webp", 42, 70))
            }
        }
        val c = controller()
        val ready = pick(c, video)
        c.setTrim(1_000, 3_000)
        c.startConversion()
        runCurrent()
        assertEquals(EditSpec(trimStartMs = 1_000, trimEndMs = 3_000), engine.jobs.single().edit)
        val done = c.state.value as ConversionState.Done
        assertEquals(ready, done.returnTo)
        c.backToReady()
        assertEquals(ready, c.state.value)
        assertEquals(1_000L, c.edit.value.trimStartMs)
    }

    @Test
    fun `adjust and retry from a size warning keeps the file`() = runTest {
        engine.convertFlow = { flow { emit(ConversionProgress.CompletedOversize("/o", 20, 10, 50)) } }
        val c = controller()
        val ready = pick(c, video)
        c.startConversion()
        runCurrent()
        assertTrue(c.state.value is ConversionState.SizeWarning)
        c.backToReady()
        assertEquals(ready, c.state.value)
    }

    @Test
    fun `try again after a failed conversion keeps the file`() = runTest {
        engine.convertFlow = { flow { emit(ConversionProgress.Failed("nope")) } }
        val c = controller()
        val ready = pick(c, video)
        c.startConversion()
        runCurrent()
        assertEquals(ConversionState.Error("nope", ErrorKind.CONVERSION, ready), c.state.value)
        c.backToReady()
        assertEquals(ready, c.state.value)
    }

    @Test
    fun `an engine that throws ends in an Error state instead of crashing`() = runTest {
        engine.convertFlow = { flow { throw java.io.IOException("disk full") } }
        val c = controller()
        val ready = pick(c, video)
        c.startConversion()
        runCurrent()
        assertEquals(ConversionState.Error("disk full", ErrorKind.CONVERSION, ready), c.state.value)
    }

    @Test
    fun `cancelling a conversion returns to the input`() = runTest {
        val c = controller()
        val ready = pick(c, video)
        c.startConversion()
        runCurrent()
        assertTrue(c.state.value is ConversionState.Converting)
        c.cancelConversion()
        runCurrent()
        assertEquals(ready, c.state.value)
    }

    @Test
    fun `cancelling a preview restores Ready and dismissing deletes the clip`() = runTest {
        val c = controller()
        val ready = pick(c, video)
        c.startPreview()
        runCurrent()
        assertTrue(c.state.value is ConversionState.Previewing)
        c.cancelPreview()
        assertEquals(ready, c.state.value)

        engine.previewFlow = { flow { emit(ConversionProgress.Complete("/preview.webp", 9, 70)) } }
        c.startPreview()
        runCurrent()
        assertTrue(c.state.value is ConversionState.PreviewReady)
        c.dismissPreview()
        runCurrent()
        assertEquals(ready, c.state.value)
        assertEquals(listOf("/preview.webp"), engine.deleted)
    }

    @Test
    fun `a failed preview surfaces a notice on Ready`() = runTest {
        engine.previewFlow = { flow { emit(ConversionProgress.Failed("bad filter")) } }
        val c = controller()
        pick(c, video)
        c.startPreview()
        runCurrent()
        assertEquals("bad filter", (c.state.value as ConversionState.Ready).notice)
    }

    @Test
    fun `a pick during a slow probe supersedes the earlier pick`() = runTest {
        val gate = CompletableDeferred<Unit>()
        engine.describeGate = gate
        val c = controller()
        c.onFilePicked(video.uri)
        runCurrent()
        assertEquals(ConversionState.Loading, c.state.value)
        engine.describeGate = null
        c.onFilePicked(other.uri)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(other, (c.state.value as ConversionState.Ready).primary)
    }

    @Test
    fun `the input and edit survive process death`() = runTest {
        val c = controller()
        pick(c, video)
        c.setSegments(listOf(TrimSegment(0, 1_000, 0.5f), TrimSegment(2_000, 3_000)))
        val restored = ConvertController(backgroundScope, engine, configStore, FakeSessionStore(sessions.value))
        runCurrent()
        assertEquals(video, (restored.state.value as ConversionState.Ready).primary)
        assertEquals(c.edit.value, restored.edit.value)
    }

    @Test
    fun `reset clears the saved session`() = runTest {
        val c = controller()
        pick(c, video)
        c.reset()
        assertNull(sessions.value)
        assertEquals(ConversionState.Idle, c.state.value)
    }

    @Test
    fun `config writes are debounced to the settled value`() = runTest {
        val c = controller()
        repeat(20) { i -> c.updateConfig { copy(startQuality = 40 + i) }; advanceTimeBy(50) }
        assertTrue(configStore.saves.isEmpty())
        advanceTimeBy(ConvertController.PERSIST_DEBOUNCE_MS + 1)
        assertEquals(listOf(59), configStore.saves.map { it.startQuality })
    }

    @Test
    fun `stored preferences load without being written back`() = runTest {
        configStore.stored = ConversionConfig.fromPreset(Preset.SLACK)
        val c = controller()
        advanceUntilIdle()
        assertEquals(Preset.SLACK, c.config.value.preset)
        assertTrue(configStore.saves.isEmpty())
    }

    @Test
    fun `a session restored from an older encoding still works`() = runTest {
        sessions.value = SessionCodec.encode(ConvertSession(SourceMedia.single(other), EditSpec(trimEndMs = 2_000)))
        val c = controller()
        assertEquals(2_000L, c.edit.value.trimEndMs)
    }

    // ── multi-file sources ──

    private val v3 = SourceClip("content://v/3", "third.mp4", 3_000, durationMs = 6_000, width = 1280, height = 720)
    private val img1 = SourceClip("content://i/1", "a.jpg", 100, width = 400, height = 300)
    private val img2 = SourceClip("content://i/2", "b.png", 100, width = 300, height = 400)

    private val multiEngine = FakeEngine(
        mapOf(video.uri to video, other.uri to other, v3.uri to v3, img1.uri to img1, img2.uri to img2),
    )

    private fun TestScope.multiController() =
        ConvertController(backgroundScope, multiEngine, configStore, sessions).also { runCurrent() }

    @Test
    fun `picking several videos creates a join in pick order`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(video.uri, other.uri), SourceKind.JOIN)
        runCurrent()
        val ready = c.state.value as ConversionState.Ready
        assertEquals(SourceKind.JOIN, ready.source.kind)
        assertEquals(listOf(video, other), ready.source.clips)
    }

    @Test
    fun `a single picked video in join mode is just a single input`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(video.uri), SourceKind.JOIN)
        runCurrent()
        assertEquals(SourceKind.SINGLE, (c.state.value as ConversionState.Ready).source.kind)
    }

    @Test
    fun `reordering moves the clip together with its kept range`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(video.uri, other.uri, v3.uri), SourceKind.JOIN)
        runCurrent()
        c.setClipRange(0, TrimSegment(1_000, 2_000))
        c.moveClip(0, 2)
        val ready = c.state.value as ConversionState.Ready
        assertEquals(listOf(other, v3, video), ready.source.clips)
        assertEquals(TrimSegment(1_000, 2_000), c.edit.value.clipRange(2, video.durationMs))
        assertEquals(TrimSegment(0, other.durationMs), c.edit.value.clipRange(0, other.durationMs))
    }

    @Test
    fun `removing down to one clip turns the join into a trimmed single input`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(video.uri, other.uri), SourceKind.JOIN)
        runCurrent()
        c.setClipRange(1, TrimSegment(500, 1_500))
        c.removeClip(0)
        val ready = c.state.value as ConversionState.Ready
        assertEquals(SourceKind.SINGLE, ready.source.kind)
        assertEquals(other, ready.primary)
        assertEquals(500L, c.edit.value.trimStartMs)
        assertEquals(1_500L, c.edit.value.trimEndMs)
    }

    @Test
    fun `added clips keep existing ranges index-aligned`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(video.uri, other.uri), SourceKind.JOIN)
        runCurrent()
        c.setClipRange(1, TrimSegment(100, 200))
        c.addFiles(listOf(v3.uri))
        runCurrent()
        assertEquals(3, (c.state.value as ConversionState.Ready).source.clips.size)
        assertEquals(TrimSegment(100, 200), c.edit.value.clipRange(1, other.durationMs))
        assertEquals(TrimSegment(0, v3.durationMs), c.edit.value.clipRange(2, v3.durationMs))
    }

    @Test
    fun `adding clips to a single trimmed video promotes it to a join keeping the trim`() = runTest {
        val c = multiController()
        pick(c, video)
        c.setTrim(2_000, 5_000)
        c.addFiles(listOf(other.uri))
        runCurrent()
        val ready = c.state.value as ConversionState.Ready
        assertEquals(SourceKind.JOIN, ready.source.kind)
        assertEquals(listOf(video, other), ready.source.clips)
        assertEquals(TrimSegment(2_000, 5_000), c.edit.value.clipRange(0, video.durationMs))
        assertEquals(TrimSegment(0, other.durationMs), c.edit.value.clipRange(1, other.durationMs))
        // The single-input trim no longer applies once the ranges live per clip.
        assertEquals(0L, c.edit.value.trimStartMs)
        assertEquals(0L, c.edit.value.trimEndMs)
        assertTrue(c.edit.value.segments.isEmpty())
    }

    @Test
    fun `adding clips to a stitched video turns each kept segment into its own clip`() = runTest {
        val c = multiController()
        pick(c, video)
        c.setSegments(listOf(TrimSegment(6_000, 8_000), TrimSegment(1_000, 2_000)))
        c.addFiles(listOf(other.uri))
        runCurrent()
        val ready = c.state.value as ConversionState.Ready
        assertEquals(listOf(video, video, other), ready.source.clips)
        // Segments are merged/sorted the same way the stitch would have played them.
        assertEquals(TrimSegment(1_000, 2_000), c.edit.value.clipRange(0, video.durationMs))
        assertEquals(TrimSegment(6_000, 8_000), c.edit.value.clipRange(1, video.durationMs))
        assertEquals(TrimSegment(0, other.durationMs), c.edit.value.clipRange(2, other.durationMs))
    }

    @Test
    fun `animated WebP cannot be joined but videos and GIFs can`() {
        val webp = SourceClip("content://w/1", "anim.webp", 100, durationMs = 1_000)
        assertTrue(!canAddClips(SourceMedia.single(webp)))
        assertTrue(canAddClips(SourceMedia.single(video)))
        assertTrue(canAddClips(SourceMedia.single(gif)))
    }

    @Test
    fun `an untrimmed single video joins with full-length ranges`() = runTest {
        val c = multiController()
        pick(c, video)
        c.addFiles(listOf(other.uri))
        runCurrent()
        assertEquals(TrimSegment(0, video.durationMs), c.edit.value.clipRange(0, video.durationMs))
    }

    @Test
    fun `image sequences are described as images and offer animated and MP4 outputs`() = runTest {
        val c = multiController()
        c.onFilesPicked(listOf(img1.uri, img2.uri), SourceKind.IMAGE_SEQUENCE)
        runCurrent()
        val ready = c.state.value as ConversionState.Ready
        assertEquals(SourceKind.IMAGE_SEQUENCE, ready.source.kind)
        assertEquals(
            listOf(OutputMediaFormat.WEBP, OutputMediaFormat.GIF, OutputMediaFormat.MP4),
            availableOutputFormats(ready.source),
        )
    }

    @Test
    fun `audio extraction is offered only for inputs with audio`() {
        assertTrue(OutputMediaFormat.AUDIO in availableOutputFormats(SourceMedia.single(video)))
        assertTrue(OutputMediaFormat.AUDIO !in availableOutputFormats(SourceMedia.single(other)))
        assertEquals(listOf(OutputMediaFormat.WEBP, OutputMediaFormat.GIF), availableOutputFormats(SourceMedia.single(gif)))
    }

    @Test
    fun `grabbing a frame reports the saved path or the failure`() = runTest {
        val c = controller()
        pick(c, video)
        c.grabFrame(1_234)
        runCurrent()
        assertEquals(FrameGrabState.Ready("/frame.png"), c.frameGrab.value)
        engine.grabResult = { throw IllegalStateException("no frame") }
        c.grabFrame(99_000)
        runCurrent()
        assertEquals(FrameGrabState.Failed("no frame"), c.frameGrab.value)
        c.dismissFrameGrab()
        assertEquals(FrameGrabState.Idle, c.frameGrab.value)
    }

    @Test
    fun `edit tools persist into the saved session`() = runTest {
        val c = controller()
        pick(c, video)
        c.updateEdit { copy(speed = 2f, rotationDegrees = 90, muted = true, reverse = true) }
        val restored = SessionCodec.decode(sessions.value)!!
        assertEquals(c.edit.value, restored.edit)
    }
}
