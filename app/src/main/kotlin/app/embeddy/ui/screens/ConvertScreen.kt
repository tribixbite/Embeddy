package app.embeddy.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.embeddy.R
import app.embeddy.conversion.ConversionState
import app.embeddy.ui.components.ConversionProgressCard
import app.embeddy.ui.components.MediaPickerCard
import app.embeddy.ui.components.OutputPreviewCard
import app.embeddy.ui.components.PreviewCard
import app.embeddy.ui.components.SettingsPanel
import app.embeddy.ui.components.VideoTrimPlayer
import app.embeddy.util.SizeEstimation
import app.embeddy.viewmodel.InspectViewModel
import app.embeddy.viewmodel.MainViewModel

@Composable
fun ConvertScreen(
    viewModel: MainViewModel = viewModel(),
    inspectViewModel: InspectViewModel = viewModel(),
    onNavigateToInspect: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFilePicked(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            is ConversionState.Idle -> {
                MediaPickerCard(
                    onClick = {
                        filePicker.launch(arrayOf("video/*", "image/gif"))
                    },
                )
                SettingsPanel(
                    config = config,
                    onPresetSelected = viewModel::setPreset,
                    onConfigChanged = viewModel::updateConfig,
                )
            }

            is ConversionState.Picking -> {
                MediaPickerCard(
                    onClick = {
                        filePicker.launch(arrayOf("video/*", "image/gif"))
                    },
                )
            }

            is ConversionState.Ready -> {
                // File info card with inspect button
                ReadyCard(
                    state = s,
                    onChangePick = {
                        filePicker.launch(arrayOf("video/*", "image/gif"))
                    },
                    onInspect = {
                        // Navigate to inspect tab with this file's URI
                        inspectViewModel.inspectFile(Uri.parse(s.inputUri))
                        onNavigateToInspect?.invoke()
                    },
                )

                // Video preview with trim controls
                if (s.durationMs > 0) {
                    // Compute effective output resolution for BPP estimation
                    val estWidth = SizeEstimation.estimateOutputWidth(s.width, s.height, config)
                    val estHeight = SizeEstimation.estimateOutputHeight(s.width, s.height, config)

                    VideoTrimPlayer(
                        uri = Uri.parse(s.inputUri),
                        durationMs = s.durationMs,
                        inputSizeBytes = s.fileSize,
                        targetSizeBytes = config.targetSizeBytes,
                        trimStartMs = config.trimStartMs,
                        trimEndMs = config.trimEndMs,
                        onTrimChanged = viewModel::setTrim,
                        outputWidth = estWidth,
                        outputHeight = estHeight,
                        outputFps = config.fps,
                        outputQuality = config.startQuality,
                        segments = config.segments,
                        onSegmentsChanged = viewModel::setSegments,
                    )
                }

                SettingsPanel(
                    config = config,
                    onPresetSelected = viewModel::setPreset,
                    onConfigChanged = viewModel::updateConfig,
                    onPreview = viewModel::startPreview,
                )

                // Convert button with target size shown
                val targetMb = String.format("%.1f MB", config.targetSizeBytes / 1_000_000.0)
                Button(
                    onClick = viewModel::startConversion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        Icons.Default.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${stringResource(R.string.convert)} ($targetMb target)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            is ConversionState.Converting -> {
                ConversionProgressCard(
                    state = s,
                    targetSizeBytes = config.targetSizeBytes,
                    onCancel = viewModel::cancelConversion,
                )
            }

            is ConversionState.Done -> {
                OutputPreviewCard(
                    state = s,
                    onNewConversion = viewModel::reset,
                )
            }

            is ConversionState.SizeWarning -> {
                SizeWarningCard(
                    state = s,
                    onAccept = viewModel::acceptOversize,
                    onRetry = viewModel::reset,
                )
            }

            is ConversionState.Previewing -> {
                ConversionProgressCard(
                    state = ConversionState.Converting(
                        progress = s.progress,
                        currentQuality = config.startQuality,
                        attempt = 1,
                        elapsedMs = s.elapsedMs,
                    ),
                    targetSizeBytes = config.targetSizeBytes,
                    onCancel = viewModel::cancelPreview,
                    label = stringResource(R.string.preview_generating),
                )
            }

            is ConversionState.PreviewReady -> {
                PreviewCard(
                    previewPath = s.previewPath,
                    fileSizeBytes = s.fileSizeBytes,
                    qualityUsed = config.startQuality,
                    onDismiss = viewModel::dismissPreview,
                )
            }

            is ConversionState.Error -> {
                ErrorCard(
                    message = s.message,
                    onRetry = viewModel::reset,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ReadyCard(
    state: ConversionState.Ready,
    onChangePick: () -> Unit,
    onInspect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // Info icon to inspect media metadata
                IconButton(
                    onClick = onInspect,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Inspect media metadata",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val sizeMb = String.format("%.1f MB", state.fileSize / 1_000_000.0)
            val duration = if (state.durationMs > 0) {
                val secs = state.durationMs / 1000
                "${secs / 60}m ${secs % 60}s"
            } else "N/A"
            val resolution = if (state.width > 0) "${state.width}x${state.height}" else "N/A"

            Text(
                text = "$sizeMb · $resolution · $duration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onChangePick) {
                Text(stringResource(R.string.change_file))
            }
        }
    }
}

/** Shown when conversion completed but output exceeds the target size. */
@Composable
private fun SizeWarningCard(
    state: ConversionState.SizeWarning,
    onAccept: () -> Unit,
    onRetry: () -> Unit,
) {
    val actualMb = String.format("%.2f MB", state.outputSizeBytes / 1_000_000.0)
    val targetMb = String.format("%.1f MB", state.targetSizeBytes / 1_000_000.0)
    val overBy = String.format(
        "%.1f%%",
        (state.outputSizeBytes - state.targetSizeBytes).toFloat() / state.targetSizeBytes * 100,
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.size_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.size_warning_body, actualMb, targetMb, overBy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Quality: ${state.qualityUsed} (lowest tried)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.use_anyway))
                }

                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.trim_and_retry))
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.conversion_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}
