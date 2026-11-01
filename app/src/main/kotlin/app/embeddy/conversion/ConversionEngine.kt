package app.embeddy.conversion

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.UriStaging
import app.embeddy.util.deleteStaleEntries
import app.embeddy.viewmodel.rethrowIfCancellation
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import android.os.Build
import androidx.core.net.toUri

/**
 * What the Convert state machine needs from the media layer. An interface so
 * the controller can be unit-tested with a fake (no FFmpeg, no Context).
 */
interface ConvertEngine {
    /**
     * Take a persistable grant when possible, read the provider's name/size and
     * probe the media. Throws when the input cannot be read.
     */
    suspend fun describe(uri: String): SourceClip

    /** Like [describe] for a still image (dimensions from its header, no duration). */
    suspend fun describeImage(uri: String): SourceClip

    /** Run the conversion; the flow always ends with a terminal progress event. */
    fun convert(job: ConvertJob): Flow<ConversionProgress>

    /** Render a short animated-WebP preview of the current edit and settings. */
    fun preview(job: ConvertJob): Flow<ConversionProgress>

    /** Delete a file the engine produced (preview clips). */
    suspend fun deleteFile(path: String)

    /** Save the video frame at [positionMs] of [uri] as a PNG; returns its path. */
    suspend fun grabFrame(uri: String, positionMs: Long): String
}

/** One conversion request: the inputs, how they are edited, and how to encode. */
data class ConvertJob(
    val source: SourceMedia,
    val config: ConversionConfig,
    val edit: EditSpec,
)

/** Length of the preview clip rendered by [ConvertEngine.preview]. */
const val PREVIEW_DURATION_MS = 3_000L

/**
 * FFmpeg-kit backed [ConvertEngine]: animated WebP / video encoding with the
 * adaptive target-size loop from discwebp.
 */
class ConversionEngine(private val context: Context) : ConvertEngine {

