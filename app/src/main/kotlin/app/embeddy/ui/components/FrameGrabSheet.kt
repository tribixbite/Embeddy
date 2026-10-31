package app.embeddy.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.embeddy.R
import app.embeddy.viewmodel.FrameGrabState
import coil.compose.AsyncImage
import java.io.File

/**
 * Bottom sheet for a grabbed video frame: preview plus Share, Save to
 * Downloads, and "Compress in Squoosh" (hands the PNG to the Squoosh tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameGrabSheet(
    state: FrameGrabState,
    onDismiss: () -> Unit,
    onOpenInSquoosh: (Uri) -> Unit,
) {
    if (state == FrameGrabState.Idle) return
    val context = LocalContext.current
    val save = rememberDownloadsSaver()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.grab_frame_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when (state) {
                FrameGrabState.Working -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is FrameGrabState.Failed -> Text(
                    text = stringResource(R.string.grab_frame_failed, state.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is FrameGrabState.Ready -> {
                    AsyncImage(
                        model = File(state.path),
                        contentDescription = stringResource(R.string.grab_frame_preview_desc),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { shareFile(context, state.path) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.share))
                        }
                        FilledTonalButton(onClick = { save(state.path, "image/png") }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.save))
                        }
                    }
                    OutlinedButton(
                        onClick = { onOpenInSquoosh(providerUri(context, state.path)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.grab_frame_squoosh))
                    }
                }
                FrameGrabState.Idle -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** FileProvider URI for a file under the app cache's shared roots. */
private fun providerUri(context: Context, path: String): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.provider", File(path))

private fun shareFile(context: Context, path: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, providerUri(context, path))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_output)))
}
