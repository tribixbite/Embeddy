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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.embeddy.R
import app.embeddy.conversion.TrimSegment
import app.embeddy.util.SizeEstimation
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Video preview with playback controls and trim/stitch support.
 *
 * Supports two modes:
 * - **Simple trim**: single range slider to set start/end points
 * - **Stitch mode**: multiple segments to keep, with gaps removed from the output.
 *   Users can add/remove segments to selectively cut sections from the video.
 */
@Composable
fun VideoTrimPlayer(
    uri: Uri,
    durationMs: Long,
    inputSizeBytes: Long,
    targetSizeBytes: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTrimChanged: (startMs: Long, endMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Output width for BPP estimation (0 = fallback to heuristic). */
    outputWidth: Int = 0,
    /** Output height for BPP estimation (0 = fallback to heuristic). */
    outputHeight: Int = 0,
    /** Target FPS for BPP estimation. */
    outputFps: Int = 12,
    /** Quality setting (1-100) used to estimate bits-per-pixel. */
    outputQuality: Int = 70,
    /** Multi-segment stitch list. When non-empty, overrides single trim. */
    segments: List<TrimSegment> = emptyList(),
    /** Callback when segments change (stitch mode). */
    onSegmentsChanged: (List<TrimSegment>) -> Unit = {},
) {
    val context = LocalContext.current
    val effectiveDuration = if (durationMs > 0) durationMs else 1L

    // Stitch mode toggle
    var stitchMode by remember { mutableStateOf(segments.isNotEmpty()) }

    // ExoPlayer instance — create once per URI
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(uri) {
        onDispose { exoPlayer.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }

    // Poll position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPositionMs = exoPlayer.currentPosition
            val end = if (stitchMode && segments.isNotEmpty()) {
                segments.last().endMs
            } else {
                if (trimEndMs > 0) trimEndMs else effectiveDuration
            }
            if (currentPositionMs >= end) {
                val start = if (stitchMode && segments.isNotEmpty()) {
                    segments.first().startMs
                } else trimStartMs
                exoPlayer.seekTo(start)
            }
            delay(100)
        }
    }

    // Calculate total kept duration for size estimation
    val totalKeptMs = if (stitchMode && segments.isNotEmpty()) {
        segments.sumOf { it.durationMs }
    } else {
        val trimStart = if (trimStartMs > 0) trimStartMs else 0L
        val trimEnd = if (trimEndMs > 0) trimEndMs else effectiveDuration
        (trimEnd - trimStart).coerceAtLeast(1)
    }

    // BPP-based size estimation (shared utility)
    val estimatedSize = SizeEstimation.estimateOutputBytes(
        width = outputWidth,
        height = outputHeight,
        durationMs = totalKeptMs,
        fps = outputFps,
        quality = outputQuality,
        inputSizeBytes = inputSizeBytes,
        totalDurationMs = effectiveDuration,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Video player surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Play/pause overlay
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            isPlaying = false
                        } else {
                            val end = if (stitchMode && segments.isNotEmpty()) {
                                segments.last().endMs
                            } else {
                                if (trimEndMs > 0) trimEndMs else effectiveDuration
                            }
                            if (exoPlayer.currentPosition >= end) {
                                val start = if (stitchMode && segments.isNotEmpty()) {
                                    segments.first().startMs
                                } else trimStartMs
                                exoPlayer.seekTo(start)
                            }
                            exoPlayer.play()
                            isPlaying = true
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Trim header with stitch mode toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = stringResource(R.string.trim),
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
                // Stitch mode toggle chip
                FilterChip(
                    selected = stitchMode,
                    onClick = {
                        stitchMode = !stitchMode
                        if (stitchMode && segments.isEmpty()) {
                            // Initialize with one segment covering the current trim range
                            val start = if (trimStartMs > 0) trimStartMs else 0L
                            val end = if (trimEndMs > 0) trimEndMs else effectiveDuration
                            onSegmentsChanged(listOf(TrimSegment(start, end)))
                        } else if (!stitchMode) {
                            // Revert to simple trim from first segment
                            val first = segments.firstOrNull()
                            if (first != null) {
                                onTrimChanged(first.startMs, first.endMs)
                            }
                            onSegmentsChanged(emptyList())
                        }
                    },
                    label = { Text(stringResource(R.string.stitch_mode), style = MaterialTheme.typography.labelSmall) },
                )
            }

            if (stitchMode) {
                // Multi-segment stitch UI
                StitchSegments(
                    segments = segments,
                    durationMs = effectiveDuration,
                    exoPlayer = exoPlayer,
                    onSegmentsChanged = onSegmentsChanged,
                )
            } else {
                // Simple single-range trim slider
                val sliderRange = 0f..effectiveDuration.toFloat()
                val trimStart = if (trimStartMs > 0) trimStartMs.toFloat() else 0f
                val trimEnd = if (trimEndMs > 0) trimEndMs.toFloat() else effectiveDuration.toFloat()

                RangeSlider(
                    value = trimStart..trimEnd,
                    onValueChange = { range ->
                        val newStart = range.start.toLong()
                        val newEnd = range.endInclusive.toLong()
                        onTrimChanged(newStart, newEnd)
                        exoPlayer.seekTo(newStart)
                    },
                    valueRange = sliderRange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${formatTime(trimStart.toLong())} – ${formatTime(trimEnd.toLong())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatTime(totalKeptMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Size estimate vs target
            val estStr = formatFileSize(estimatedSize)
            val targetStr = formatFileSize(targetSizeBytes)
            val overTarget = estimatedSize > targetSizeBytes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.est_output, estStr),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overTarget) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.target_label, targetStr),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * UI for managing multiple stitch segments.
 * Each segment has its own range slider and can be added/removed.
 */
@Composable
private fun StitchSegments(
    segments: List<TrimSegment>,
    durationMs: Long,
    exoPlayer: ExoPlayer,
    onSegmentsChanged: (List<TrimSegment>) -> Unit,
) {
    val sliderRange = 0f..durationMs.toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEachIndexed { index, segment ->
            // Segment label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.segment_label, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                // Remove button (only if more than 1 segment)
                if (segments.size > 1) {
                    IconButton(
                        onClick = {
                            val updated = segments.toMutableList().apply { removeAt(index) }
                            onSegmentsChanged(updated)
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Remove,
                            contentDescription = stringResource(R.string.remove_segment),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Range slider for this segment
            RangeSlider(
                value = segment.startMs.toFloat()..segment.endMs.toFloat(),
                onValueChange = { range ->
                    val updated = segments.toMutableList().apply {
                        set(index, TrimSegment(range.start.toLong(), range.endInclusive.toLong()))
                    }
                    onSegmentsChanged(updated.sortedBy { it.startMs })
                    exoPlayer.seekTo(range.start.toLong())
                },
                valueRange = sliderRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = segmentColor(index),
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )

            // Time labels for this segment
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatTime(segment.startMs)} – ${formatTime(segment.endMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTime(segment.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = segmentColor(index),
                )
            }
        }

        // Add segment button
        TextButton(
            onClick = {
                // Add a new segment at the end of the video (last 20% or after last segment)
                val lastEnd = segments.maxOfOrNull { it.endMs } ?: 0L
                val newStart = lastEnd.coerceAtMost(durationMs - 1000)
                    .coerceAtLeast(0)
                val newEnd = durationMs
                if (newStart < newEnd) {
                    onSegmentsChanged(segments + TrimSegment(newStart, newEnd))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_segment), style = MaterialTheme.typography.labelMedium)
        }

        // Total kept duration summary
        val totalKept = segments.sumOf { it.durationMs }
        val removed = durationMs - totalKept
        if (removed > 0) {
            Text(
                text = "Keeping ${formatTime(totalKept)} of ${formatTime(durationMs)} (removing ${formatTime(removed)})",
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

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val frac = (ms % 1000) / 100
    return if (min > 0) "${min}:${sec.toString().padStart(2, '0')}.${frac}"
    else "${sec}.${frac}s"
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