    private val outputDir: File
        get() = File(context.cacheDir, "converted").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "temp").also { it.mkdirs() }

    override suspend fun describe(uri: String): SourceClip = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        // Persistable grants let the URI survive process death. Providers that
        // don't offer one (share-intent grants, GET_CONTENT) throw — non-fatal.
        try {
            context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val (fileName, fileSize) = FileInfoUtils.queryFileInfo(context, parsed)
        val info = probeInput(parsed)
        SourceClip(
            uri = uri,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            hasAudio = info.hasAudio,
        )
    }

    override suspend fun describeImage(uri: String): SourceClip = withContext(Dispatchers.IO) {
        val parsed = uri.toUri()
        try {
            context.contentResolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val (fileName, fileSize) = FileInfoUtils.queryFileInfo(context, parsed)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decodes always return null, so only the stream itself is
        // null-checked (chaining `?: throw` after decodeStream failed every image)
        val stream = context.contentResolver.openInputStream(parsed)
            ?: throw IllegalStateException("Cannot open $fileName")
        stream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IllegalStateException("$fileName is not a readable image")
        SourceClip(uri = uri, fileName = fileName, fileSize = fileSize, width = bounds.outWidth, height = bounds.outHeight)
    }

    /**
     * Extract basic metadata from the input URI.
     * Order: animated-WebP RIFF parse → MediaMetadataRetriever (fast) →
     * FFprobe over the SAF protocol (no copy) → FFprobe over a temp copy.
     * Throws [IllegalStateException] if every probe fails.
     */
    suspend fun probeInput(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        // Animated WebP first — neither MediaMetadataRetriever nor this FFmpeg
        // build reports its real frame count or duration.
        try {
            probeAnimatedWebP(uri)?.let { return@withContext it }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.w(e, "Animated WebP probe failed for %s", uri)
        }

        try {
            return@withContext probeWithRetriever(uri)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.w(e, "MediaMetadataRetriever failed for %s, trying FFprobe", uri)
        }

        try {
            safParameter(uri)?.let { saf -> return@withContext probeWithFfprobe(saf) }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.w(e, "FFprobe over SAF failed for %s, trying a temp copy", uri)
        }

        try {
            val copy = UriStaging.copyToTemp(context, uri, tempDir, "probe")
            try {
                return@withContext probeWithFfprobe(copy.absolutePath)
            } finally {
                copy.delete()
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.w(e, "FFprobe fallback also failed for %s", uri)
        }

        throw IllegalStateException(
            "Cannot read media info: file may be corrupted, unsupported, or inaccessible"
        )
    }

    /**
     * An ffmpeg-kit `saf:` URL for [uri], or null when the provider can't
     * supply one. FFmpeg then reads the content provider's file descriptor
     * directly — no multi-GB copy into the cache first.
     *
     * A fresh parameter is requested for every FFmpeg/FFprobe run: ffmpeg-kit
     * closes the descriptor when the session closes it.
     */
    private fun safParameter(uri: Uri): String? = try {
        FFmpegKitConfig.getSafParameterForRead(context, uri)
    } catch (e: Exception) {
        Timber.w(e, "No SAF parameter for %s", uri)
        null
    }

    /**
     * Probe an animated WebP straight from its RIFF container.
     * Returns null for anything that isn't one.
     */
    private fun probeAnimatedWebP(uri: Uri): MediaInfo? {
        if (!isAnimatedWebP(uri)) return null
        // Confirmed animated WebP — now the full read is justified, but still capped.
        val fullStream = context.contentResolver.openInputStream(uri) ?: return null
        val bytes = fullStream.use { stream -> readAtMost(stream, MAX_ANIMATED_WEBP_BYTES) }
        if (bytes == null) {
            Timber.w("Animated WebP exceeds %d bytes, skipping RIFF probe", MAX_ANIMATED_WEBP_BYTES)
            return null
        }
        val frameSet = WebPFrameSplitter.split(bytes) ?: return null
        return MediaInfo(
            width = frameSet.width,
            height = frameSet.height,
            durationMs = frameSet.totalDurationMs.toLong(),
            mimeType = "image/webp",
            frameCount = frameSet.frames.size,
            loopCount = frameSet.loopCount,
        )
    }

    /**
     * Sniff only the 21-byte RIFF header. Reading the whole stream up front
     * would allocate a picked 2 GB video on the heap and OutOfMemoryError (an
     * Error, not an Exception) would crash the app before any probe ran.
     */
    private fun isAnimatedWebP(uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { WebPFrameSplitter.isAnimatedWebP(it) } ?: false

    /**
     * Read a stream fully, or return null once it exceeds [maxBytes].
     * Guards heap allocation for reads whose size the ContentResolver can't report.
     */
    private fun readAtMost(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Probe using Android's MediaMetadataRetriever (fast, but fragile for some files). */
    private fun probeWithRetriever(uri: Uri): MediaInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            // FFmpeg autorotates on decode, so a portrait phone MP4 stored as
            // 1920x1080 + rotate=90 effectively converts at 1080x1920 — report
            // the display dimensions, keeping the rotation value for the UI.
            val (displayWidth, displayHeight) = normalizeDimensions(
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation,
            )
            return MediaInfo(
                width = displayWidth,
                height = displayHeight,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                rotation = rotation,
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "",
                // Key added in API 28; older devices report no count (0 = unknown)
                frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                } else 0,
                hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Probe using FFprobeKit — handles files that MediaMetadataRetriever rejects
     * (very-low-bitrate, missing audio track, unusual codec configurations).
     * [path] is a filesystem path or a `saf:` URL.
     */
    private fun probeWithFfprobe(path: String): MediaInfo {
        val session = FFprobeKit.getMediaInformation(path)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IllegalStateException("FFprobe failed with rc=${session.returnCode}")
        }
        val mediaInfo = session.mediaInformation
            ?: throw IllegalStateException("FFprobe returned no media information")
        val videoStream = mediaInfo.streams?.firstOrNull { it.type == "video" }
        val durationSec = mediaInfo.duration?.toDoubleOrNull() ?: 0.0
        // session.output is the raw `-print_format json` dump FFprobeKit parsed
        // MediaInformation from — rotation (side_data_list) and nb_frames are
        // only present there, not on the convenience accessors.
        val rawJson = session.output ?: ""
        val rotation = FfprobeJsonParser.rotationDegrees(rawJson)
        val (displayWidth, displayHeight) = normalizeDimensions(
            videoStream?.width?.toInt() ?: 0,
            videoStream?.height?.toInt() ?: 0,
            rotation,
        )
        return MediaInfo(
            width = displayWidth,
            height = displayHeight,
            durationMs = (durationSec * 1000).toLong(),
            bitrate = mediaInfo.bitrate?.toLongOrNull()?.toInt() ?: 0,
            rotation = rotation,
            mimeType = mediaInfo.format ?: "",
            frameCount = FfprobeJsonParser.frameCount(rawJson),
            hasAudio = mediaInfo.streams?.any { it.type == "audio" } == true,
        )
    }

    override fun convert(job: ConvertJob): Flow<ConversionProgress> = channelFlow {
        val workspace = Workspace()
        try {
            runConversion(job, workspace)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Timber.e(e, "Conversion failed")
            send(ConversionProgress.Failed(failureMessage(e)))
        } finally {
            workspace.cleanup()
        }
    }.flowOn(Dispatchers.IO)

    /** The adaptive encode loop behind [convert]. */
    private suspend fun ProducerScope<ConversionProgress>.runConversion(job: ConvertJob, workspace: Workspace) {
        val config = job.config
        val clip = job.source.clips.first()
        val target = resolveOutputTarget(config.outputFormat, clip.fileName)
        var prepared = prepareInputs(job, workspace, previewWindowMs = 0)
        // Joined/sequence outputs are named after the first clip plus the mode.
        val stem = clip.fileName.substringBeforeLast('.')
        val baseName = when (job.source.kind) {
            SourceKind.SINGLE -> stem
            SourceKind.JOIN -> "${stem}_joined"
            SourceKind.IMAGE_SEQUENCE -> "${stem}_sequence"
        }
        val safeName = FileInfoUtils.sanitizeFileName(baseName, fallback = "output")
        val finalOutput = File(outputDir, "${safeName}_embeddy.${target.extension}")
        val tempOutput = workspace.file("out", ".${target.extension}")

        val durationMs = outputDurationMs(job.edit, prepared.specs)
        if (job.edit.reverse) {
            require(reverseAllowed(durationMs)) { REVERSE_TOO_LONG }
        }
        val expectedFrames = expectedFrameCount(durationMs, config)
        val startTime = System.currentTimeMillis()
        // Audio-only output is a single fixed-bitrate pass; target size applies to pictures.
        val targetBytes = if (target == OutputTarget.AudioM4a) 0L else config.targetSizeBytes

        // Video in target-size mode: the size is a direct function of the
        // bitrate, so compute it instead of walking the quality ladder.
        val audioBps = if (includesAudio(request(prepared, job, target, tempOutput))) VIDEO_AUDIO_BITRATE_BPS else 0L
        var bitrate: Long? = if (target is OutputTarget.Video) {
            bitrateForTargetSize(targetBytes, durationMs, audioBps)
        } else null

        var quality = config.startQuality
        val minQuality = config.effectiveMinQuality
        var attempt = 1
        var best: Pair<Long, Int>? = null // size to quality
        var lastError: String? = null
        var producedEmpty = false

        Timber.d("Starting conversion: %s → %s (q=%d, min=%d, target=%d bytes)",
            clip.fileName, finalOutput.name, quality, minQuality, targetBytes)

        while (quality >= minQuality && (bitrate == null || attempt <= MAX_BITRATE_ATTEMPTS)) {
            send(ConversionProgress.Attempt(quality, attempt))
            val command = buildFfmpegCommand(
                request(prepared, job, target, tempOutput).copy(quality = quality, videoBitrateBps = bitrate),
            )
            Timber.d("FFmpeg command (attempt %d): %s", attempt, command)
            val error = executeFfmpeg(command) { stats ->
                trySend(
                    ConversionProgress.Progress(
                        fraction = progressFraction(stats.videoFrameNumber, stats.time, expectedFrames, durationMs),
                        currentQuality = quality,
                        attempt = attempt,
                        elapsedMs = System.currentTimeMillis() - startTime,
                    ),
                )
            }

            if (error != null) {
                // A provider that hands out a SAF parameter but can't actually be
                // read through it: retry this attempt once from temp copies.
                if (prepared.viaSaf) {
                    Timber.w("FFmpeg failed reading via SAF, retrying from temp copies")
                    prepared = prepared.copyStaged(workspace)
                    continue
                }
                lastError = error
                break
            }
            // SAF parameters are single-use: fetch fresh ones for the next attempt.
            if (prepared.viaSaf) prepared = prepared.refreshSaf()

            val fileSize = tempOutput.length()
            if (fileSize <= 0) {
                // FFmpeg exited 0 but wrote nothing (e.g. -ss past the end of the
                // input). A lower quality can't conjure frames.
                producedEmpty = true
                break
            }

            // Keep the best (smallest) output so far.
            tempOutput.copyTo(finalOutput, overwrite = true)
            best = fileSize to quality

            if (targetBytes <= 0 || fileSize <= targetBytes) {
                send(ConversionProgress.Complete(finalOutput.absolutePath, fileSize, quality))
                return
            }

            val sizeMb = String.format(Locale.getDefault(), "%.1f", fileSize / 1_000_000.0)
            send(ConversionProgress.SizeExceeded(fileSize, quality, sizeMb))
            attempt++
            val currentBitrate = bitrate
            if (currentBitrate != null) {
                // Bitrate mode: scale by the observed overshoot; stop at the floor.
                if (currentBitrate <= MIN_VIDEO_BITRATE_BPS) break
                bitrate = nextBitrateForTarget(currentBitrate, fileSize, targetBytes)
            } else {
                quality -= config.qualityStep
            }
        }

        val result = best
        if (result != null) {
            send(
                ConversionProgress.CompletedOversize(
                    outputPath = finalOutput.absolutePath,
                    fileSizeBytes = result.first,
                    targetSizeBytes = targetBytes,
                    qualityUsed = result.second,
                ),
            )
        } else {
            send(ConversionProgress.Failed(if (producedEmpty) NOTHING_KEPT else ffmpegErrorReason(lastError)))
        }
    }

    override fun preview(job: ConvertJob): Flow<ConversionProgress> = channelFlow {
        val workspace = Workspace()
        // previewOutput is NOT owned by the workspace: the caller displays it
        // and deletes it via deleteFile().
        val previewOutput = File(tempDir, "preview_${UUID.randomUUID()}.webp")
        try {
            val windowJob = previewJob(job, PREVIEW_DURATION_MS)
                ?: throw IllegalArgumentException(NOTHING_KEPT)
            // Previews are always animated WebP: PreviewCard renders via Coil,
            // which cannot animate an MP4, and a 3 s WebP is a faithful stand-in
            // for crop/rotate/scale/fps/quality checks either way.
            val previewJob = windowJob.copy(config = job.config.copy(outputFormat = OutputMediaFormat.WEBP))
            val windowEnd = previewJob.edit.keptRanges(previewJob.source.clips.first().durationMs)
                .maxOfOrNull { it.endMs } ?: 0L
            val prepared = prepareInputs(previewJob, workspace, previewWindowMs = windowEnd)
            val quality = job.config.startQuality
            send(ConversionProgress.Attempt(quality, 1))
            val durationMs = outputDurationMs(previewJob.edit, prepared.specs)
            val expectedFrames = expectedFrameCount(durationMs, job.config)
            val startTime = System.currentTimeMillis()
            val command = buildFfmpegCommand(request(prepared, previewJob, OutputTarget.AnimatedWebp, previewOutput))
            Timber.d("Preview FFmpeg command: %s", command)
            val error = executeFfmpeg(command) { stats ->
                trySend(
                    ConversionProgress.Progress(
                        fraction = progressFraction(stats.videoFrameNumber, stats.time, expectedFrames, durationMs),
                        currentQuality = quality,
                        attempt = 1,
                        elapsedMs = System.currentTimeMillis() - startTime,
                    ),
                )
            }
            if (error == null && previewOutput.length() > 0) {
                send(ConversionProgress.Complete(previewOutput.absolutePath, previewOutput.length(), quality))
            } else {
                previewOutput.delete()
                send(ConversionProgress.Failed(if (error != null) ffmpegErrorReason(error) else "Preview generation failed"))
            }
        } catch (e: Exception) {
            previewOutput.delete()
            e.rethrowIfCancellation()
            Timber.e(e, "Preview generation failed")
            send(ConversionProgress.Failed(failureMessage(e)))
        } finally {
            workspace.cleanup()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun deleteFile(path: String) {
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    override suspend fun grabFrame(uri: String, positionMs: Long): String = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri.toUri())
            // OPTION_CLOSEST decodes the exact frame (not the nearest keyframe),
            // matching what the paused player shows.
            val frame = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw IllegalStateException("No frame at ${positionMs}ms")
            val name = FileInfoUtils.sanitizeFileName(
                FileInfoUtils.queryFileName(context, uri.toUri())?.substringBeforeLast('.') ?: "frame",
                fallback = "frame",
            )
            val out = File(outputDir, "${name}_frame_${positionMs}ms.png")
            try {
                out.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                frame.recycle()
            }
            out.absolutePath
        } finally {
            retriever.release()
        }
    }

    /** Resolved FFmpeg inputs for one run. */
    private data class PreparedInputs(
        val specs: List<InputSpec>,
        val sourceLoopCount: Int? = null,
        /** Index of each spec that reads through a `saf:` URL, mapped to its clip URI. */
        val safClips: Map<Int, String> = emptyMap(),
    ) {
        val viaSaf: Boolean get() = safClips.isNotEmpty()
    }

    /** Replace every SAF input with a fresh SAF parameter (they are single-use). */
    private fun PreparedInputs.refreshSaf(): PreparedInputs {
        val fresh = specs.mapIndexed { i, spec ->
            val uri = safClips[i] ?: return@mapIndexed spec
            safParameter(uri.toUri())?.let { spec.copy(path = it) } ?: spec
        }
        return copy(specs = fresh)
    }

    /** Replace every SAF input with a staged temp copy (SAF reading failed). */
    private suspend fun PreparedInputs.copyStaged(workspace: Workspace): PreparedInputs {
        val staged = specs.mapIndexed { i, spec ->
            val uri = safClips[i] ?: return@mapIndexed spec
            spec.copy(path = workspace.stage(uri.toUri()).absolutePath)
        }
        return copy(specs = staged, safClips = emptyMap())
    }

    /**
     * Resolve the job's inputs for FFmpeg:
     * - image sequences are rendered to a PNG sequence + concat script;
     * - animated WebP is pre-decoded the same way (this FFmpeg build cannot
     *   demux it);
     * - everything else is read via the SAF protocol when the provider supports
     *   it, else from a temp copy.
     *
     * @param previewWindowMs >0 limits animated-WebP extraction to the frames a
     * preview of that length can show
     */
    private suspend fun prepareInputs(job: ConvertJob, workspace: Workspace, previewWindowMs: Long): PreparedInputs {
        if (job.source.kind == SourceKind.IMAGE_SEQUENCE) {
            val rendered = ImageSequenceRenderer(context, workspace.dir("images"))
                .render(job.source.clips.map { it.uri.toUri() }, job.edit.frameDurationMs)
            return PreparedInputs(
                listOf(
                    InputSpec(
                        path = rendered.concatScript.absolutePath,
                        isConcatScript = true,
                        width = rendered.width,
                        height = rendered.height,
                        durationMs = rendered.durationMs,
                    ),
                ),
            )
        }

        val specs = mutableListOf<InputSpec>()
        val safClips = mutableMapOf<Int, String>()
        var loopCount: Int? = null
        job.source.clips.forEachIndexed { index, clip ->
            val uri = clip.uri.toUri()
            val base = InputSpec(
                path = "",
                hasAudio = clip.hasAudio,
                width = clip.width,
                height = clip.height,
                durationMs = clip.durationMs,
            )
            if (isAnimatedWebP(uri)) {
                require(job.source.kind == SourceKind.SINGLE) { "Animated WebP files can't be joined with other clips" }
                val prepared = prepareAnimatedWebP(uri, base, workspace, previewWindowMs)
                loopCount = prepared.second
                specs += prepared.first
                return@forEachIndexed
            }
            val saf = safParameter(uri)
            if (saf != null) {
                safClips[index] = clip.uri
                specs += base.copy(path = saf)
            } else {
                specs += base.copy(path = workspace.stage(uri).absolutePath)
            }
        }
        return PreparedInputs(specs, loopCount, safClips)
    }

    /** Pre-decode an animated WebP into a concat-script input (or stage it raw on failure). */
    private suspend fun prepareAnimatedWebP(
        uri: Uri,
        base: InputSpec,
        workspace: Workspace,
        previewWindowMs: Long,
    ): Pair<InputSpec, Int?> {
        val staged = workspace.stage(uri)
        if (staged.length() <= MAX_ANIMATED_WEBP_BYTES) {
            val extracted = try {
                AnimatedWebPExtractor(workspace.dir("frames"))
                    .extract(staged.readBytes(), maxDurationMs = previewWindowMs)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Timber.w(e, "Animated WebP extraction failed, falling back to direct decode")
                null
            }
            if (extracted != null) {
                return base.copy(
                    path = extracted.concatScript.absolutePath,
                    isConcatScript = true,
                    // Pre-decoded frame sequences never carry audio.
                    hasAudio = false,
                    width = extracted.width,
                    height = extracted.height,
                    durationMs = extracted.totalDurationMs.toLong(),
                ) to extracted.loopCount
            }
        }
        return base.copy(path = staged.absolutePath) to null
    }

    private fun request(inputs: PreparedInputs, job: ConvertJob, target: OutputTarget, output: File) =
        CommandRequest(
            inputs = inputs.specs,
            outputPath = output.absolutePath,
            target = target,
            config = job.config,
            edit = job.edit,
            sourceLoopCount = inputs.sourceLoopCount,
        )

    /**
     * Temp files and directories owned by one conversion/preview run; all of
     * them are removed in [cleanup], whatever way the run ends.
     */
    private inner class Workspace {
        private val owned = mutableListOf<File>()
        private val staged = mutableMapOf<Uri, File>()

        fun file(prefix: String, suffix: String): File =
            File(tempDir, "${prefix}_${UUID.randomUUID()}$suffix").also { owned += it }

        fun dir(prefix: String): File =
            File(tempDir, "${prefix}_${UUID.randomUUID()}").also { it.mkdirs(); owned += it }

        /** Copy [uri] once per run (cancellable) and reuse the copy afterwards. */
        suspend fun stage(uri: Uri): File =
            staged[uri] ?: UriStaging.copyToTemp(context, uri, tempDir, "input")
                .also { staged[uri] = it; owned += it }

        fun cleanup() {
            owned.forEach { it.deleteRecursively() }
            owned.clear()
            staged.clear()
        }
    }

    /**
     * Execute an FFmpeg command with a statistics callback.
     * Returns null on success, or the tail of FFmpeg's output on failure.
     * Cancelling the coroutine cancels the FFmpeg session.
     */
    private suspend fun executeFfmpeg(
        command: String,
        onStatistics: (Statistics) -> Unit,
    ): String? = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                val returnCode = session.returnCode
                if (ReturnCode.isSuccess(returnCode)) {
                    if (cont.isActive) cont.resume(null)
                } else {
                    val tail = session.output?.takeLast(500) ?: "no output"
                    Timber.e("FFmpeg FAILED (rc=%s): %s", returnCode, tail)
                    if (cont.isActive) cont.resume(tail)
                }
            },
            { log -> Timber.v("FFmpeg: %s", log.message) },
            { stats -> onStatistics(stats) },
        )
        cont.invokeOnCancellation { session.cancel() }
    }

    private fun failureMessage(e: Exception): String = when (e) {
        is IllegalArgumentException -> e.message ?: NOTHING_KEPT
        else -> e.message ?: "Unknown error"
    }

    /**
     * Log which encoders this FFmpeg build ships. Diagnostics only — runs a
     * synchronous FFmpeg session, so callers gate it to debug builds.
     */
    fun checkEncoderAvailability(): Boolean {
        val output = FFmpegKit.execute("-hide_banner -encoders").output ?: return false
        val hasWebpAnim = output.contains("libwebp_anim")
        Timber.i(
            "FFmpeg encoder check: libwebp_anim=%b, libopenh264=%b, libvpx=%b, gif=%b",
            hasWebpAnim, output.contains("libopenh264"), output.contains("libvpx"), output.contains(" gif "),
        )
        return hasWebpAnim
    }

    /** Remove converted outputs older than 24 h and every abandoned temp entry. */
    fun cleanupOldFiles() {
        deleteStaleEntries(outputDir, System.currentTimeMillis() - STALE_AFTER_MS)
        // Nothing in temp/ outlives its run; anything left is from a killed process.
        deleteStaleEntries(tempDir, Long.MAX_VALUE)
    }

    private companion object {
        /**
         * Cap on how much of an animated WebP is loaded onto the heap for
         * splitting/probing — anything larger is skipped rather than risking
         * OutOfMemoryError (which no `catch (e: Exception)` would intercept).
         */
        const val MAX_ANIMATED_WEBP_BYTES = 100_000_000

        /**
         * Encode attempts in video bitrate mode. Each retry rescales by the
         * observed overshoot, so two retries converge; the cap bounds the
         * worst case (rate control ignoring the bitrate entirely).
         */
        const val MAX_BITRATE_ATTEMPTS = 4

        const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

        const val NOTHING_KEPT = "Nothing was encoded — check that the trim range overlaps the video"

        const val REVERSE_TOO_LONG = "Reverse works on outputs up to 10 seconds — trim the clip or raise the speed"
    }
}

