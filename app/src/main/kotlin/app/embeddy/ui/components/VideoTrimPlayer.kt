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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Video preview with playback controls and a trim range slider.
 * Reports trim selection back via onTrimChanged(startMs, endMs).
 * Also shows an estimated output size based on trimmed duration vs input bitrate.
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
) {
    val context = LocalContext.current
    val effectiveDuration = if (durationMs > 0) durationMs else 1L

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

    // Track playback position for the position indicator
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }

    // Poll position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPositionMs = exoPlayer.currentPosition
            // Auto-loop within trim range
            val end = if (trimEndMs > 0) trimEndMs else effectiveDuration
            if (currentPositionMs >= end) {
                exoPlayer.seekTo(trimStartMs)
            }
            delay(100)
        }
    }

    // Trim slider range (0..duration in ms, mapped to float for RangeSlider)
    val sliderRange = 0f..effectiveDuration.toFloat()
    val trimStart = if (trimStartMs > 0) trimStartMs.toFloat() else 0f
    val trimEnd = if (trimEndMs > 0) trimEndMs.toFloat() else effectiveDuration.toFloat()

    // Size estimation using bits-per-pixel (BPP) model.
    // BPP ranges from ~0.05 (low quality) to ~0.3 (high quality) for animated WebP.
    // Formula: estimatedBytes = (width * height * totalFrames * bpp) / 8
    val trimmedDuration = (trimEnd - trimStart).toLong().coerceAtLeast(1)
    val estimatedSize = if (outputWidth > 0 && outputHeight > 0 && effectiveDuration > 0) {
        val bpp = 0.05f + (outputQuality / 100f) * 0.25f  // maps quality 0-100 → bpp 0.05-0.30
        val totalFrames = (trimmedDuration / 1000f * outputFps).toLong().coerceAtLeast(1)
        val totalPixels = outputWidth.toLong() * outputHeight.toLong()
        (totalPixels * totalFrames * bpp / 8f).toLong()
    } else if (effectiveDuration > 0) {
        // Fallback: proportional duration ratio with conservative 0.3x heuristic
        (inputSizeBytes * trimmedDuration.toFloat() / effectiveDuration * 0.3f).toLong()
    } else inputSizeBytes

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
                            useController = false // We provide custom controls
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // Play/pause overlay button
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                            isPlaying = false
                        } else {
                            // Start from trim start if at end
                            val end = if (trimEndMs > 0) trimEndMs else effectiveDuration
                            if (exoPlayer.currentPosition >= end) {
                                exoPlayer.seekTo(trimStartMs)
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

            // Trim range slider
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
            }

            RangeSlider(
                value = trimStart..trimEnd,
                onValueChange = { range ->
                    val newStart = range.start.toLong()
                    val newEnd = range.endInclusive.toLong()
                    onTrimChanged(newStart, newEnd)
                    // Seek player to start of new range for preview
                    exoPlayer.seekTo(newStart)
                },
                valueRange = sliderRange,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )

            // Trim times + size estimate
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
                    text = formatTime(trimmedDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    text = "Est. output: $estStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overTarget) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Target: $targetStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
