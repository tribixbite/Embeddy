package app.embeddy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.embeddy.R
import app.embeddy.conversion.ColorSpace
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.DitherMode
import app.embeddy.conversion.Preset
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPanel(
    config: ConversionConfig,
    onPresetSelected: (Preset) -> Unit,
    onConfigChanged: (ConversionConfig.() -> ConversionConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with expand toggle
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse settings" else "Expand settings",
                )
            }

            // Preset chips — always visible
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Preset.entries.forEach { preset ->
                    FilterChip(
                        selected = config.preset == preset,
                        onClick = { onPresetSelected(preset) },
                        label = { Text(preset.label) },
                    )
                }
            }

            // Expandable detailed settings
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))

                    // Max dimension slider
                    SettingSlider(
                        label = stringResource(R.string.max_dimension),
                        value = config.maxDimension.toFloat(),
                        valueRange = 240f..1280f,
                        steps = 12,
                        valueLabel = "${config.maxDimension}px",
                        onValueChange = { dim ->
                            onConfigChanged { copy(maxDimension = dim.roundToInt()) }
                        },
                    )

                    // Exact dimensions
                    Text(
                        text = stringResource(R.string.exact_dimensions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = if (config.exactWidth > 0) config.exactWidth.toString() else "",
                            onValueChange = { text ->
                                val w = text.toIntOrNull() ?: 0
                                onConfigChanged { copy(exactWidth = w) }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.width_hint)) },
                            placeholder = { Text("0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                        )
                        OutlinedTextField(
                            value = if (config.exactHeight > 0) config.exactHeight.toString() else "",
                            onValueChange = { text ->
                                val h = text.toIntOrNull() ?: 0
                                onConfigChanged { copy(exactHeight = h) }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.height_hint)) },
                            placeholder = { Text("0") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // FPS slider
                    SettingSlider(
                        label = stringResource(R.string.frame_rate),
                        value = config.fps.toFloat(),
                        valueRange = 6f..30f,
                        steps = 7,
                        valueLabel = "${config.fps} fps",
                        onValueChange = { fps ->
                            onConfigChanged { copy(fps = fps.roundToInt()) }
                        },
                    )

                    // Quality slider
                    SettingSlider(
                        label = stringResource(R.string.quality),
                        value = config.startQuality.toFloat(),
                        valueRange = 30f..100f,
                        steps = 13,
                        valueLabel = "${config.startQuality}",
                        onValueChange = { q ->
                            onConfigChanged { copy(startQuality = q.roundToInt()) }
                        },
                    )

                    // Target size slider (in MB)
                    val sizeMb = config.targetSizeBytes / 1_000_000f
                    SettingSlider(
                        label = stringResource(R.string.target_size),
                        value = sizeMb,
                        valueRange = 0.25f..25f,
                        steps = 0,
                        valueLabel = String.format("%.1f MB", sizeMb),
                        onValueChange = { mb ->
                            onConfigChanged { copy(targetSizeBytes = (mb * 1_000_000).toLong()) }
                        },
                    )

                    // Sharpen toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.sharpen),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Switch(
                            checked = config.sharpen,
                            onCheckedChange = { on ->
                                onConfigChanged { copy(sharpen = on) }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Advanced FFmpeg encoding flags ──
                    TextButton(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = stringResource(R.string.advanced_settings),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                    }

                    AnimatedVisibility(
                        visible = advancedExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            // 1. Denoise strength (hqdn3d)
                            SettingSlider(
                                label = stringResource(R.string.denoise),
                                value = config.denoiseStrength.toFloat(),
                                valueRange = 0f..10f,
                                steps = 9,
                                valueLabel = if (config.denoiseStrength == 0) "Off" else "${config.denoiseStrength}",
                                onValueChange = { v ->
                                    onConfigChanged { copy(denoiseStrength = v.roundToInt()) }
                                },
                            )

                            // 2. Color space selector
                            Text(
                                text = stringResource(R.string.color_space),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ColorSpace.entries.forEach { cs ->
                                    FilterChip(
                                        selected = config.colorSpace == cs,
                                        onClick = { onConfigChanged { copy(colorSpace = cs) } },
                                        label = { Text(cs.label) },
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // 3. Dithering mode
                            Text(
                                text = stringResource(R.string.dithering),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DitherMode.entries.forEach { dm ->
                                    FilterChip(
                                        selected = config.ditherMode == dm,
                                        onClick = { onConfigChanged { copy(ditherMode = dm) } },
                                        label = { Text(dm.label) },
                                    )
                                }
                            }

                            Spacer(Modifier.height(12.dp))

                            // 4. Keyframe interval
                            SettingSlider(
                                label = stringResource(R.string.keyframe_interval),
                                value = config.keyframeInterval.toFloat(),
                                valueRange = 0f..120f,
                                steps = 0,
                                valueLabel = if (config.keyframeInterval == 0) "Auto" else "${config.keyframeInterval} frames",
                                onValueChange = { v ->
                                    onConfigChanged { copy(keyframeInterval = v.roundToInt()) }
                                },
                            )

                            // 5. Compression level (0-6, higher = slower but smaller)
                            SettingSlider(
                                label = stringResource(R.string.compression_level),
                                value = config.compressionLevel.toFloat(),
                                valueRange = 0f..6f,
                                steps = 5,
                                valueLabel = "${config.compressionLevel}",
                                onValueChange = { v ->
                                    onConfigChanged { copy(compressionLevel = v.roundToInt()) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