data class MediaInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val bitrate: Int = 0,
    val rotation: Int = 0,
    val mimeType: String = "",
    val frameCount: Int = 0,
    /** Source animation loop count (0 = infinite/unknown); set for animated WebP. */
    val loopCount: Int = 0,
    /** True when the container carries an audio track (always false for GIF/WebP). */
    val hasAudio: Boolean = false,
)

/**
 * Swap probed width/height when rotation metadata indicates a ±90° display
 * rotation. FFmpeg autorotates on decode, so the effective dimensions of a
 * portrait phone MP4 (stored 1920x1080 + rotate=90) are 1080x1920 — reporting
 * the stored values transposes every displayed dimension and size estimate.
 */
internal fun normalizeDimensions(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) height to width else width to height
}

/**
 * The edit restricted to the first [windowMs] of OUTPUT time, or null when
 * the edit keeps nothing. Previews used to start at the first segment and run
 * 3 s regardless of the trim end or the gaps between segments, so they showed
 * frames the real conversion would cut. Speed is accounted for: a 2x edit
 * previews 6 s of source to fill a 3 s window.
 */
internal fun previewEdit(edit: EditSpec, sourceDurationMs: Long, windowMs: Long): EditSpec? {
    val ranges = edit.keptRanges(sourceDurationMs)
    if (ranges.isEmpty()) return null
    var remaining = (windowMs * edit.effectiveSpeed).toLong()
    val window = mutableListOf<TrimSegment>()
    for (range in ranges) {
        if (remaining <= 0) break
        // Open-ended range (unknown duration): take the window from its start.
        val length = if (range.endMs > range.startMs) range.durationMs else remaining
        val take = minOf(length, remaining)
        window += range.copy(endMs = range.startMs + take)
        remaining -= take
    }
    return edit.copy(trimStartMs = 0, trimEndMs = 0, segments = window)
}

