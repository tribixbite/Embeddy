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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.embeddy.R
import app.embeddy.conversion.ConversionConfig
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
                    contentDescription = null,
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
