package app.embeddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.embeddy.R
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.SPEED_PRESETS
import app.embeddy.util.formatClipTime
import java.util.Locale

/**
 * Quick-edit tools for the current input: speed, rotate/flip, crop, mute and
 * reverse. Each control writes straight into the per-input [EditSpec].
 *
 * @param showMute the output keeps an audio track (video format, input has audio)
 * @param showCrop the source supports the crop overlay (single input)
 * @param reverseAllowed the output is short enough to reverse
 * @param cropEditing the crop overlay is currently shown
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditToolsCard(
    edit: EditSpec,
    onEditChanged: (EditSpec.() -> EditSpec) -> Unit,
    showMute: Boolean,
    showCrop: Boolean,
    showSpeed: Boolean,
    reverseAllowed: Boolean,
    reverseLimitMs: Long,
    cropEditing: Boolean,
    onCropEditingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.edit_tools),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (showSpeed) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.edit_speed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_PRESETS.forEach { speed ->
                        FilterChip(
                            selected = edit.effectiveSpeed == speed,
                            onClick = { onEditChanged { copy(speed = speed) } },
                            label = { Text(stringResource(R.string.speed_factor, formatSpeed(speed))) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.edit_orientation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Material icon buttons are 48dp touch targets by default.
                // The button itself has no on/off state, so show (and announce) the
                // current angle; otherwise a tap gives no visible feedback at all.
                val rotation = edit.normalizedRotation
                OutlinedIconButton(onClick = { onEditChanged { copy(rotationDegrees = (normalizedRotation + 90) % 360) } }) {
                    Icon(
                        Icons.AutoMirrored.Filled.RotateRight,
                        contentDescription = stringResource(R.string.edit_rotate_state, rotation),
                    )
                }
                if (rotation != 0) {
                    Text(
                        text = stringResource(R.string.edit_rotation_degrees, rotation),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                FilledTonalIconToggleButton(
                    checked = edit.flipHorizontal,
                    onCheckedChange = { on -> onEditChanged { copy(flipHorizontal = on) } },
                ) {
                    Icon(Icons.Default.Flip, contentDescription = stringResource(R.string.edit_flip_horizontal))
                }
                FilledTonalIconToggleButton(
                    checked = edit.flipVertical,
                    onCheckedChange = { on -> onEditChanged { copy(flipVertical = on) } },
                ) {
                    Icon(
                        Icons.Default.Flip,
                        contentDescription = stringResource(R.string.edit_flip_vertical),
                        modifier = Modifier.rotate(90f),
                    )
                }
                if (showCrop) {
                    FilledTonalIconToggleButton(checked = cropEditing, onCheckedChange = onCropEditingChanged) {
                        Icon(Icons.Default.Crop, contentDescription = stringResource(R.string.edit_crop))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (edit.normalizedRotation != 0) {
                    Text(
                        text = stringResource(R.string.rotation_degrees, edit.normalizedRotation),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (showCrop && edit.crop != null && !edit.crop.isFullFrame) {
                androidx.compose.material3.TextButton(
                    onClick = { onEditChanged { copy(crop = null) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.edit_crop_reset))
                }
            }

            if (showMute) {
                ToggleRow(
                    label = stringResource(R.string.edit_mute),
                    checked = edit.muted,
                    enabled = true,
                    onCheckedChange = { on -> onEditChanged { copy(muted = on) } },
                )
            }
            ToggleRow(
                label = stringResource(R.string.edit_reverse),
                supporting = if (!reverseAllowed) {
                    stringResource(R.string.edit_reverse_limit, formatClipTime(reverseLimitMs))
                } else null,
                checked = edit.reverse && reverseAllowed,
                enabled = reverseAllowed,
                onCheckedChange = { on -> onEditChanged { copy(reverse = on) } },
            )
        }
    }
}

/** Switch row whose whole surface (≥48dp) toggles. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** "0.25", "1", "1.5" — trailing zeros dropped. */
internal fun formatSpeed(speed: Float): String =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