/**
 * [job] cut down to its first [windowMs] of output, or null when nothing is
 * kept. Joined clips beyond the window are dropped (with their ranges, so the
 * remaining ranges stay index-aligned); image sequences keep the frames that
 * fit the window.
 */
internal fun previewJob(job: ConvertJob, windowMs: Long): ConvertJob? {
    val edit = job.edit
    return when (job.source.kind) {
        SourceKind.SINGLE -> previewEdit(edit, job.source.clips.first().durationMs, windowMs)
            ?.let { job.copy(edit = it) }
        SourceKind.JOIN -> {
            var remaining = (windowMs * edit.effectiveSpeed).toLong()
            val clips = mutableListOf<SourceClip>()
            val ranges = mutableListOf<TrimSegment>()
            job.source.clips.forEachIndexed { i, clip ->
                if (remaining <= 0) return@forEachIndexed
                val range = edit.clipRange(i, clip.durationMs)
                val take = minOf(range.durationMs, remaining)
                if (take <= 0) return@forEachIndexed
                clips += clip
                ranges += range.copy(endMs = range.startMs + take)
                remaining -= take
            }
            when (clips.size) {
                0 -> null
                // One surviving clip is a plain trim of that clip.
                1 -> job.copy(
                    source = SourceMedia.single(clips.first()),
                    edit = edit.copy(
                        trimStartMs = ranges.first().startMs,
                        trimEndMs = ranges.first().endMs,
                        segments = emptyList(),
                        clipRanges = emptyList(),
                    ),
                )
                else -> job.copy(source = SourceMedia(SourceKind.JOIN, clips), edit = edit.copy(clipRanges = ranges))
            }
        }
        SourceKind.IMAGE_SEQUENCE -> {
            val frameMs = ConcatScript.clampDuration(edit.frameDurationMs).toLong()
            val count = ((windowMs * edit.effectiveSpeed) / frameMs).toInt().coerceIn(1, job.source.clips.size)
            job.copy(source = job.source.copy(clips = job.source.clips.take(count)))
        }
    }
}

