package app.embeddy.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.embeddy.R
import app.embeddy.inspect.InspectState
import app.embeddy.inspect.MetadataResult
import app.embeddy.viewmodel.InspectViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun InspectScreen(
    viewModel: InspectViewModel = viewModel(),
    /** Optional URI to inspect immediately (from Convert tab info icon). */
    initialFileUri: Uri? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()

    // File picker for local file inspection
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.inspectFile(it) }
    }

    // Auto-inspect if launched with a file URI
    var hasAutoInspected by remember { mutableStateOf(false) }
    if (initialFileUri != null && !hasAutoInspected) {
        hasAutoInspected = true
        viewModel.inspectFile(initialFileUri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // URL input
        OutlinedTextField(
            value = urlInput,
            onValueChange = viewModel::updateUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.enter_url)) },
            leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { viewModel.fetchMetadata() }),
            shape = RoundedCornerShape(16.dp),
        )

        // Fetch URL button + Inspect File button side by side
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = viewModel::fetchMetadata,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = urlInput.isNotBlank() && state !is InspectState.Fetching,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fetch))
            }

            FilledTonalButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                enabled = state !is InspectState.Fetching,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.inspect_file))
            }
        }

        // State-driven content
        when (val s = state) {
            is InspectState.Idle -> {
                // Show hint
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.inspect_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is InspectState.Fetching -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.fetching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is InspectState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.failed_to_fetch),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            is InspectState.Success -> {
                MetadataResults(result = s.result)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MetadataResults(result: MetadataResult) {
    // Social preview card (Discord-style embed) — only for URL inspections
    if (result.title != null || result.imageUrl != null) {
        SocialPreviewCard(result)
    }

    // Open Graph tags
    if (result.ogTags.isNotEmpty()) {
        MetadataSection(
            title = stringResource(R.string.open_graph),
            tags = result.ogTags,
        )
    }

    // Twitter Card tags
    if (result.twitterTags.isNotEmpty()) {
        MetadataSection(
            title = stringResource(R.string.twitter_card),
            tags = result.twitterTags,
        )
    }

    // Media-specific technical info (from local file inspection)
    if (result.mediaTags.isNotEmpty()) {
        MetadataSection(
            title = stringResource(R.string.media_info),
            tags = result.mediaTags,
            expandedByDefault = true,
        )
    }

    // General meta tags
    if (result.generalTags.isNotEmpty()) {
        MetadataSection(
            title = stringResource(R.string.general_meta),
            tags = result.generalTags,
        )
    }

    // No metadata found
    if (!result.hasData) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = stringResource(R.string.no_metadata),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Discord/Slack-style social embed preview. */
@Composable
private fun SocialPreviewCard(result: MetadataResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column {
            // Accent bar + content
            Row(modifier = Modifier.fillMaxWidth()) {
                // Left accent bar (Discord embed style)
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .heightIn(min = 60.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(12.dp),
                ) {
                    result.siteName?.let { siteName ->
                        Text(
                            text = siteName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    result.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    result.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                        )
                    }
                }
            }

            // OG image preview
            result.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Social preview image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** Expandable section showing key-value metadata tags. */
@Composable
private fun MetadataSection(
    title: String,
    tags: Map<String, String>,
    expandedByDefault: Boolean = true,
) {
    var expanded by remember { mutableStateOf(expandedByDefault) }
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "$title (${tags.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3,
                                )
                            }
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(value)) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_tag),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
