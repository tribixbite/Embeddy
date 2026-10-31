package app.embeddy.conversion

import app.embeddy.util.SizeEstimation
import java.util.Locale

/*
 * Pure FFmpeg command construction.
 *
 * Everything here is free of Android types so the JVM unit suite pins the exact
 * commands production runs. ConversionEngine only resolves input paths, runs
 * FFmpeg and reports progress.
 */

/**
 * Video output container with the codec pair this ffmpeg-kit build can encode.
 *
 * The bundled build (com.moizhassan.ffmpeg:ffmpeg-kit-16kb 6.1.1) has NO
 * libx264/x265, but ships libopenh264, the native aac encoder, libvpx, and
 * libopus. Output is therefore always re-encoded — stream copy is impossible
 * anyway with per-segment volume filters and unreliable at non-keyframe cuts.
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

/** What a conversion writes: decides encoder flags, filters and extension. */
sealed class OutputTarget(val extension: String, val mimeType: String) {
    /** Animated WebP via libwebp_anim. */
    data object AnimatedWebp : OutputTarget("webp", "image/webp")

    /** Animated GIF via FFmpeg's native gif encoder with per-frame palettes. */
    data object Gif : OutputTarget("gif", "image/gif")

    /** A video container (MP4/WebM/MKV), optionally with audio. */
    data class Video(val container: VideoContainer) :
        OutputTarget(container.extension, container.mimeType)

    /** Audio only: AAC in an MPEG-4 audio (.m4a) container. */
    data object AudioM4a : OutputTarget("m4a", "audio/mp4")

    /** True when the output has a picture (everything but audio-only). */
    val hasVideo: Boolean get() = this !is AudioM4a
}

/**
 * Map the selected [OutputMediaFormat] to a concrete [OutputTarget].
 *
 * ORIGINAL matches the input container by file extension: the mp4 family
 * (mp4/m4v/mov) stays H.264-in-MP4, webm/mkv keep their container with
 * VP8+Opus, and anything unrecognized falls back to MP4 (the most portable
 * target this build can produce).
 */
fun resolveOutputTarget(format: OutputMediaFormat, sourceFileName: String): OutputTarget =
    when (format) {
        OutputMediaFormat.WEBP -> OutputTarget.AnimatedWebp
        OutputMediaFormat.GIF -> OutputTarget.Gif
        OutputMediaFormat.AUDIO -> OutputTarget.AudioM4a
        OutputMediaFormat.MP4 -> OutputTarget.Video(VideoContainer.MP4)
        OutputMediaFormat.ORIGINAL -> OutputTarget.Video(
            when (sourceFileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)) {
                "mp4", "m4v", "mov" -> VideoContainer.MP4
                "webm" -> VideoContainer.WEBM
                "mkv" -> VideoContainer.MKV
                else -> VideoContainer.MP4
            },
        )
    }

/** The [VideoContainer] for [format], or null when it is not a video format. */
fun resolveOutputContainer(format: OutputMediaFormat, sourceFileName: String): VideoContainer? =
    (resolveOutputTarget(format, sourceFileName) as? OutputTarget.Video)?.container

/** One FFmpeg input. */
data class InputSpec(
    /** Filesystem path, `saf:` URL, or concat-script path. */
    val path: String,
    /** True when [path] is a concat-demuxer script of pre-decoded frames. */
    val isConcatScript: Boolean = false,
    /** True when the input carries an audio track. */
    val hasAudio: Boolean = false,
    /** Display width (post-autorotate); 0 = unknown. */
    val width: Int = 0,
    /** Display height (post-autorotate); 0 = unknown. */
    val height: Int = 0,
    /** Source duration; 0 = unknown. */
    val durationMs: Long = 0,
)

/**
 * Everything one FFmpeg invocation needs.
 *
 * One input is a single source (trim/stitch via [edit]); several inputs are
 * joined back to back, each trimmed by [EditSpec.clipRange].
 */
