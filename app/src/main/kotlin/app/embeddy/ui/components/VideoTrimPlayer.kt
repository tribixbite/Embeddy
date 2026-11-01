package app.embeddy.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import app.embeddy.R
import app.embeddy.conversion.CropRect
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.TrimSegment
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.formatClipTime
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Video preview with playback controls and trim/stitch support.
 *
 * Supports two modes:
 * - **Simple trim**: single range slider to set start/end points
 * - **Stitch mode**: multiple segments to keep, with gaps removed from the output.
 *
 * Slider drags are held in LOCAL state and committed once on release: pushing
 * every drag frame through the ViewModel recomposed the whole Convert screen
 * and persisted the session dozens of times per second. While dragging, the
 * player seeks to the nearest keyframe (cheap); the release does an exact seek.
 *
 * Opts into Media3's @UnstableApi for PlayerView's buffering indicator and
 * seek parameters — both stable in practice, but lint (and release lintVital)
 * reject the call without the opt-in.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoTrimPlayer(
    uri: Uri,
    durationMs: Long,
    edit: EditSpec,
    targetSizeBytes: Long,
    onTrimChanged: (startMs: Long, endMs: Long) -> Unit,
    onSegmentsChanged: (List<TrimSegment>) -> Unit,
    /** Estimated output bytes for a kept duration (format-aware, see SizeEstimation). */
    estimateBytes: (keptMs: Long) -> Long,
    modifier: Modifier = Modifier,
    /**
     * True for GIF/animated-WebP inputs, which Media3's default renderers cannot
     * decode (the surface stays black). Renders a Coil animated preview instead
     * of an ExoPlayer surface; the trim/stitch sliders keep working.
     */
    animatedImagePreview: Boolean = false,
    /** Show the per-segment volume slider (video output with an audio track). */
    showVolume: Boolean = false,
    /** Display width/height of the source, for mapping the crop overlay. */
    contentWidth: Int = 0,
    contentHeight: Int = 0,
    /** Show the free-crop overlay over the preview. */
    cropEditing: Boolean = false,
    /** Commit a crop rect drawn on the overlay. */
    onCropCommit: (CropRect) -> Unit = {},
    /** Save the frame at the given position; null hides the button (no player). */
    onGrabFrame: ((positionMs: Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val effectiveDuration = if (durationMs > 0) durationMs else 1L

    // Stitch mode follows the edit for each new input (keyed on uri) — a plain
    // remember{} kept the previous file's mode.
    var stitchMode by remember(uri) { mutableStateOf(edit.segments.isNotEmpty()) }

    // ExoPlayer instance — one per URI; null when a Coil preview is shown instead
    val exoPlayer: ExoPlayer? = remember(uri, animatedImagePreview) {
        if (animatedImagePreview) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    // Keyed on uri: playback state must not leak from the previous file.
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var currentPositionMs by remember(uri) { mutableLongStateOf(0L) }

    // Mirror the player's real state (it pauses itself on audio-focus loss,
    // end of media, errors…) instead of trusting our own button presses.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    // Pause when the app leaves the foreground — the player otherwise kept
    // playing audio with the screen off.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) exoPlayer?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Local drag state; null when not dragging (the committed edit is shown).
    var dragTrim by remember(uri) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    var dragSegments by remember(uri) { mutableStateOf<List<TrimSegment>?>(null) }
    val segments = dragSegments ?: edit.segments
    val trimStart = dragTrim?.start?.toLong() ?: edit.trimStartMs.coerceAtLeast(0)
    val trimEnd = dragTrim?.endInclusive?.toLong()
        ?: if (edit.trimEndMs > 0) edit.trimEndMs else effectiveDuration

    // Keep fresh references for the playback loop (avoids stale captures)
    val currentSegments by rememberUpdatedState(segments.sortedBy { it.startMs })
    val currentTrimStart by rememberUpdatedState(trimStart)
    val currentTrimEnd by rememberUpdatedState(trimEnd)
    val currentStitchMode by rememberUpdatedState(stitchMode)

    // Poll position while playing — skips gaps between segments in stitch mode.
    // Keyed on the player too, so the loop dies with a released player.
    LaunchedEffect(isPlaying, exoPlayer) {
        if (exoPlayer == null) return@LaunchedEffect
        while (isPlaying) {
            currentPositionMs = exoPlayer.currentPosition
            val pos = currentPositionMs
            val ranges = currentSegments
            if (currentStitchMode && ranges.isNotEmpty()) {
                if (ranges.none { pos >= it.startMs && pos < it.endMs }) {
                    // In a gap — jump to the next segment or loop to the first
                    val next = ranges.firstOrNull { it.startMs > pos }
                    exoPlayer.seekTo(next?.startMs ?: ranges.first().startMs)
                }
            } else if (pos >= currentTrimEnd || pos < currentTrimStart) {
                exoPlayer.seekTo(currentTrimStart)
            }
            delay(100)
        }
    }

    /** Seek during a drag: nearest keyframe keeps scrubbing responsive. */
    fun scrubTo(positionMs: Long) {
        exoPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        exoPlayer?.seekTo(positionMs)
    }

    /** Exact seek once the drag settles. */
    fun settleAt(positionMs: Long) {
        exoPlayer?.setSeekParameters(SeekParameters.EXACT)
        exoPlayer?.seekTo(positionMs)
    }

    val totalKeptMs = if (stitchMode && segments.isNotEmpty()) {
        segments.sumOf { it.durationMs }
    } else {
        (trimEnd - trimStart).coerceAtLeast(1)
    }
    val estimatedSize = estimateBytes(totalKeptMs)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Preview surface: ExoPlayer for video, Coil animated image for GIF/WebP.
            // Only the media layer is clipped to rounded corners: Compose clips
            // hit-testing to the clip shape too, and the crop handles sit on the
            // video's corners — inside a clipped box a touch on a handle missed
            // the overlay and scrolled the page instead.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                if (exoPlayer == null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                        contentDescription = stringResource(R.string.animated_preview_desc),
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        update = { view -> view.player = exoPlayer },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    )

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                            } else {
                                val ranges = currentSegments
                                val (start, end) = if (stitchMode && ranges.isNotEmpty()) {
                                    ranges.first().startMs to ranges.last().endMs
                                } else trimStart to trimEnd
                                if (exoPlayer.currentPosition >= end || exoPlayer.currentPosition < start) {
                                    settleAt(start)
                                }
                                exoPlayer.play()
                            }
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                if (cropEditing) {
                    var liveCrop by remember(uri) { mutableStateOf<CropRect?>(null) }
                    CropOverlay(
                        crop = liveCrop ?: edit.crop ?: CropRect.FULL,
                        contentWidth = contentWidth,
                        contentHeight = contentHeight,
                        accessibilityLabel = stringResource(R.string.edit_crop_overlay_desc),
                        onCropChange = { liveCrop = it },
                        onCropCommit = {
                            liveCrop = null
                            onCropCommit(it)
                        },
                    )
                }
            }

            if (onGrabFrame != null && exoPlayer != null) {
                TextButton(
                    onClick = {
                        exoPlayer.pause()
                        onGrabFrame(exoPlayer.currentPosition)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.grab_frame))
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }

            // Trim header with stitch mode toggle
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.trim),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = stitchMode,
                    onClick = {
                        stitchMode = !stitchMode
                        if (stitchMode && edit.segments.isEmpty()) {
                            // Start with one segment covering the current trim range
                            onSegmentsChanged(listOf(TrimSegment(trimStart, trimEnd)))
                        } else if (!stitchMode) {
                            // Revert to simple trim from the first segment
                            val first = edit.segments.minByOrNull { it.startMs }
                            if (first != null) onTrimChanged(first.startMs, first.endMs)
                            else onSegmentsChanged(emptyList())
                        }
                    },
                    label = { Text(stringResource(R.string.stitch_mode), style = MaterialTheme.typography.labelSmall) },
                )
            }

            if (stitchMode) {
                StitchSegments(
                    segments = segments,
                    durationMs = effectiveDuration,
                    showVolume = showVolume,
                    onDrag = { updated, seekMs ->
                        dragSegments = updated
                        if (seekMs != null) scrubTo(seekMs)
                    },
                    onCommit = { updated, seekMs ->
                        dragSegments = null
                        // Sort only after the drag completes — sorting mid-drag
                        // swaps indices under the finger and breaks the gesture.
                        onSegmentsChanged(updated.sortedBy { it.startMs })
                        if (seekMs != null) settleAt(seekMs)
                    },
                )
            } else {
                var draggedHandleMs by remember(uri) { mutableLongStateOf(-1L) }
                RangeSlider(
                    value = trimStart.toFloat()..trimEnd.toFloat(),
                    onValueChange = { range ->
                        val previous = dragTrim ?: (trimStart.toFloat()..trimEnd.toFloat())
                        // Seek to whichever handle the user is dragging
                        val handle = if (range.start != previous.start) range.start else range.endInclusive
                        draggedHandleMs = handle.toLong()
                        dragTrim = range
                        scrubTo(handle.toLong())
                    },
                    onValueChangeFinished = {
                        dragTrim?.let { onTrimChanged(it.start.toLong(), it.endInclusive.toLong()) }
                        dragTrim = null
                        if (draggedHandleMs >= 0) settleAt(draggedHandleMs)
                    },
                    valueRange = 0f..effectiveDuration.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .systemGestureExclusion(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatClipTime(trimStart)} – ${formatClipTime(trimEnd)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatClipTime(totalKeptMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Size estimate vs target
            val overTarget = targetSizeBytes > 0 && estimatedSize > targetSizeBytes
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = stringResource(R.string.est_output, FileInfoUtils.formatFileSize(estimatedSize)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overTarget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (targetSizeBytes > 0) {
                    Text(
                        text = stringResource(R.string.target_label, FileInfoUtils.formatFileSize(targetSizeBytes)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * UI for managing multiple stitch segments.
 * Each segment has its own range slider and can be added/removed.
 *
 * @param onDrag live drag updates (local only) plus the position to scrub to
 * @param onCommit the settled list once a drag/click finishes
 */
@Composable
private fun StitchSegments(
    segments: List<TrimSegment>,
    durationMs: Long,
    showVolume: Boolean,
    onDrag: (List<TrimSegment>, seekMs: Long?) -> Unit,
    onCommit: (List<TrimSegment>, seekMs: Long?) -> Unit,
) {
    val sliderRange = 0f..durationMs.toFloat()
    // The latest drag result, read by onValueChangeFinished.
    val latest by rememberUpdatedState(segments)
    var lastSeekMs by remember { mutableLongStateOf(-1L) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { index, segment ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.segment_label, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                if (segments.size > 1) {
                    IconButton(
                        onClick = { onCommit(segments.toMutableList().apply { removeAt(index) }, null) },
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = stringResource(R.string.remove_segment),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            RangeSlider(
                value = segment.startMs.toFloat()..segment.endMs.toFloat(),
                onValueChange = { range ->
                    val newStart = range.start.toLong()
                    val newEnd = range.endInclusive.toLong()
                    val seek = if (newStart != segment.startMs) newStart else newEnd
                    lastSeekMs = seek
                    // copy() so the segment's volume survives a handle drag
                    onDrag(segments.toMutableList().apply { set(index, segment.copy(startMs = newStart, endMs = newEnd)) }, seek)
                },
                onValueChangeFinished = { onCommit(latest, lastSeekMs.takeIf { it >= 0 }) },
                valueRange = sliderRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .systemGestureExclusion(),
                colors = SliderDefaults.colors(
                    activeTrackColor = segmentColor(index),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${formatClipTime(segment.startMs)} – ${formatClipTime(segment.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatClipTime(segment.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = segmentColor(index),
                )
            }

            // Compact per-segment volume (0–200%, default 100%) for video
            // outputs with an audio track. WebP output is always silent.
            if (showVolume) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.segment_volume_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = segment.volume,
                        onValueChange = { v ->
                            onDrag(segments.toMutableList().apply { set(index, segment.copy(volume = v)) }, null)
                        },
                        onValueChangeFinished = { onCommit(latest, null) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = segmentColor(index),
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.volume_percent, (segment.volume * 100).roundToInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(
            onClick = {
                // New segment after the last one (or the final second of the video)
                val lastEnd = segments.maxOfOrNull { it.endMs } ?: 0L
                val newStart = lastEnd.coerceAtMost(durationMs - 1000).coerceAtLeast(0)
                if (newStart < durationMs) onCommit(segments + TrimSegment(newStart, durationMs), null)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_segment), style = MaterialTheme.typography.labelMedium)
        }

        val totalKept = segments.sumOf { it.durationMs }
        val removed = durationMs - totalKept
        if (removed > 0) {
            Text(
                text = stringResource(
                    R.string.keeping_summary,
                    formatClipTime(totalKept),
                    formatClipTime(durationMs),
                    formatClipTime(removed),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Assign distinct colors to segments for visual differentiation. */
@Composable
private fun segmentColor(index: Int) = when (index % 4) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    2 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}
