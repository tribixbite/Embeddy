package app.embeddy.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.embeddy.R
import app.embeddy.conversion.ConversionState
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import java.io.File

@Composable
fun OutputPreviewCard(
    state: ConversionState.Done,
    onNewConversion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeMb = String.format("%.2f", state.outputSizeBytes / 1_000_000.0)

    // Cache ImageLoader across recompositions to avoid recreation per frame
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(ImageDecoderDecoder.Factory()) }
            .build()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Success header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.conversion_complete),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Animated WebP preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(state.outputPath))
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = "Converted WebP preview",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }

            Spacer(Modifier.height(12.dp))

            // File info badge
            Text(
                text = stringResource(R.string.output_size, "${sizeMb} MB", state.qualityUsed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { shareOutput(context, state.outputPath) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share))
                }

                FilledTonalButton(
                    onClick = { saveToDownloads(context, state.outputPath) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.save))
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onNewConversion,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.new_conversion))
            }
        }
    }
}

private fun shareOutput(context: Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/webp"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share WebP"))
}

private fun saveToDownloads(context: Context, path: String) {
    val file = File(path)
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/webp")
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