/**
 * Targeted extraction of scalar fields from FFprobe's `-print_format json`
 * output that FFprobeKit's convenience accessors do not surface.
 *
 * Regex-over-JSON is deliberate: `org.json` is an unmocked stub in plain JVM
 * unit tests and only two well-known scalar fields are needed. Both patterns
 * accept quoted and unquoted values, matching ffprobe's actual emissions.
 */
internal object FfprobeJsonParser {
    private val ROTATION_REGEX = Regex("\"rotation\"\\s*:\\s*\"?(-?\\d+)\"?")
    private val NB_FRAMES_REGEX = Regex("\"nb_frames\"\\s*:\\s*\"?(\\d+)\"?")

    /**
     * Display rotation in MediaMetadataRetriever convention (0/90/180/270).
     * ffprobe's side_data_list rotation is counter-clockwise-positive, while
     * MMR reports the clockwise rotation to apply — negate and normalize.
     */
    fun rotationDegrees(ffprobeJson: String): Int {
        val raw = ROTATION_REGEX.find(ffprobeJson)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
        return ((-raw) % 360 + 360) % 360
    }

    /** First stream's `nb_frames`, or 0 when the container doesn't declare it. */
    fun frameCount(ffprobeJson: String): Int =
        NB_FRAMES_REGEX.find(ffprobeJson)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/** Progress events emitted during conversion. */
sealed interface ConversionProgress {
    data class Attempt(val quality: Int, val attemptNumber: Int) : ConversionProgress
    data class Progress(
        val fraction: Float,
        val currentQuality: Int,
        val attempt: Int,
        val elapsedMs: Long,
    ) : ConversionProgress
    data class SizeExceeded(
        val actualBytes: Long,
        val quality: Int,
        val sizeMbFormatted: String,
    ) : ConversionProgress
    data class Complete(
        val outputPath: String,
        val fileSizeBytes: Long,
        val qualityUsed: Int,
    ) : ConversionProgress
    /** Conversion produced output but it exceeds the target size. */
    data class CompletedOversize(
        val outputPath: String,
        val fileSizeBytes: Long,
        val targetSizeBytes: Long,
        val qualityUsed: Int,
    ) : ConversionProgress
    data class Failed(val message: String) : ConversionProgress
}
