package app.embeddy.conversion

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.VisibleForTesting
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.SizeEstimation
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

/**
 * Wraps FFmpeg-kit to convert media files to animated WebP.
 * Implements the adaptive-quality algorithm from discwebp:
 * try at startQuality, reduce by qualityStep until file fits targetSizeBytes or minQuality reached.
 */
class ConversionEngine(private val context: Context) {

    private val cacheDir: File
        get() = File(context.cacheDir, "converted").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "temp").also { it.mkdirs() }

    /**
     * Extract basic metadata from the input URI.
     * Primary: MediaMetadataRetriever (fast, no temp file).
     * Fallback: FFprobeKit (copies to temp file, handles edge cases
     * like very-low-bitrate or audio-less files that MMR rejects).
     * Throws [IllegalStateException] if both probes fail.
     */
    suspend fun probeInput(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        // Animated WebP first — neither MediaMetadataRetriever nor this FFmpeg
        // build reports its real frame count or duration.
        try {
            probeAnimatedWebP(uri)?.let { return@withContext it }
        } catch (e: Exception) {
            Timber.w(e, "Animated WebP probe failed for %s", uri)
        }

        // Try MediaMetadataRetriever first (fast, no temp file needed)
        try {
            return@withContext probeWithRetriever(uri)
        } catch (e: Exception) {
            Timber.w(e, "MediaMetadataRetriever failed for %s, trying FFprobe", uri)
        }

        // Fallback: copy to temp file and probe with FFprobeKit
        try {
            return@withContext probeWithFfprobe(uri)
        } catch (e: Exception) {
            Timber.w(e, "FFprobe fallback also failed for %s", uri)
        }

        // Both probes failed — throw so the UI can show a meaningful error
        throw IllegalStateException(
            "Cannot read media info: file may be corrupted, unsupported, or inaccessible"
        )
    }

    /**
     * Probe an animated WebP straight from its RIFF container.
     * Returns null for anything that isn't one.
     */
    private fun probeAnimatedWebP(uri: Uri): MediaInfo? {
        // Sniff only the 21-byte header first — reading the whole stream up front
        // would allocate a picked 2 GB video on the Java heap and OutOfMemoryError
        // (an Error, not an Exception) would crash the app before any probe ran.
        val animated = context.contentResolver.openInputStream(uri)?.use { stream ->
            WebPFrameSplitter.isAnimatedWebP(stream)
        } ?: return null
        if (!animated) return null

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
            bitrate = 0,
            rotation = 0,
            mimeType = "image/webp",
            frameCount = frameSet.frames.size,
            loopCount = frameSet.loopCount,
        )
    }

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
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_BITRATE
            )?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val mimeType = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            ) ?: ""
            val frameCount = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
            )?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
            ) == "yes"
            // FFmpeg autorotates on decode, so a portrait phone MP4 stored as
            // 1920x1080 + rotate=90 effectively converts at 1080x1920 — report
            // the display dimensions, keeping the rotation value for the UI.
            val (displayWidth, displayHeight) = normalizeDimensions(width, height, rotation)
            return MediaInfo(
                width = displayWidth,
                height = displayHeight,
                durationMs = durationMs,
                bitrate = bitrate,
                rotation = rotation,
                mimeType = mimeType,
                frameCount = frameCount,
                hasAudio = hasAudio,
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Probe using FFprobeKit — handles files that MediaMetadataRetriever rejects
     * (very-low-bitrate, missing audio track, unusual codec configurations).
     * Requires copying the URI to a temp file for FFprobe access.
     */
    private suspend fun probeWithFfprobe(uri: Uri): MediaInfo {
        val tempFile = copyUriToTemp(uri)
        try {
            val session = FFprobeKit.getMediaInformation(tempFile.absolutePath)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFprobe failed with rc=${session.returnCode}")
            }
            val mediaInfo = session.mediaInformation
                ?: throw IllegalStateException("FFprobe returned no media information")

            val videoStream = mediaInfo.streams
                ?.firstOrNull { it.type == "video" }
            val hasAudio = mediaInfo.streams?.any { it.type == "audio" } == true

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
                hasAudio = hasAudio,
            )
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Convert input URI to the format selected in [ConversionConfig.outputFormat]:
     * animated WebP (default) or a video container (MP4 / Original).
     * When targetSizeBytes > 0, uses adaptive quality (reduce quality until file
     * fits — for video outputs the quality maps to a target bitrate).
     * When targetSizeBytes == 0, encodes once at startQuality with no size check.
     * Emits [ConversionProgress] updates and returns the output file path on success.
     *
     * @param sourceFileName the input's display name WITH extension — drives the
     * container mapping for [OutputMediaFormat.ORIGINAL].
     */
    fun convert(
        inputUri: Uri,
        config: ConversionConfig,
        outputName: String,
        sourceFileName: String = "",
    ): Flow<ConversionProgress> = callbackFlow {
        // Copy input to a temp file so FFmpeg can access it directly
        val inputFile = copyUriToTemp(inputUri)
        val container = resolveOutputContainer(config.outputFormat, sourceFileName)
        val outputExtension = container?.extension ?: "webp"
        val tempOutput = File(tempDir, "temp_${UUID.randomUUID()}.$outputExtension")
        val safeName = FileInfoUtils.sanitizeFileName(outputName, fallback = "output")
        val finalOutput = File(cacheDir, "${safeName}_embeddy.$outputExtension")

        // Animated WebP can't be demuxed by this FFmpeg build — pre-decode it to
        // a PNG sequence and feed that in via the concat demuxer instead.
        val frameDir = File(tempDir, "frames_${UUID.randomUUID()}").also { it.mkdirs() }
        val extracted = try {
            extractAnimatedWebP(inputFile, frameDir)
        } catch (e: Exception) {
            Timber.w(e, "Animated WebP extraction failed, falling back to direct decode")
            null
        }

        var quality = config.startQuality
        val minQuality = config.effectiveMinQuality
        var attempt = 1
        val startTime = System.currentTimeMillis()
        Timber.d("Starting conversion: %s → %s (q=%d, min=%d, target=%d bytes)",
            inputFile.name, finalOutput.name, quality, minQuality, config.targetSizeBytes)

        // Probe once for duration (progress), dimensions (bitrate derivation),
        // and the audio track (video-output graphs).
        val probed = try {
            probeInput(inputUri)
        } catch (e: Exception) {
            Timber.w(e, "Probe for conversion metadata failed, continuing without")
            null
        }
        // Pre-decoded frame sequences never carry audio, whatever the probe says.
        val hasAudio = container != null && extracted == null && probed?.hasAudio == true

        // Get duration for progress calculation.
        // In stitch mode, FFmpeg outputs only the kept segments so use their
        // combined duration; otherwise fall back to the probed full duration.
        val durationMs = config.totalKeptDurationMs.takeIf { it > 0 }
            ?: probed?.durationMs
            ?: 0L

        // Estimate total frames for frame-count-based progress
        // (more reliable than stats.time for libwebp_anim encoder)
        val expectedFrames = if (durationMs > 0) {
            ((durationMs.toDouble() / 1000.0) * config.fps).toLong()
        } else {
            0L
        }

        try {
            // Track the best (smallest) successful output across all attempts.
            // If a later attempt fails, we still have a usable result.
            var bestOutputSize = Long.MAX_VALUE
            var bestQuality = config.startQuality
            var hasAnyOutput = false
            var producedEmptyOutput = false
            var lastFfmpegError: String? = null

            while (quality >= minQuality) {
                send(ConversionProgress.Attempt(quality, attempt))

                val command = buildFfmpegCommand(
                    inputPath = inputFile.absolutePath,
                    outputPath = tempOutput.absolutePath,
                    config = config,
                    quality = quality,
                    concatScript = extracted?.concatScript,
                    sourceLoopCount = extracted?.loopCount,
                    container = container,
                    hasAudio = hasAudio,
                    sourceWidth = probed?.width ?: 0,
                    sourceHeight = probed?.height ?: 0,
                )
                Timber.d("FFmpeg command (q=%d): %s", quality, command)

                val ffmpegError = executeFfmpeg(command) { stats ->
                    // Use whichever metric is further along — libwebp_anim
                    // may not report stats.time correctly, but videoFrameNumber works
                    val progress = when {
                        expectedFrames > 0 && durationMs > 0 -> {
                            val frameProg = stats.videoFrameNumber.toFloat() / expectedFrames
                            val timeProg = stats.time.toFloat() / durationMs
                            maxOf(frameProg, timeProg).coerceIn(0f, 1f)
                        }
                        expectedFrames > 0 -> {
                            (stats.videoFrameNumber.toFloat() / expectedFrames).coerceIn(0f, 1f)
                        }
                        durationMs > 0 -> {
                            (stats.time.toFloat() / durationMs).coerceIn(0f, 1f)
                        }
                        else -> 0f
                    }
                    trySend(
                        ConversionProgress.Progress(
                            fraction = progress,
                            currentQuality = quality,
                            attempt = attempt,
                            elapsedMs = System.currentTimeMillis() - startTime,
                        )
                    )
                }

                if (ffmpegError != null) {
                    // FFmpeg failed — save error for reporting, but don't
                    // send Failed yet if we have output from a prior attempt
                    lastFfmpegError = ffmpegError
                    break
                }

                val fileSize = tempOutput.length()
                if (fileSize <= 0) {
                    // FFmpeg exited 0 but wrote nothing (e.g. -ss past the end of
                    // the input). Retrying at a lower quality can't conjure frames,
                    // and emitting Complete here would hand the UI a path to a file
                    // that was never written.
                    producedEmptyOutput = true
                    break
                }

                // Save best output so far (smallest file = lowest quality)
                tempOutput.copyTo(finalOutput, overwrite = true)
                bestOutputSize = fileSize
                bestQuality = quality
                hasAnyOutput = true

                // No target size → one-shot encode, skip adaptive loop
                if (config.targetSizeBytes <= 0 || fileSize <= config.targetSizeBytes) {
                    tempOutput.delete()
                    inputFile.delete()
                    send(
                        ConversionProgress.Complete(
                            outputPath = finalOutput.absolutePath,
                            fileSizeBytes = finalOutput.length(),
                            qualityUsed = quality,
                        )
                    )
                    close()
                    return@callbackFlow
                }

                // Doesn't fit — reduce quality and retry
                val sizeMb = String.format(Locale.getDefault(), "%.1f", fileSize / 1_000_000.0)
                send(ConversionProgress.SizeExceeded(fileSize, quality, sizeMb))
                quality -= config.qualityStep
                attempt++
            }

            // Loop ended: either exhausted quality range or FFmpeg error.
            // Always offer the best output we managed to produce.
            tempOutput.delete()
            inputFile.delete()
            if (hasAnyOutput) {
                send(
                    ConversionProgress.CompletedOversize(
                        outputPath = finalOutput.absolutePath,
                        fileSizeBytes = bestOutputSize,
                        targetSizeBytes = config.targetSizeBytes,
                        qualityUsed = bestQuality,
                    )
                )
            } else {
                // Extract a user-friendly reason from FFmpeg's output
                val reason = if (producedEmptyOutput) {
                    "Nothing was encoded — check that the trim range overlaps the video"
                } else {
                    extractErrorReason(lastFfmpegError)
                }
                send(ConversionProgress.Failed(reason))
            }
        } catch (e: Exception) {
            Timber.e(e, "Conversion failed")
            send(ConversionProgress.Failed(e.message ?: "Unknown error"))
        } finally {
            tempOutput.delete()
            inputFile.delete()
            frameDir.deleteRecursively()
        }

        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Execute an FFmpeg command with a statistics callback.
     * Returns null on success, or the error message on failure.
     */
    private suspend fun executeFfmpeg(
        command: String,
        onStatistics: (Statistics) -> Unit,
    ): String? = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeAsync(
            command,
            { session ->
                val returnCode = session.returnCode
                val success = ReturnCode.isSuccess(returnCode)
                if (!success) {
                    val tail = session.output?.takeLast(500) ?: "no output"
                    Timber.e("FFmpeg FAILED (rc=%s): %s", returnCode, tail)
                    if (cont.isActive) cont.resume(tail)
                } else {
                    if (cont.isActive) cont.resume(null)
                }
            },
            { log -> Timber.v("FFmpeg: %s", log.message) },
            { stats -> onStatistics(stats) },
        )

        cont.invokeOnCancellation {
            session.cancel()
        }
    }

    /**
     * Pre-decode an animated WebP into a PNG sequence, or return null when the
     * input isn't one. Only the RIFF header is read for files that aren't WebP,
     * so this costs nothing for the common video/GIF case.
     */
    private fun extractAnimatedWebP(
        inputFile: File,
        frameDir: File,
    ): AnimatedWebPExtractor.Extracted? {
        // Cheap header sniff before loading the whole file into memory.
        val animated = inputFile.inputStream().use { stream ->
            WebPFrameSplitter.isAnimatedWebP(stream)
        }
        if (!animated) return null

        if (inputFile.length() > MAX_ANIMATED_WEBP_BYTES) {
            Timber.w(
                "Animated WebP too large to pre-decode (%d bytes), falling back to direct decode",
                inputFile.length(),
            )
            return null
        }

        Timber.d("Input is an animated WebP — pre-decoding frames for FFmpeg")
        return AnimatedWebPExtractor(frameDir).extract(inputFile.readBytes())
    }

    /** Copy a content:// URI to a temp file for FFmpeg access. */
    private suspend fun copyUriToTemp(uri: Uri): File = withContext(Dispatchers.IO) {
        val tempFile = File(tempDir, "input_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open URI: $uri")
        tempFile
    }

    /**
     * Generate a short preview clip (~3 seconds) using the current settings.
     * Single-pass at the given quality — no adaptive loop, no target size check.
     * Returns the output file path on success, or null on failure.
     */
    fun generatePreview(
        inputUri: Uri,
        config: ConversionConfig,
        previewDurationSec: Double = 3.0,
    ): Flow<ConversionProgress> = callbackFlow {
        val inputFile = copyUriToTemp(inputUri)
        val previewOutput = File(tempDir, "preview_${UUID.randomUUID()}.webp")
        // Animated WebP needs the same pre-decode as convert() — FFmpeg can't
        // demux it, so feeding the raw file would preview a single-frame still.
        val frameDir = File(tempDir, "frames_${UUID.randomUUID()}").also { it.mkdirs() }

        try {
            val extracted = try {
                extractAnimatedWebP(inputFile, frameDir)
            } catch (e: Exception) {
                Timber.w(e, "Animated WebP extraction failed for preview, falling back to direct decode")
                null
            }

            // Determine the preview start point from trim/segment settings
            val startMs = config.segments.firstOrNull()?.startMs
                ?: config.trimStartMs.takeIf { it > 0 }
                ?: 0L

            val previewConfig = config.copy(
                // Override trim to only produce previewDurationSec of output
                trimStartMs = startMs,
                trimEndMs = startMs + (previewDurationSec * 1000).toLong(),
                segments = emptyList(), // Force single-segment mode for preview
                // Previews are always animated WebP: PreviewCard renders via
                // Coil, which cannot animate an MP4, and a 3s WebP is a faithful
                // stand-in for scale/crop/fps/quality checks either way.
                outputFormat = OutputMediaFormat.WEBP,
            )

            val durationMs = (previewDurationSec * 1000).toLong()
            val expectedFrames = (previewDurationSec * config.fps).toLong()

            send(ConversionProgress.Attempt(config.startQuality, 1))

            val command = buildFfmpegCommand(
                inputPath = inputFile.absolutePath,
                outputPath = previewOutput.absolutePath,
                config = previewConfig,
                quality = config.startQuality,
                concatScript = extracted?.concatScript,
                sourceLoopCount = extracted?.loopCount,
            )
            Timber.d("Preview FFmpeg command (q=%d): %s", config.startQuality, command)

            val previewError = executeFfmpeg(command) { stats ->
                val progress = when {
                    expectedFrames > 0 && durationMs > 0 -> {
                        val frameProg = stats.videoFrameNumber.toFloat() / expectedFrames
                        val timeProg = stats.time.toFloat() / durationMs
                        maxOf(frameProg, timeProg).coerceIn(0f, 1f)
                    }
                    expectedFrames > 0 -> {
                        (stats.videoFrameNumber.toFloat() / expectedFrames).coerceIn(0f, 1f)
                    }
                    durationMs > 0 -> {
                        (stats.time.toFloat() / durationMs).coerceIn(0f, 1f)
                    }
                    else -> 0f
                }
                trySend(
                    ConversionProgress.Progress(
                        fraction = progress,
                        currentQuality = config.startQuality,
                        attempt = 1,
                        elapsedMs = 0,
                    )
                )
            }

            if (previewError == null && previewOutput.exists() && previewOutput.length() > 0) {
                send(
                    ConversionProgress.Complete(
                        outputPath = previewOutput.absolutePath,
                        fileSizeBytes = previewOutput.length(),
                        qualityUsed = config.startQuality,
                    )
                )
            } else {
                previewOutput.delete()
                val reason = if (previewError != null) extractErrorReason(previewError) else "Preview generation failed"
                send(ConversionProgress.Failed(reason))
            }
        } catch (e: Exception) {
            Timber.e(e, "Preview generation failed")
            previewOutput.delete()
            send(ConversionProgress.Failed(e.message ?: "Unknown error"))
        } finally {
            inputFile.delete()
            frameDir.deleteRecursively()
            // Note: previewOutput is NOT deleted here — caller owns cleanup
        }

        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Extract a user-friendly error reason from FFmpeg's raw output.
     * Detects common failure patterns and returns a readable message.
     */
    private fun extractErrorReason(ffmpegOutput: String?): String {
        if (ffmpegOutput == null) return "Conversion failed — no output from FFmpeg"
        return when {
            "Unknown encoder" in ffmpegOutput ->
                "Encoder not available — this FFmpeg build may lack the required codec. " +
                "FFmpeg says: ${ffmpegOutput.substringAfter("Unknown encoder").take(80).trim()}"
            "No such filter" in ffmpegOutput ->
                "FFmpeg filter not found: ${ffmpegOutput.substringAfter("No such filter").take(40).trim()}"
            "Invalid data found" in ffmpegOutput ->
                "File appears corrupted or uses an unsupported codec"
            "Permission denied" in ffmpegOutput ->
                "Cannot access file — permission denied"
            "does not contain" in ffmpegOutput && "stream" in ffmpegOutput ->
                "File does not contain a video stream"
            "Out of memory" in ffmpegOutput || "Cannot allocate" in ffmpegOutput ->
                "Out of memory — try a shorter clip or lower resolution"
            else -> {
                // Last ~120 chars as fallback
                val tail = ffmpegOutput.takeLast(120).trim()
                "FFmpeg error: $tail"
            }
        }
    }

    /**
     * Check if the libwebp_anim encoder is available in this FFmpeg build.
     * Useful for diagnostics — call once at app startup and log the result.
     */
    fun checkEncoderAvailability(): Boolean {
        val session = FFmpegKit.execute("-hide_banner -encoders")
        val output = session.output ?: return false
        val hasWebpAnim = output.contains("libwebp_anim")
        val hasWebp = output.contains("libwebp")
        val hasOpenH264 = output.contains("libopenh264")
        val hasVpx = output.contains("libvpx")
        Timber.i(
            "FFmpeg encoder check: libwebp_anim=%b, libwebp=%b, libopenh264=%b, libvpx=%b",
            hasWebpAnim, hasWebp, hasOpenH264, hasVpx,
        )
        if (!hasWebpAnim) {
            Timber.w("libwebp_anim encoder NOT available — animated WebP output will fail")
        }
        return hasWebpAnim
    }

    /** Cleanup old converted files older than 24 hours. */
    fun cleanupOldFiles() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        cacheDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        // deleteRecursively so abandoned frame_* extraction dirs go too
        tempDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private companion object {
        /**
         * Cap on how much of an animated WebP is loaded onto the heap for
         * splitting/probing — anything larger is skipped rather than risking
         * OutOfMemoryError (which no `catch (e: Exception)` would intercept).
         */
        const val MAX_ANIMATED_WEBP_BYTES = 100_000_000
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
 * Resolve the loop count passed to `-loop`.
 *
 * `configLoop` 0 means infinite; when the user has left it at the default and
 * the source (an animated WebP) declares a finite loop count, preserve it so a
 * play-once input doesn't silently become an infinite loop.
 *
 * TODO: 0 is ambiguous between "untouched default" and "explicitly infinite" —
 * forcing infinite loop on a finite-loop source needs a UI-level tri-state
 * (Auto/Infinite/N); see the WS-G handoff notes for M76.
 */
internal fun resolveLoopCount(configLoop: Int, sourceLoopCount: Int?): Int =
    if (configLoop == 0 && sourceLoopCount != null && sourceLoopCount > 0) {
        sourceLoopCount
    } else {
        configLoop
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

/**
 * Format a millisecond timestamp as an FFmpeg seconds argument.
 * Always uses [Locale.US] — FFmpeg only accepts `.` as the decimal separator,
 * and the default locale would emit `1,500` on e.g. de-DE/fr-FR devices.
 */
private fun formatSeconds(millis: Long): String =
    String.format(Locale.US, "%.3f", millis / 1000.0)

/**
 * Format a segment volume for the `volume=` audio filter, clamped to the UI
 * range 0..2. [Locale.US] for the same decimal-separator reason as
 * [formatSeconds].
 */
private fun formatVolume(volume: Float): String =
    String.format(Locale.US, "%.3f", volume.coerceIn(0f, 2f))

/**
 * Build the core video filter chain (scale, fps, denoise, sharpen, dither).
 * Shared between single-segment and multi-segment (stitching) modes.
 *
 * @param forVideoOutput true when encoding to a video container — the
 * palettegen/paletteuse dither chain is a palette-format (WebP/GIF) concept
 * and must not leak into H.264/VP8 encodes.
 */
private fun buildVideoFilters(config: ConversionConfig, forVideoOutput: Boolean = false): String {
    return buildList {
        // Scaling: exact dimensions take precedence over maxDimension
        if (config.exactWidth > 0 && config.exactHeight > 0) {
            add("scale=${config.exactWidth}:${config.exactHeight}:force_original_aspect_ratio=increase:flags=lanczos")
            add("crop=${config.exactWidth}:${config.exactHeight}")
        } else if (config.exactWidth > 0) {
            add("scale=${config.exactWidth}:-2:flags=lanczos")
        } else if (config.exactHeight > 0) {
            add("scale=-2:${config.exactHeight}:flags=lanczos")
        } else {
            add("scale='min(${config.maxDimension},iw)':'min(${config.maxDimension},ih)':force_original_aspect_ratio=decrease:flags=lanczos")
        }
        add("fps=${config.fps}")
        if (config.denoiseStrength > 0) {
            val strength = config.denoiseStrength.coerceIn(1, 10)
            add("hqdn3d=$strength:$strength:${strength / 2}:${strength / 2}")
        }
        if (config.sharpen) {
            add("unsharp=5:5:1.2:5:5:0.6")
        }
        if (config.ditherMode != DitherMode.NONE && !forVideoOutput) {
            add("split[a][b];[a]palettegen[p];[b][p]paletteuse=dither=${config.ditherMode.ffmpegValue}")
        }
    }.joinToString(",")
}

/**
 * Append encoder flags common to all encode modes.
 *
 * NOTE: keyframeInterval used to emit `-g N` here — libwebp_anim never reads
 * gop_size (frame decisions come from its internal kmin/kmax logic), so the
 * flag was a silent no-op and has been removed.
 */
private fun StringBuilder.appendEncoderFlags(config: ConversionConfig, quality: Int, loop: Int) {
    append("-c:v libwebp_anim ")
    append("-quality $quality ")
    append("-compression_level ${config.compressionLevel} ")
    append("-loop $loop ")
    append("-an ")
    append("-vsync vfr ")
    if (config.colorSpace != ColorSpace.AUTO) {
        append("-pix_fmt ${config.colorSpace.ffmpegValue} ")
    }
}

/**
 * Video output container with the codec pair this ffmpeg-kit build can encode.
 *
 * The bundled build (com.moizhassan.ffmpeg:ffmpeg-kit-16kb 6.1.1) is the
 * non-GPL "full" flavor: NO libx264/x265, but it ships libopenh264, the native
 * aac encoder, libvpx, and libopus. Output is therefore always re-encoded —
 * stream copy is impossible anyway with per-segment volume filters and
 * unreliable at non-keyframe cuts.
 */
enum class VideoContainer(
    val extension: String,
    val videoCodec: String,
    val audioCodec: String,
    val mimeType: String,
) {
    MP4("mp4", "libopenh264", "aac", "video/mp4"),
    WEBM("webm", "libvpx", "libopus", "video/webm"),
    MKV("mkv", "libvpx", "libopus", "video/x-matroska"),
}

/**
 * Map the selected [OutputMediaFormat] to a concrete [VideoContainer], or null
 * for animated-WebP output.
 *
 * ORIGINAL matches the input container by file extension: the mp4 family
 * (mp4/m4v/mov) stays H.264-in-MP4, webm/mkv keep their container with
 * VP8+Opus, and anything unrecognized falls back to MP4 (the most portable
 * target this build can produce).
 */
internal fun resolveOutputContainer(
    format: OutputMediaFormat,
    sourceFileName: String,
): VideoContainer? = when (format) {
    OutputMediaFormat.WEBP -> null
    OutputMediaFormat.MP4 -> VideoContainer.MP4
    OutputMediaFormat.ORIGINAL ->
        when (sourceFileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)) {
            "mp4", "m4v", "mov" -> VideoContainer.MP4
            "webm" -> VideoContainer.WEBM
            "mkv" -> VideoContainer.MKV
            else -> VideoContainer.MP4
        }
}

/** Bitrate clamp bounds for [videoBitrateBps]. */
private const val MIN_VIDEO_BITRATE_BPS = 200_000L
private const val MAX_VIDEO_BITRATE_BPS = 20_000_000L

/**
 * Map the quality slider to a target video bitrate for libopenh264/libvpx
 * (neither has a CRF-style quality knob in this build, so `-b:v` it is).
 *
 * Formula (integer-exact, so tests can pin values):
 *   bppMilli = 30 + quality * 3 / 2      — 0.030 bits/pixel at q=0 rising
 *                                          linearly to 0.180 bpp at q=100
 *   bitrate  = width * height * fps * bppMilli / 1000
 * clamped to [200 kbps, 20 Mbps]. Anchors: 720p30 at q=100 ≈ 5 Mbps,
 * 720x405 at 12 fps / q=70 ≈ 470 kbps. Degenerate (zero) dimensions land on
 * the clamp floor rather than producing a zero bitrate.
 */
internal fun videoBitrateBps(width: Int, height: Int, fps: Int, quality: Int): Long {
    val bppMilli = 30L + quality.coerceIn(0, 100) * 3L / 2L
    val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
    return (pixelsPerSecond * bppMilli / 1000L)
        .coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
}

/**
 * Append encoder flags for video-container outputs.
 *
 * `-pix_fmt yuv420p` here is a fixed compatibility choice (baseline for
 * H.264/VP8 playback everywhere), NOT the user's WebP colorSpace setting —
 * that option, like loop/quality/compression_level, must not leak into video
 * encodes.
 */
private fun StringBuilder.appendVideoEncoderFlags(
    container: VideoContainer,
    bitrateBps: Long,
    hasAudio: Boolean,
) {
    append("-c:v ${container.videoCodec} ")
    append("-b:v $bitrateBps ")
    append("-pix_fmt yuv420p ")
    if (hasAudio) {
        append("-c:a ${container.audioCodec} ")
        append("-b:a 128k ")
    } else {
        append("-an ")
    }
    if (container == VideoContainer.MP4) {
        // Moves the moov atom up front so the file streams/scrubs immediately.
        append("-movflags +faststart ")
    }
}

/**
 * Build the FFmpeg command matching the discwebp algorithm.
 * Supports:
 * - Single trim (trimStartMs/trimEndMs)
 * - Multi-segment stitching via concat filter (segments list) — including over a
 *   pre-decoded PNG sequence, which previously fell into the single-segment
 *   concat branch and silently dropped every segment but the first
 * - Exact crop, denoise, sharpen, dither, color space
 *
 * Top-level (not a ConversionEngine member) so JVM tests can pin the generated
 * commands without an Android Context.
 */
@VisibleForTesting
internal fun buildFfmpegCommand(
    inputPath: String,
    outputPath: String,
    config: ConversionConfig,
    quality: Int,
    /** Set when the input was pre-decoded to a PNG sequence (animated WebP/GIF) */
    concatScript: File? = null,
    /** Loop count declared by the source animation, when known */
    sourceLoopCount: Int? = null,
    /** Target video container, or null for animated-WebP output */
    container: VideoContainer? = null,
    /** True when the input has an audio track (video outputs only) */
    hasAudio: Boolean = false,
    /** Probed source width, used to derive the video bitrate (0 = unknown) */
    sourceWidth: Int = 0,
    /** Probed source height, used to derive the video bitrate (0 = unknown) */
    sourceHeight: Int = 0,
): String {
    // Normalize once for every branch: zero-length segments would become empty
    // trim streams that abort FFmpeg's concat filter, and an unsorted list
    // would stitch the output out of order. Production merges via
    // mergeOverlappingSegments first, but the builder must not rely on that.
    val segments = config.segments.filter { it.durationMs > 0 }.sortedBy { it.startMs }

    // Pre-decoded frame sequences are read via the concat demuxer so each frame
    // keeps its own duration.
    val inputArgs = if (concatScript != null) {
        "-f concat -safe 0 -i \"${concatScript.absolutePath}\""
    } else {
        "-i \"$inputPath\""
    }

    if (container != null) {
        return buildVideoCommand(
            inputArgs, outputPath, config, quality, segments,
            container, hasAudio, sourceWidth, sourceHeight,
        )
    }

    val filters = buildVideoFilters(config)
    val loop = resolveLoopCount(config.loop, sourceLoopCount)

    // Multi-segment stitching mode: complex filtergraph with concat. The trim
    // filter operates on timestamps, which the concat demuxer synthesizes from
    // per-frame durations, so the same graph works for both input kinds.
    if (segments.size > 1) {
        return buildStitchCommand(inputArgs, outputPath, config, quality, filters, loop, segments)
    }

    if (concatScript != null) {
        return buildConcatCommand(concatScript, outputPath, config, quality, filters, loop, segments)
    }

    // Single-segment mode (legacy trim or single segment)
    val effectiveStart = segments.firstOrNull()?.startMs ?: config.trimStartMs
    val effectiveEnd = segments.firstOrNull()?.endMs ?: config.trimEndMs

    return buildString {
        append("-y ")
        if (effectiveStart > 0) {
            append("-ss ${formatSeconds(effectiveStart)} ")
        }
        append("-i \"$inputPath\" ")
        if (effectiveEnd > 0) {
            // -to is an output option here, so it is relative to the seek point
            append("-to ${formatSeconds(effectiveEnd - effectiveStart)} ")
        }
        append("-vf \"$filters\" ")
        appendEncoderFlags(config, quality, loop)
        append("\"$outputPath\"")
    }
}

/**
 * Build the FFmpeg command for a video-container output (MP4/WebM/MKV).
 *
 * Single/no-segment inputs use the fast `-ss`/`-to` seek path (plus a plain
 * `-af volume=` when the one segment carries a non-unity gain); multiple
 * segments build a trim/atrim + concat filter_complex with per-segment volume.
 * Inputs without an audio track (or pre-decoded frame sequences, which never
 * have one) get a video-only graph and `-an`.
 */
private fun buildVideoCommand(
    inputArgs: String,
    outputPath: String,
    config: ConversionConfig,
    quality: Int,
    segments: List<TrimSegment>,
    container: VideoContainer,
    hasAudio: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
): String {
    val filters = buildVideoFilters(config, forVideoOutput = true)

    // Bitrate is derived from the ESTIMATED OUTPUT resolution (post scale/crop),
    // falling back to a maxDimension-sized 16:9 frame when the probe failed.
    val (estWidth, estHeight) = SizeEstimation.estimateOutputDimensions(sourceWidth, sourceHeight, config)
    val bitrateWidth = if (estWidth > 0) estWidth else config.maxDimension
    val bitrateHeight = if (estHeight > 0) estHeight else config.maxDimension * 9 / 16
    val bitrate = videoBitrateBps(bitrateWidth, bitrateHeight, config.fps, quality)

    if (segments.size > 1) {
        val n = segments.size
        val filterGraph = buildString {
            segments.forEachIndexed { i, seg ->
                append(
                    "[0:v]trim=start=${formatSeconds(seg.startMs)}:end=${formatSeconds(seg.endMs)}," +
                        "setpts=PTS-STARTPTS[v$i];"
                )
                if (hasAudio) {
                    append(
                        "[0:a]atrim=start=${formatSeconds(seg.startMs)}:end=${formatSeconds(seg.endMs)}," +
                            "asetpts=PTS-STARTPTS,volume=${formatVolume(seg.volume)}[a$i];"
                    )
                }
            }
            if (hasAudio) {
                val inputs = (0 until n).joinToString("") { "[v$it][a$it]" }
                append("${inputs}concat=n=$n:v=1:a=1[cat][acat];")
            } else {
                val inputs = (0 until n).joinToString("") { "[v$it]" }
                append("${inputs}concat=n=$n:v=1:a=0[cat];")
            }
            // Scale/fps/denoise/sharpen apply AFTER the concat, exactly like
            // the WebP stitch path, so per-segment streams stay filter-free.
            append("[cat]$filters[vout]")
        }
        return buildString {
            append("-y ")
            append("$inputArgs ")
            append("-filter_complex \"$filterGraph\" ")
            append("-map \"[vout]\" ")
            if (hasAudio) append("-map \"[acat]\" ")
            appendVideoEncoderFlags(container, bitrate, hasAudio)
            append("\"$outputPath\"")
        }
    }

    // Single segment or legacy trim: seek path, with -to relative to the seek.
    val effectiveStart = segments.firstOrNull()?.startMs ?: config.trimStartMs
    val effectiveEnd = segments.firstOrNull()?.endMs ?: config.trimEndMs
    val volume = segments.firstOrNull()?.volume ?: 1f

    return buildString {
        append("-y ")
        if (effectiveStart > 0) {
            append("-ss ${formatSeconds(effectiveStart)} ")
        }
        append("$inputArgs ")
        if (effectiveEnd > effectiveStart) {
            append("-to ${formatSeconds(effectiveEnd - effectiveStart)} ")
        }
        append("-vf \"$filters\" ")
        if (hasAudio && volume != 1f) {
            append("-af \"volume=${formatVolume(volume)}\" ")
        }
        appendVideoEncoderFlags(container, bitrate, hasAudio)
        append("\"$outputPath\"")
    }
}

/**
 * Build an FFmpeg command that reads a pre-decoded PNG sequence (single segment).
 *
 * Used for animated WebP, which FFmpeg 6.0 cannot demux. Trimming is applied
 * on the output side since the concat demuxer has no seekable timeline.
 */
private fun buildConcatCommand(
    concatScript: File,
    outputPath: String,
    config: ConversionConfig,
    quality: Int,
    filters: String,
    loop: Int,
    /** Normalized (non-empty, sorted) segment list from buildFfmpegCommand. */
    segments: List<TrimSegment>,
): String = buildString {
    append("-y ")
    append("-f concat -safe 0 ")
    val effectiveStart = segments.firstOrNull()?.startMs ?: config.trimStartMs
    val effectiveEnd = segments.firstOrNull()?.endMs ?: config.trimEndMs
    if (effectiveStart > 0) {
        append("-ss ${formatSeconds(effectiveStart)} ")
    }
    append("-i \"${concatScript.absolutePath}\" ")
    if (effectiveEnd > effectiveStart) {
        append("-to ${formatSeconds(effectiveEnd - effectiveStart)} ")
    }
    append("-vf \"$filters\" ")
    appendEncoderFlags(config, quality, loop)
    append("\"$outputPath\"")
}

/**
 * Build an FFmpeg command that stitches multiple segments using the concat filter.
 * Each segment is trimmed from the same input, concatenated, THEN filtered.
 *
 * Filters are applied AFTER concat to avoid FFmpeg label collisions — some
 * filters (e.g. palettegen/paletteuse for dithering) use internal labels like
 * [a][b][p] that would conflict if duplicated per segment.
 *
 * Complex filtergraph structure:
 *   [0:v]trim=start:end,setpts=PTS-STARTPTS[v0];
 *   [0:v]trim=start:end,setpts=PTS-STARTPTS[v1];
 *   [v0][v1]concat=n=2:v=1:a=0[cat];[cat]<filters>[vout]
 *
 * @param inputArgs full input specification (`-i "path"` or the concat-demuxer form)
 */
private fun buildStitchCommand(
    inputArgs: String,
    outputPath: String,
    config: ConversionConfig,
    quality: Int,
    filters: String,
    loop: Int,
    /** Normalized (non-empty, sorted) segment list from buildFfmpegCommand. */
    segments: List<TrimSegment>,
): String {
    val n = segments.size

    val filterGraph = buildString {
        segments.forEachIndexed { i, seg ->
            // Trim each segment and reset PTS (no per-segment filters)
            append(
                "[0:v]trim=${formatSeconds(seg.startMs)}:${formatSeconds(seg.endMs)}," +
                    "setpts=PTS-STARTPTS[v$i];"
            )
        }
        // Concatenate all segments, then apply filters to the combined stream
        val inputs = (0 until n).joinToString("") { "[v$it]" }
        append("${inputs}concat=n=$n:v=1:a=0[cat];[cat]$filters[vout]")
    }

    return buildString {
        append("-y ")
        append("$inputArgs ")
        append("-filter_complex \"$filterGraph\" ")
        append("-map \"[vout]\" ")
        appendEncoderFlags(config, quality, loop)
        append("\"$outputPath\"")
    }
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
