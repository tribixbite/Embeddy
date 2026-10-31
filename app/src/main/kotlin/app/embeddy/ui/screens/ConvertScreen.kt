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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionState
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.ErrorKind
import app.embeddy.conversion.InputSpec
import app.embeddy.conversion.OutputMediaFormat
import app.embeddy.conversion.REVERSE_MAX_OUTPUT_MS
import app.embeddy.conversion.SourceKind
import app.embeddy.conversion.editedDimensions
import app.embeddy.conversion.outputDurationMs
import app.embeddy.conversion.reverseAllowed
import app.embeddy.ui.components.EditToolsCard
import app.embeddy.ui.components.FrameGrabSheet
import app.embeddy.ui.components.ImageSequenceCard
import app.embeddy.ui.components.JoinClipsCard
import app.embeddy.ui.isAnimatedImageFile
import app.embeddy.ui.components.ConversionProgressCard
import app.embeddy.ui.components.MediaPickerCard
import app.embeddy.ui.components.OutputPreviewCard
import app.embeddy.ui.components.PreviewCard
import app.embeddy.ui.components.SettingsPanel
import app.embeddy.ui.components.VideoTrimPlayer
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.SizeEstimation
import app.embeddy.util.formatMediaDuration
import app.embeddy.viewmodel.availableOutputFormats
import app.embeddy.viewmodel.canAddClips
import java.util.Locale
import app.embeddy.viewmodel.InspectViewModel
import app.embeddy.viewmodel.MainViewModel

/** Input formats the Convert tab can decode: video, GIF, and animated WebP. */
private val CONVERT_MIME_TYPES = arrayOf("video/*", "image/gif", "image/webp")

/** Inputs that can be joined back to back (FFmpeg demuxes these directly). */
private val JOIN_MIME_TYPES = arrayOf("video/*", "image/gif")

/** Still images for an image sequence (decoded by Android, so HEIC works too). */
private val IMAGE_MIME_TYPES = arrayOf("image/*")