data class CommandRequest(
    val inputs: List<InputSpec>,
    val outputPath: String,
    val target: OutputTarget,
    val config: ConversionConfig,
    val edit: EditSpec = EditSpec(),
    /** Encoder quality for this attempt (WebP quality; video bitrate source). */
    val quality: Int = config.startQuality,
    /** Loop count declared by the source animation, when known. */
    val sourceLoopCount: Int? = null,
    /**
     * Explicit video bitrate (target-size mode computes it directly from the
     * size budget); null derives it from [quality] via [videoBitrateBps].
     */
    val videoBitrateBps: Long? = null,
) {
    /** Convenience constructor for the common single-input case. */
    constructor(
        input: InputSpec,
        outputPath: String,
        target: OutputTarget,
        config: ConversionConfig,
        edit: EditSpec = EditSpec(),
        quality: Int = config.startQuality,
        sourceLoopCount: Int? = null,
        videoBitrateBps: Long? = null,
    ) : this(listOf(input), outputPath, target, config, edit, quality, sourceLoopCount, videoBitrateBps)

    /** The first (or only) input. */
    val input: InputSpec get() = inputs.first()

    /** True when several inputs are joined. */
    val isJoin: Boolean get() = inputs.size > 1
}

/** Bitrate clamp bounds for video encodes. */
internal const val MIN_VIDEO_BITRATE_BPS = 200_000L
internal const val MAX_VIDEO_BITRATE_BPS = 20_000_000L

/** Fixed audio bitrate for video outputs. */
internal const val VIDEO_AUDIO_BITRATE_BPS = 128_000L

/** Audio bitrate for audio-only (.m4a) outputs. */
internal const val AUDIO_ONLY_BITRATE_BPS = 192_000L

/** Sample rate every joined audio stream is resampled to before concat. */
private const val JOIN_SAMPLE_RATE = 48_000

/**
 * Format a millisecond timestamp as an FFmpeg seconds argument.
 * Always uses [Locale.US] — FFmpeg only accepts `.` as the decimal separator,
 * and the default locale would emit `1,500` on e.g. de-DE/fr-FR devices.
 */
internal fun formatSeconds(millis: Long): String =
    String.format(Locale.US, "%.3f", millis / 1000.0)

/**
 * Format a segment volume for the `volume=` audio filter, clamped to the UI
 * range 0..2. [Locale.US] for the same decimal-separator reason as
 * [formatSeconds].
 */
internal fun formatVolume(volume: Float): String =
    String.format(Locale.US, "%.3f", volume.coerceIn(0f, 2f))

/**
 * Map the quality slider to a target video bitrate for libopenh264/libvpx
 * (neither has a CRF-style quality knob in this build, so `-b:v` it is).
 *
 * Formula (integer-exact, so tests can pin values):
 *   bppMilli = 30 + quality * 3 / 2      — 0.030 bits/pixel at q=0 rising
 *                                          linearly to 0.180 bpp at q=100
 *   bitrate  = width * height * fps * bppMilli / 1000
 * clamped to [200 kbps, 20 Mbps]. Degenerate (zero) dimensions land on the
 * clamp floor rather than producing a zero bitrate.
 */
internal fun videoBitrateBps(width: Int, height: Int, fps: Int, quality: Int): Long {
    val bppMilli = 30L + quality.coerceIn(0, 100) * 3L / 2L
    val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
    return (pixelsPerSecond * bppMilli / 1000L)
        .coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
}

/** Fraction of the size budget handed to the encoder; the rest covers container overhead. */
private const val TARGET_SIZE_HEADROOM = 0.95

/**
 * Video bitrate that makes a [durationMs] encode land inside [targetBytes].
 *
 * libopenh264/libvpx run in bitrate mode, so for video the size is a direct
 * function of the bitrate — computing it up front replaces the WebP-style
 * quality ladder, which spent a full re-encode per step. The audio track's
 * share is subtracted first; the result is clamped like [videoBitrateBps].
 * Returns null when the duration is unknown (fall back to the quality mapping).
 */
internal fun bitrateForTargetSize(targetBytes: Long, durationMs: Long, audioBps: Long): Long? {
    if (targetBytes <= 0 || durationMs <= 0) return null
    val totalBps = targetBytes * 8.0 * TARGET_SIZE_HEADROOM / (durationMs / 1000.0)
    return (totalBps - audioBps).toLong().coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
}

/**
 * Bitrate for the next target-size attempt after one came out at [actualBytes].
 *
 * Rate control overshoots on short or high-motion clips; scaling by the
 * observed overshoot (with the same headroom) converges in one or two retries.
 * Always strictly lower than [previousBps] (by at least 10%) so the loop
 * terminates, and never below the clamp floor.
 */
