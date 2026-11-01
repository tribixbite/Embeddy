package app.embeddy.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.embeddy.R
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.SourceClip
import app.embeddy.conversion.TrimSegment
import app.embeddy.util.formatClipTime
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt
import androidx.compose.ui.res.pluralStringResource

/**
 * Ordered clip list for joining separate videos: each row has a thumbnail,
 * move up/down and remove buttons (48dp targets), and a range slider for the
 * part of that clip to keep.
 */
@Composable
fun JoinClipsCard(
    clips: List<SourceClip>,
    edit: EditSpec,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRangeChanged: (Int, TrimSegment) -> Unit,
    onAddClips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = clips.indices.sumOf { edit.clipRange(it, clips[it].durationMs).durationMs }
    SourceListCard(
        title = pluralStringResource(R.plurals.join_title, clips.size, clips.size),
        summary = stringResource(R.string.join_total, formatClipTime(totalMs)),
        addLabel = stringResource(R.string.join_add_clips),
        onAdd = onAddClips,
        modifier = modifier,
    ) {
        clips.forEachIndexed { index, clip ->
            ClipRow(
                clip = clip,
                index = index,
                count = clips.size,
                subtitle = formatClipTime(edit.clipRange(index, clip.durationMs).durationMs),
                onMove = onMove,
                onRemove = onRemove,
            )
            if (clip.durationMs > 0) {
                val range = edit.clipRange(index, clip.durationMs)
                // Local drag state; committed once on release.
                var drag by remember(clip.uri) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
                val shown = drag ?: (range.startMs.toFloat()..range.endMs.toFloat())
                RangeSlider(
                    value = shown,
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { onRangeChanged(index, TrimSegment(it.start.toLong(), it.endInclusive.toLong())) }
                        drag = null
                    },
                    valueRange = 0f..clip.durationMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .systemGestureExclusion(),
                )
                Text(
                    text = "${formatClipTime(shown.start.toLong())} – ${formatClipTime(shown.endInclusive.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Ordered image list for an image sequence, with the per-frame duration.
 */
@Composable
fun ImageSequenceCard(
    images: List<SourceClip>,
    frameDurationMs: Int,
    onFrameDurationChanged: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAddImages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SourceListCard(
        title = pluralStringResource(R.plurals.sequence_title, images.size, images.size),
        summary = stringResource(R.string.join_total, formatClipTime(frameDurationMs.toLong() * images.size)),
        addLabel = stringResource(R.string.sequence_add_images),
        onAdd = onAddImages,
        modifier = modifier,
    ) {
        var dragMs by remember { mutableStateOf<Float?>(null) }
        val shownMs = dragMs ?: frameDurationMs.toFloat()
        Text(
            text = stringResource(R.string.sequence_frame_duration, formatClipTime(shownMs.roundToInt().toLong())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = shownMs,
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { onFrameDurationChanged((it / 50).roundToInt() * 50) }
                dragMs = null
            },
            valueRange = 50f..3000f,
            modifier = Modifier.fillMaxWidth(),
        )
        images.forEachIndexed { index, image ->
            ClipRow(
                clip = image,
                index = index,
                count = images.size,
                subtitle = if (image.width > 0) "${image.width}x${image.height}" else "",
                onMove = onMove,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun SourceListCard(
    title: String,
    summary: String,
    addLabel: String,
    onAdd: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(text = summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            content()
            TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(addLabel)
            }
        }
    }
}

/** Thumbnail + name + reorder/remove controls for one item. */
@Composable
private fun ClipRow(
    clip: SourceClip,
    index: Int,
    count: Int,
    subtitle: String,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            // Coil's VideoFrameDecoder renders a frame for video URIs too.
            model = ImageRequest.Builder(context).data(Uri.parse(clip.uri)).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.clip_label, index + 1, clip.fileName),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
        }
        IconButton(onClick = { onMove(index, index + 1) }, enabled = index < count - 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
        }
        IconButton(onClick = { onRemove(index) }) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_item), tint = MaterialTheme.colorScheme.error)
        }
    }
}