@Composable
fun ConvertScreen(
    viewModel: MainViewModel = viewModel(),
    inspectViewModel: InspectViewModel = viewModel(),
    onNavigateToInspect: (() -> Unit)? = null,
    /** Hand a grabbed frame to the Squoosh tab. */
    onOpenInSquoosh: ((Uri) -> Unit)? = null,
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val config by controller.config.collectAsStateWithLifecycle()
    val edit by controller.edit.collectAsStateWithLifecycle()
    val frameGrab by controller.frameGrab.collectAsStateWithLifecycle()
    // The crop overlay is a transient UI mode, reset per input.
    var cropEditing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            cropEditing = false
            controller.onFilePicked(it.toString())
        }
    }
    val joinPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.onFilesPicked(uris.map(Uri::toString), SourceKind.JOIN)
    }
    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.onFilesPicked(uris.map(Uri::toString), SourceKind.IMAGE_SEQUENCE)
    }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        controller.addFiles(uris.map(Uri::toString))
    }

    FrameGrabSheet(
        state = frameGrab,
        onDismiss = controller::dismissFrameGrab,
        onOpenInSquoosh = { uri ->
            controller.dismissFrameGrab()
            onOpenInSquoosh?.invoke(uri)
        },
    )

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
                    onClick = { filePicker.launch(CONVERT_MIME_TYPES) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { joinPicker.launch(JOIN_MIME_TYPES) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.join_videos))
                    }
                    OutlinedButton(
                        onClick = { imagesPicker.launch(IMAGE_MIME_TYPES) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.Collections, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.images_to_animation))
                    }
                }
                SettingsPanel(
                    config = config,
                    onPresetSelected = controller::setPreset,
                    onConfigChanged = controller::updateConfig,
                )
            }

            is ConversionState.Loading -> LoadingCard()

            is ConversionState.Ready -> {
                val source = s.source
                val animatedImageInput = source.kind == SourceKind.SINGLE && isAnimatedImageFile(s.fileName)
                val outputHasVideo = config.outputFormat != OutputMediaFormat.AUDIO
                val outputMs = outputDurationMs(edit, source.clips.map { InputSpec("", durationMs = it.durationMs) })
                    .takeIf { source.kind != SourceKind.IMAGE_SEQUENCE }
                    ?: (edit.frameDurationMs.toLong() * source.clips.size)

                when (source.kind) {
                    SourceKind.SINGLE -> {
                        ReadyCard(
                            state = s,
                            onChangePick = {
                                cropEditing = false
                                filePicker.launch(CONVERT_MIME_TYPES)
                            },
                            onAddClips = if (canAddClips(source)) {
                                {
                                    cropEditing = false
                                    addPicker.launch(JOIN_MIME_TYPES)
                                }
                            } else null,
                            onInspect = {
                                inspectViewModel.inspectFile(Uri.parse(s.inputUri))
                                onNavigateToInspect?.invoke()
                            },
                        )
                        if (s.durationMs > 0) {
                            VideoTrimPlayer(
                                uri = Uri.parse(s.inputUri),
                                durationMs = s.durationMs,
                                edit = edit,
                                targetSizeBytes = config.targetSizeBytes,
                                onTrimChanged = controller::setTrim,
                                onSegmentsChanged = controller::setSegments,
                                estimateBytes = { keptMs -> estimateBytes(config, s, edit, keptMs) },
                                // GIF/animated WebP would render as a dead black ExoPlayer
                                // surface — show a Coil animated preview instead (sliders kept).
                                animatedImagePreview = animatedImageInput,
                                // Per-segment volume only matters when the output keeps audio.
                                showVolume = config.outputFormat.isVideo && s.hasAudio && !edit.muted ||
                                    config.outputFormat == OutputMediaFormat.AUDIO,
                                contentWidth = s.width,
                                contentHeight = s.height,
                                cropEditing = cropEditing && outputHasVideo,
                                onCropCommit = { rect -> controller.updateEdit { copy(crop = rect.takeUnless { it.isFullFrame }) } },
                                onGrabFrame = controller::grabFrame,
                            )
                        }
                    }
                    SourceKind.JOIN -> JoinClipsCard(
                        clips = source.clips,
                        edit = edit,
                        onMove = controller::moveClip,
                        onRemove = controller::removeClip,
                        onRangeChanged = controller::setClipRange,
                        onAddClips = { addPicker.launch(JOIN_MIME_TYPES) },
                    )
                    SourceKind.IMAGE_SEQUENCE -> ImageSequenceCard(
                        images = source.clips,
                        frameDurationMs = edit.frameDurationMs,
                        onFrameDurationChanged = { ms -> controller.updateEdit { copy(frameDurationMs = ms) } },
                        onMove = controller::moveClip,
                        onRemove = controller::removeClip,
                        onAddImages = { addPicker.launch(IMAGE_MIME_TYPES) },
                    )
                }
                if (source.kind != SourceKind.SINGLE) {
                    TextButton(onClick = controller::reset, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.start_over))
                    }
                }

                if (outputHasVideo) {
                    EditToolsCard(
                        edit = edit,
                        onEditChanged = controller::updateEdit,
                        showMute = config.outputFormat.isVideo && s.hasAudio,
                        showCrop = source.kind == SourceKind.SINGLE && s.durationMs > 0,
                        showSpeed = source.kind != SourceKind.IMAGE_SEQUENCE,
                        reverseAllowed = reverseAllowed(outputMs),
                        reverseLimitMs = REVERSE_MAX_OUTPUT_MS,
                        cropEditing = cropEditing,
                        onCropEditingChanged = { cropEditing = it },
                    )
                } else {
                    // Audio-only output: speed and reverse still apply.
                    EditToolsCard(
                        edit = edit,
                        onEditChanged = controller::updateEdit,
                        showMute = false,
                        showCrop = false,
                        showSpeed = true,
                        reverseAllowed = reverseAllowed(outputMs),
                        reverseLimitMs = REVERSE_MAX_OUTPUT_MS,
                        cropEditing = false,
                        onCropEditingChanged = {},
                    )
                }

                SettingsPanel(
                    config = config,
                    onPresetSelected = controller::setPreset,
                    onConfigChanged = controller::updateConfig,
                    onPreview = controller::startPreview,
                    availableFormats = availableOutputFormats(s.source),
                    // Previews are animated WebP renders; an audio-only output has nothing to show.
                    previewEnabled = outputHasVideo,
                )

                // Convert button — shows the selected format and target size
                val formatLabel = config.outputFormat.label
                val convertLabel = if (config.targetSizeBytes > 0) {
                    stringResource(
                        R.string.convert_to_with_target,
                        formatLabel,
                        FileInfoUtils.formatFileSize(config.targetSizeBytes),
                    )
                } else {
                    stringResource(R.string.convert_to, formatLabel)
                }
                Button(
                    onClick = controller::startConversion,
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
                        text = convertLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            is ConversionState.Converting -> {
                ConversionProgressCard(
                    state = s,
                    targetSizeBytes = config.targetSizeBytes,
                    onCancel = controller::cancelConversion,
                )
            }

            is ConversionState.Done -> {
                OutputPreviewCard(
                    state = s,
                    onNewConversion = controller::reset,
                    onEditAgain = if (s.returnTo != null) controller::backToReady else null,
                )
            }

            is ConversionState.SizeWarning -> {
                SizeWarningCard(
                    state = s,
                    onAccept = controller::acceptOversize,
                    // Back to the same file with the edit intact, not the picker.
                    onRetry = controller::backToReady,
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
                    onCancel = controller::cancelPreview,
                    label = stringResource(R.string.preview_generating),
                )
            }

            is ConversionState.PreviewReady -> {
                PreviewCard(
                    previewPath = s.previewPath,
                    fileSizeBytes = s.fileSizeBytes,
                    qualityUsed = config.startQuality,
                    onDismiss = controller::dismissPreview,
                )
            }

            is ConversionState.Error -> {
                ErrorCard(
                    message = when (s.kind) {
                        ErrorKind.READ_FILE -> stringResource(R.string.error_read_file, s.message)
                        ErrorKind.CONVERSION -> s.message
                    },
                    onRetry = controller::backToReady,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** Format-aware size estimate for the single-input trim card. */
private fun estimateBytes(config: ConversionConfig, s: ConversionState.Ready, edit: EditSpec, keptMs: Long): Long {
    val (w, h) = editedDimensions(s.width, s.height, edit)
    return SizeEstimation.estimateConvertedBytes(
        config = config,
        inputWidth = w,
        inputHeight = h,
        keptMs = (keptMs / edit.effectiveSpeed).toLong(),
        hasAudio = s.hasAudio && !edit.muted,
        inputSizeBytes = s.fileSize,
        totalDurationMs = s.durationMs,
    )
}

/** Spinner shown while a picked file is being probed. */
@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.loading_media),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ReadyCard(
    state: ConversionState.Ready,
    onChangePick: () -> Unit,
    onInspect: () -> Unit,
    /** Join more clips onto this one; null when the input cannot be joined. */
    onAddClips: (() -> Unit)? = null,
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
                // Decorative file-type glyph; distinct from the Inspect (i) button
                // beside the name, which used to share this icon.
                Icon(
                    Icons.Outlined.VideoFile,
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
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.inspect_media_desc),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Some providers report a NULL size column, which would otherwise render
            // as a misleading "0.0 MB" for a multi-megabyte file.
            val sizeLabel = if (state.fileSize > 0) {
                FileInfoUtils.formatFileSize(state.fileSize)
            } else stringResource(R.string.unknown_size)
            val duration = if (state.durationMs > 0) {
                formatMediaDuration(state.durationMs)
            } else stringResource(R.string.not_available)
            val resolution = if (state.width > 0) {
                "${state.width}x${state.height}"
            } else stringResource(R.string.not_available)

            Text(
                text = "$sizeLabel · $resolution · $duration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Transient notice (e.g. a preview that failed to render)
            state.notice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row {
                TextButton(onClick = onChangePick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.change_file))
                }
                onAddClips?.let { addClips ->
                    TextButton(onClick = addClips, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.join_add_clips))
                    }
                }
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
    val actualMb = FileInfoUtils.formatFileSize(state.outputSizeBytes)
    val targetMb = FileInfoUtils.formatFileSize(state.targetSizeBytes)
    val overBy = String.format(
        Locale.getDefault(),
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
                text = stringResource(R.string.quality_lowest_tried, state.qualityUsed),
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