internal fun nextBitrateForTarget(previousBps: Long, actualBytes: Long, targetBytes: Long): Long {
    if (actualBytes <= 0 || targetBytes <= 0) return previousBps
    val scaled = (previousBps * (targetBytes.toDouble() / actualBytes) * TARGET_SIZE_HEADROOM).toLong()
    return minOf(scaled, previousBps * 9 / 10).coerceAtLeast(MIN_VIDEO_BITRATE_BPS)
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
 * (Auto/Infinite/N).
 */
internal fun resolveLoopCount(configLoop: Int, sourceLoopCount: Int?): Int =
    if (configLoop == 0 && sourceLoopCount != null && sourceLoopCount > 0) {
        sourceLoopCount
    } else {
        configLoop
    }

/** Round down to an even value (min 2) — yuv420p needs even dimensions. */
internal fun evenFloor(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)

/**
 * Scaling filters for [config].
 *
 * @param forVideo true for H.264/VP8 outputs. yuv420p chroma subsampling needs
 * even dimensions and odd sizes (1920x1080 at max 720 → 720x405) are rejected
 * or mis-rendered by many players, so video outputs are forced even:
 * `force_divisible_by=2` on the aspect-preserving scale, even exact sizes, and
 * a trunc-to-even pass when the source size is kept. SizeEstimation mirrors
 * every branch.
 */
internal fun scaleFilters(config: ConversionConfig, forVideo: Boolean): List<String> {
    val ew = if (forVideo && config.exactWidth > 0) evenFloor(config.exactWidth) else config.exactWidth
    val eh = if (forVideo && config.exactHeight > 0) evenFloor(config.exactHeight) else config.exactHeight
    return when {
        ew > 0 && eh > 0 -> listOf(
            "scale=$ew:$eh:force_original_aspect_ratio=increase:flags=lanczos",
            "crop=$ew:$eh",
        )
        ew > 0 -> listOf("scale=$ew:-2:flags=lanczos")
        eh > 0 -> listOf("scale=-2:$eh:flags=lanczos")
        config.maxDimension > 0 -> {
            val m = config.maxDimension
            val divisible = if (forVideo) ":force_divisible_by=2" else ""
            listOf(
                "scale='min($m,iw)':'min($m,ih)':force_original_aspect_ratio=decrease$divisible:flags=lanczos",
            )
        }
        // Original resolution: nothing to do for palette formats; video still
        // needs even dimensions.
        forVideo -> listOf("scale=trunc(iw/2)*2:trunc(ih/2)*2")
        else -> emptyList()
    }
}

/**
 * Palette quantization for palette-based outputs.
 *
 * `stats_mode=single` + `new=1` computes one palette per frame. The classic
 * whole-stream `palettegen` waits for end-of-stream before emitting anything,
 * so `paletteuse` had to buffer EVERY decoded frame of the clip in memory
 * (~840 MB for 60 s of 720p at 12 fps) — long clips ran out of memory.
 * Labels are prefixed (`pa`/`pb`/`pp`) so they cannot collide with the
 * stitch graph's `[v0]`/`[a0]` labels.
 */
internal fun paletteFilter(dither: DitherMode, maxColors: Int = 256): String {
    val ditherValue = dither.ffmpegValue.ifEmpty { "none" }
    return "split[pa][pb];[pa]palettegen=max_colors=$maxColors:stats_mode=single[pp];" +
        "[pb][pp]paletteuse=new=1:dither=$ditherValue"
}

/**
 * GIF palette size for a quality value: 32 colors at q=0 up to the full 256 at
 * q=100, so the adaptive target-size loop has a real size lever for GIF.
 */
internal fun gifMaxColors(quality: Int): Int = 32 + quality.coerceIn(0, 100) * 224 / 100

/** Format a speed factor for setpts/atempo, locale-safe. */
private fun formatFactor(value: Float): String = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

/**
 * `atempo` stages for a speed factor. Each stage is kept within 0.5..2.0 —
 * the range every FFmpeg version supports with good quality — so 4x becomes
 * `atempo=2,atempo=2` and 0.25x becomes `atempo=0.5,atempo=0.5`.
 */
internal fun atempoChain(speed: Float): List<String> {
    var remaining = speed.coerceIn(MIN_SPEED, MAX_SPEED).toDouble()
    val stages = mutableListOf<String>()
    while (remaining > 2.0 + 1e-6) { stages += "atempo=2"; remaining /= 2.0 }
    while (remaining < 0.5 - 1e-6) { stages += "atempo=0.5"; remaining /= 0.5 }
    if (kotlin.math.abs(remaining - 1.0) > 1e-6) stages += "atempo=${formatFactor(remaining.toFloat())}"
    return stages
}

/** Rotation/flip filters for the edit (applied after crop, before scale). */
internal fun orientationFilters(edit: EditSpec): List<String> = buildList {
    when (edit.normalizedRotation) {
        90 -> add("transpose=clock")
        180 -> { add("hflip"); add("vflip") }
        270 -> add("transpose=cclock")
    }
    if (edit.flipHorizontal) add("hflip")
    if (edit.flipVertical) add("vflip")
}

/** Crop filter for the edit, in exact even pixels when the source size is known. */
internal fun cropFilter(edit: EditSpec, width: Int, height: Int): String? {
    val crop = edit.crop?.normalized()?.takeUnless { it.isFullFrame } ?: return null
    if (width > 0 && height > 0) {
        val px = cropPixels(crop, width, height)
        return "crop=${px.width}:${px.height}:${px.x}:${px.y}"
    }
    // Unknown size: let FFmpeg evaluate the fractions.
    fun f(v: Float) = String.format(Locale.US, "%.4f", v)
    return "crop=iw*${f(crop.right - crop.left)}:ih*${f(crop.bottom - crop.top)}:iw*${f(crop.left)}:ih*${f(crop.top)}"
}

/**
 * The per-output video filter chain applied after trimming/concatenation:
 * speed → crop → rotate/flip → scale → fps → denoise → sharpen → reverse → palette.
 *
 * Joined inputs are already scaled, padded and frame-rate-normalized per input,
 * so [normalizedInputs] skips crop/scale/fps here.
 */
internal fun videoFilterChain(req: CommandRequest, normalizedInputs: Boolean = req.isJoin): String {
    val config = req.config
    val edit = req.edit
    val forVideo = req.target is OutputTarget.Video
    return buildList {
        if (edit.effectiveSpeed != 1f) add("setpts=PTS/${formatFactor(edit.effectiveSpeed)}")
        if (!normalizedInputs) cropFilter(edit, req.input.width, req.input.height)?.let(::add)
        addAll(orientationFilters(edit))
        if (!normalizedInputs) {
            addAll(scaleFilters(config, forVideo))
            if (config.fps > 0) add("fps=${config.fps}")
        }
        if (config.denoiseStrength > 0) {
            val strength = config.denoiseStrength.coerceIn(1, 10)
            add("hqdn3d=$strength:$strength:${strength / 2}:${strength / 2}")
        }
        if (config.sharpen) add("unsharp=5:5:1.2:5:5:0.6")
        // After scale/fps so the buffered frames are as small and few as possible.
        if (edit.reverse) add("reverse")
        when {
            req.target == OutputTarget.Gif -> add(paletteFilter(config.ditherMode, gifMaxColors(req.quality)))
            // Dithering is opt-in for WebP: a palette concept that must not
            // leak into H.264/VP8 encodes.
            req.target == OutputTarget.AnimatedWebp && config.ditherMode != DitherMode.NONE ->
                add(paletteFilter(config.ditherMode))
        }
    }.joinToString(",")
}

/** Audio post-processing after trim/concat: speed then reverse. */
internal fun audioPostFilters(edit: EditSpec): List<String> = buildList {
    addAll(atempoChain(edit.effectiveSpeed))
    if (edit.reverse) add("areverse")
}

/** True when the output keeps an audio track. */
internal fun includesAudio(req: CommandRequest): Boolean {
    val anyAudio = req.inputs.any { it.hasAudio && !it.isConcatScript }
    return when (req.target) {
        is OutputTarget.Video -> anyAudio && !req.edit.muted
        OutputTarget.AudioM4a -> anyAudio
        else -> false
    }
}

/**
 * Canvas every joined input is scaled and padded to: the first clip's size run
 * through the normal scale rules, always even (pad and yuv420p need it).
 */
internal fun joinCanvas(req: CommandRequest): Pair<Int, Int> {
    val first = req.input
    val (w, h) = SizeEstimation.estimateOutputDimensions(first.width, first.height, req.config, forVideo = true)
    return if (w > 0 && h > 0) w to h else evenFloor(req.config.maxDimension.takeIf { it > 0 } ?: 1280) to 720
}

/** Output dimensions the encoder will see (used for the quality→bitrate mapping). */
internal fun estimatedOutputSize(req: CommandRequest): Pair<Int, Int> {
    if (req.isJoin) {
        val (w, h) = joinCanvas(req)
        return if (req.edit.normalizedRotation % 180 == 90) h to w else w to h
    }
    val forVideo = req.target is OutputTarget.Video
    val (ew, eh) = editedDimensions(req.input.width, req.input.height, req.edit)
    val (w, h) = SizeEstimation.estimateOutputDimensions(ew, eh, req.config, forVideo = forVideo)
    // Probe failed: assume a maxDimension-sized 16:9 frame (720p for "original").
    val fallbackLong = if (req.config.maxDimension > 0) req.config.maxDimension else 1280
    return if (w > 0 && h > 0) w to h else fallbackLong to fallbackLong * 9 / 16
}

/** Encoder flags for the target. */
private fun StringBuilder.appendEncoderFlags(req: CommandRequest, audio: Boolean) {
    val config = req.config
    when (val target = req.target) {
        OutputTarget.AnimatedWebp -> {
            append("-c:v libwebp_anim ")
            append("-quality ${req.quality} ")
            append("-compression_level ${config.compressionLevel} ")
            append("-loop ${resolveLoopCount(config.loop, req.sourceLoopCount)} ")
            append("-an ")
            append("-vsync vfr ")
            if (config.colorSpace != ColorSpace.AUTO) {
                append("-pix_fmt ${config.colorSpace.ffmpegValue} ")
            }
        }
        OutputTarget.Gif -> {
            append("-c:v gif ")
            // gif muxer: 0 = loop forever, N = repeat N times (same as WebP's -loop)
            append("-loop ${resolveLoopCount(config.loop, req.sourceLoopCount)} ")
            append("-an ")
        }
        is OutputTarget.Video -> {
            val container = target.container
            val bitrate = req.videoBitrateBps ?: run {
                val (w, h) = estimatedOutputSize(req)
                videoBitrateBps(w, h, config.estimationFps, req.quality)
            }
            append("-c:v ${container.videoCodec} ")
            append("-b:v $bitrate ")
            // Fixed compatibility choice (baseline for H.264/VP8 playback
            // everywhere), NOT the user's WebP colorSpace setting.
            append("-pix_fmt yuv420p ")
            if (audio) {
                append("-c:a ${container.audioCodec} ")
                append("-b:a ${VIDEO_AUDIO_BITRATE_BPS / 1000}k ")
            } else {
                append("-an ")
            }
            if (container == VideoContainer.MP4) {
                // Moves the moov atom up front so the file streams/scrubs immediately.
                append("-movflags +faststart ")
            }
        }
        OutputTarget.AudioM4a -> {
            append("-vn ")
            append("-c:a aac ")
            append("-b:a ${AUDIO_ONLY_BITRATE_BPS / 1000}k ")
            append("-movflags +faststart ")
        }
    }
    // Outputs are meant for sharing: never carry the source's global metadata
    // (camera make/model, creation time, and on phone MP4s the GPS location).
    append("-map_metadata -1 ")
}

/** `-f concat -safe 0 -i "script"` or `-i "path"`. */
private fun inputArgs(input: InputSpec): String =
    if (input.isConcatScript) "-f concat -safe 0 -i \"${input.path}\"" else "-i \"${input.path}\""

/**
 * Build the complete FFmpeg command for [req].
 *
 * - Several inputs: each is trimmed, scaled+padded to a common canvas and
 *   frame rate, its audio resampled (or silence generated for clips without
 *   audio), then everything is concatenated.
 * - One kept range: the fast seek path — `-ss`/`-t` as INPUT options (so the
 *   duration is measured on the source timeline, independent of any speed
 *   change), plus `-vf`/`-af` chains.
 * - Several kept ranges: a trim/atrim + concat `filter_complex`, filters applied
 *   AFTER the concat so palette labels are never duplicated per segment. The
 *   trim filter works on timestamps, which the concat demuxer synthesizes from
 *   per-frame durations, so the same graph serves pre-decoded frame sequences.
 */
fun buildFfmpegCommand(req: CommandRequest): String {
    val audio = includesAudio(req)
    require(req.target != OutputTarget.AudioM4a || audio) { "The input has no audio track to extract" }
    if (req.isJoin) return buildJoinCommand(req, audio)

    val ranges = req.edit.keptRanges(req.input.durationMs)
    require(ranges.isNotEmpty()) { "Nothing to encode: the edit keeps no time range" }
    if (ranges.size > 1) return buildStitchCommand(req, ranges, audio)

    val range = ranges.first()
    val chain = if (req.target.hasVideo) videoFilterChain(req) else ""
    val audioChain = buildList {
        if (range.volume != 1f) add("volume=${formatVolume(range.volume)}")
        addAll(audioPostFilters(req.edit))
    }.joinToString(",")
    return buildString {
        append("-y ")
        if (range.startMs > 0) append("-ss ${formatSeconds(range.startMs)} ")
        // A range that runs to the source's end needs no -t: the probed
        // duration is rounded to ms and could shave off the final frame.
        val endsEarly = range.endMs > range.startMs &&
            (req.input.durationMs <= 0 || range.endMs < req.input.durationMs)
        if (endsEarly) append("-t ${formatSeconds(range.durationMs)} ")
        append(inputArgs(req.input)).append(' ')
        if (chain.isNotEmpty()) append("-vf \"$chain\" ")
        if (audio && audioChain.isNotEmpty()) append("-af \"$audioChain\" ")
        appendEncoderFlags(req, audio)
        append("\"${req.outputPath}\"")
    }
}

/** Multi-range stitch: per-range trim (+atrim with volume), concat, then filters. */
private fun buildStitchCommand(req: CommandRequest, ranges: List<TrimSegment>, audio: Boolean): String {
    val n = ranges.size
    val video = req.target.hasVideo
    val chain = if (video) videoFilterChain(req) else ""
    val audioPost = audioPostFilters(req.edit).joinToString(",")
    val graph = buildString {
        ranges.forEachIndexed { i, seg ->
            val window = "start=${formatSeconds(seg.startMs)}:end=${formatSeconds(seg.endMs)}"
            if (video) append("[0:v]trim=$window,setpts=PTS-STARTPTS[v$i];")
            if (audio) {
                append("[0:a]atrim=$window,asetpts=PTS-STARTPTS,volume=${formatVolume(seg.volume)}[a$i];")
            }
        }
        appendConcat(n, video, audio)
        appendPostChains(video, chain, audio, audioPost)
    }
    return finishGraphCommand(req, req.inputs, graph, video, audio, audioPost)
}

/**
 * Join several separate inputs: per input trim → scale-to-fit → pad to the
 * canvas → square pixels → common fps (video), and trim → resample to 48 kHz
 * stereo (audio) — or generated silence of the clip's length when it has no
 * audio track, so every concat segment has both streams.
 */
private fun buildJoinCommand(req: CommandRequest, audio: Boolean): String {
    val video = req.target.hasVideo
    val (w, h) = joinCanvas(req)
    val fps = req.config.estimationFps
    val chain = if (video) videoFilterChain(req, normalizedInputs = true) else ""
    val audioPost = audioPostFilters(req.edit).joinToString(",")
    val graph = buildString {
        req.inputs.forEachIndexed { i, input ->
            val range = req.edit.clipRange(i, input.durationMs)
            val trimmed = range.startMs > 0 || (range.endMs in 1 until input.durationMs)
            val window = if (range.endMs > range.startMs) {
                "start=${formatSeconds(range.startMs)}:end=${formatSeconds(range.endMs)}"
            } else {
                "start=${formatSeconds(range.startMs)}"
            }
            if (video) {
                append("[$i:v]")
                if (trimmed) append("trim=$window,")
                append("setpts=PTS-STARTPTS,")
                append("scale=$w:$h:force_original_aspect_ratio=decrease:flags=lanczos,")
                append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$fps[v$i];")
            }
            if (audio) {
                val normalize = "aresample=$JOIN_SAMPLE_RATE,aformat=channel_layouts=stereo"
                if (input.hasAudio && !input.isConcatScript) {
                    append("[$i:a]")
                    if (trimmed) append("atrim=$window,")
                    append("asetpts=PTS-STARTPTS,$normalize[a$i];")
                } else {
                    require(range.durationMs > 0) { "Clip ${i + 1} has an unknown duration" }
                    append("anullsrc=r=$JOIN_SAMPLE_RATE:cl=stereo,atrim=duration=${formatSeconds(range.durationMs)}[a$i];")
                }
            }
        }
        appendConcat(req.inputs.size, video, audio)
        appendPostChains(video, chain, audio, audioPost)
    }
    return finishGraphCommand(req, req.inputs, graph, video, audio, audioPost)
}

/** `[v0][a0][v1][a1]concat=n=N:v=1:a=1[cat][acat];` for whichever streams exist. */
private fun StringBuilder.appendConcat(n: Int, video: Boolean, audio: Boolean) {
    append((0 until n).joinToString("") { (if (video) "[v$it]" else "") + (if (audio) "[a$it]" else "") })
    append("concat=n=$n:v=${if (video) 1 else 0}:a=${if (audio) 1 else 0}")
    if (video) append("[cat]")
    if (audio) append("[acat]")
}

/** Post-concat chains: `[cat]chain[vout]` and, when there are audio filters, `[acat]…[aout]`. */
private fun StringBuilder.appendPostChains(video: Boolean, chain: String, audio: Boolean, audioPost: String) {
    if (video) append(";[cat]${chain.ifEmpty { "null" }}[vout]")
    if (audio && audioPost.isNotEmpty()) append(";[acat]$audioPost[aout]")
}

private fun finishGraphCommand(
    req: CommandRequest,
    inputs: List<InputSpec>,
    graph: String,
    video: Boolean,
    audio: Boolean,
    audioPost: String,
): String = buildString {
    append("-y ")
    inputs.forEach { append(inputArgs(it)).append(' ') }
    append("-filter_complex \"$graph\" ")
    if (video) append("-map \"[vout]\" ")
    if (audio) append(if (audioPost.isNotEmpty()) "-map \"[aout]\" " else "-map \"[acat]\" ")
    appendEncoderFlags(req, audio)
    append("\"${req.outputPath}\"")
}

/**
 * Duration of the encoded output in ms (0 when unknown) — drives progress, the
 * target-size bitrate and the reverse limit. Speed changes divide it.
 */
fun outputDurationMs(edit: EditSpec, inputs: List<InputSpec>): Long {
    val sourceMs = if (inputs.size > 1) {
        inputs.withIndex().sumOf { (i, input) -> edit.clipRange(i, input.durationMs).durationMs }
    } else {
        edit.keptDurationMs(inputs.firstOrNull()?.durationMs ?: 0)
    }
    return (sourceMs / edit.effectiveSpeed).toLong()
}

/** Single-source convenience for [outputDurationMs]. */
fun outputDurationMs(edit: EditSpec, sourceDurationMs: Long): Long =
    outputDurationMs(edit, listOf(InputSpec(path = "", durationMs = sourceDurationMs)))

/** True when an output of [outputMs] may be reversed (see [REVERSE_MAX_OUTPUT_MS]). */
fun reverseAllowed(outputMs: Long): Boolean = outputMs in 1..REVERSE_MAX_OUTPUT_MS

/**
 * Encode progress from one FFmpeg statistics sample.
 *
 * libwebp_anim does not always report `time`, but the frame counter works, so
 * the further-along of the two signals wins. Returns 0 when neither total is
 * known (the UI then switches to an indeterminate bar).
 */
fun progressFraction(
    frameNumber: Int,
    timeMs: Double,
    expectedFrames: Long,
    durationMs: Long,
): Float {
    val frameProgress = if (expectedFrames > 0) frameNumber.toFloat() / expectedFrames else 0f
    val timeProgress = if (durationMs > 0) (timeMs / durationMs).toFloat() else 0f
    return maxOf(frameProgress, timeProgress).coerceIn(0f, 1f)
}

/** Frames the encoder will emit for [durationMs] at the config's (estimated) fps. */
fun expectedFrameCount(durationMs: Long, config: ConversionConfig): Long =
    if (durationMs > 0) (durationMs / 1000.0 * config.estimationFps).toLong() else 0L

/**
 * Turn FFmpeg's raw output into a short user-facing reason.
 * Detects common failure patterns; falls back to the tail of the log.
 */
internal fun ffmpegErrorReason(ffmpegOutput: String?): String {
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
        else -> "FFmpeg error: ${ffmpegOutput.takeLast(120).trim()}"
    }
}
