package app.embeddy.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.embeddy.R
import app.embeddy.squoosh.OutputFormat
import app.embeddy.squoosh.SquooshResult
import app.embeddy.squoosh.SquooshState
import app.embeddy.viewmodel.SquooshViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SquooshScreen(viewModel: SquooshViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
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
            is SquooshState.Idle -> {
                // Image picker
                ImagePickerCard(onClick = { imagePicker.launch(arrayOf("image/*")) })

                // Settings
                SquooshSettings(
                    format = config.format,
                    quality = config.quality,
                    effort = config.effort,
                    lossless = config.lossless,
                    maxDimension = config.maxDimension,
                    onFormatChanged = viewModel::setFormat,
                    onQualityChanged = viewModel::setQuality,
                    onEffortChanged = viewModel::setEffort,
                    onLosslessChanged = viewModel::setLossless,
                    onMaxDimensionChanged = viewModel::setMaxDimension,
                )
            }

            is SquooshState.Ready -> {
                // File info
                FileInfoCard(
                    fileName = s.fileName,
                    fileSize = s.fileSize,
                    onChangePick = { imagePicker.launch(arrayOf("image/*")) },
                )

                // Settings
                SquooshSettings(
                    format = config.format,
                    quality = config.quality,
                    effort = config.effort,
                    lossless = config.lossless,
                    maxDimension = config.maxDimension,
                    onFormatChanged = viewModel::setFormat,
                    onQualityChanged = viewModel::setQuality,
                    onEffortChanged = viewModel::setEffort,
                    onLosslessChanged = viewModel::setLossless,
                    onMaxDimensionChanged = viewModel::setMaxDimension,
                )

                // Compress button
                Button(
                    onClick = viewModel::compress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.compress),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            is SquooshState.Compressing -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.compressing),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is SquooshState.Done -> {
                CompressionResultCard(
                    result = s.result,
                    inputFileName = s.inputFileName,
                    onReset = viewModel::reset,
                )
            }

            is SquooshState.Error -> {
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
                                text = "Compression failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = viewModel::reset) { Text("Try again") }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ImagePickerCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.pick_image),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun FileInfoCard(fileName: String, fileSize: Long, onChangePick: () -> Unit) {
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
                Text(text = fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatFileSize(fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onChangePick) { Text("Change image") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SquooshSettings(
    format: OutputFormat,
    quality: Int,
    effort: Int,
    lossless: Boolean,
    maxDimension: Int,
    onFormatChanged: (OutputFormat) -> Unit,
    onQualityChanged: (Int) -> Unit,
    onEffortChanged: (Int) -> Unit,
    onLosslessChanged: (Boolean) -> Unit,
    onMaxDimensionChanged: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Format selector
            Text(
                text = stringResource(R.string.output_format),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputFormat.entries.forEach { fmt ->
                    FilterChip(
                        selected = format == fmt,
                        onClick = { onFormatChanged(fmt) },
                        label = { Text(fmt.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Quality slider (hidden for PNG since it's always lossless)
            if (format != OutputFormat.PNG && !lossless) {
                SettingSliderRow(
                    label = stringResource(R.string.quality),
                    value = quality.toFloat(),
                    valueRange = 1f..100f,
                    valueLabel = "$quality",
                    onValueChange = { onQualityChanged(it.roundToInt()) },
                )
            }

            // Effort slider (WebP compression_level)
            if (format == OutputFormat.WEBP) {
                SettingSliderRow(
                    label = stringResource(R.string.effort),
                    value = effort.toFloat(),
                    valueRange = 0f..6f,
                    steps = 5,
                    valueLabel = "$effort",
                    onValueChange = { onEffortChanged(it.roundToInt()) },
                )
            }

            // Max dimension slider
            val dimValue = if (maxDimension == 0) 0f else maxDimension.toFloat()
            SettingSliderRow(
                label = stringResource(R.string.max_dimension),
                value = dimValue,
                valueRange = 0f..4096f,
                valueLabel = if (maxDimension == 0) "Original" else "${maxDimension}px",
                onValueChange = { onMaxDimensionChanged(it.roundToInt()) },
            )

            // Lossless toggle (only for WebP)
            if (format == OutputFormat.WEBP) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.lossless),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = lossless, onCheckedChange = onLosslessChanged)
                }
            }
        }
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    steps: Int = 0,
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

@Composable
private fun CompressionResultCard(
    result: SquooshResult,
    inputFileName: String,
    onReset: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Success header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.compression_complete),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Preview
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(File(result.outputPath))
                    .crossfade(true)
                    .build(),
                contentDescription = "Compressed image preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )

            Spacer(Modifier.height(12.dp))

            // Size comparison
            Text(
                text = stringResource(R.string.original_size, formatFileSize(result.originalSizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.compressed_size, formatFileSize(result.compressedSizeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val savings = String.format("%.1f%%", result.savingsPercent)
            val savingsColor = if (result.savingsPercent > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
            Text(
                text = stringResource(R.string.savings, savings),
                style = MaterialTheme.typography.titleSmall,
                color = savingsColor,
            )

            Spacer(Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { shareFile(context, result.outputPath) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share))
                }

                FilledTonalButton(
                    onClick = { saveToDownloads(context, result.outputPath) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.save))
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Compress another")
            }
        }
    }
}

private fun shareFile(context: Context, path: String) {
    val file = File(path)
    val mimeType = when (file.extension.lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share image"))
}

private fun saveToDownloads(context: Context, path: String) {
    val file = File(path)
    val mimeType = when (file.extension.lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(
        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        contentValues,
    ) ?: return
    resolver.openOutputStream(uri)?.use { output ->
        file.inputStream().use { input -> input.copyTo(output) }
    }
    contentValues.clear()
    contentValues.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, contentValues, null, null)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
