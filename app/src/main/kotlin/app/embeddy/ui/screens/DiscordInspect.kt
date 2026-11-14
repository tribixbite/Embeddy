package app.embeddy.ui.screens

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embeddy.R
import app.embeddy.inspect.discord.CheckKind
import app.embeddy.inspect.discord.CheckStatus
import app.embeddy.inspect.discord.ComponentEmbedReport
import app.embeddy.inspect.discord.ComponentEmbedRules
import app.embeddy.inspect.discord.ComponentType
import app.embeddy.inspect.discord.DiscordCheck
import app.embeddy.inspect.discord.DiscordReport
import app.embeddy.inspect.discord.EmbedIssueKind
import app.embeddy.inspect.discord.IssueSeverity
import app.embeddy.inspect.discord.Json
import app.embeddy.inspect.discord.MdBlock
import app.embeddy.inspect.discord.MdBlockKind
import app.embeddy.inspect.discord.MdSpan
import app.embeddy.inspect.discord.MdTimestamp
import app.embeddy.inspect.discord.MediaKind
import app.embeddy.inspect.discord.PreviewLayout
import app.embeddy.inspect.discord.StandardPreview
import app.embeddy.inspect.discord.arrOrNull
import app.embeddy.inspect.discord.mediaKind
import app.embeddy.inspect.discord.objOrNull
import app.embeddy.inspect.discord.parseDiscordMarkdown
import app.embeddy.inspect.discord.stringOrNull
import coil.compose.AsyncImage
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date

/** Discord's dark-theme surfaces, so the preview reads like the real client */
private object DiscordColors {
    val message = Color(0xFF313338)
    val embed = Color(0xFF2B2D31)
    val defaultAccent = Color(0xFF1E1F22)
    val text = Color(0xFFDBDEE1)
    val heading = Color(0xFFF2F3F5)
    val muted = Color(0xFF949BA4)
    val link = Color(0xFF00A8FC)
    val button = Color(0xFF4E5058)
    val code = Color(0xFF1E1F22)
    val spoiler = Color(0xFF1E1F22)
    val pass = Color(0xFF23A55A)
    val warn = Color(0xFFF0B232)
    val fail = Color(0xFFF23F43)
}

/**
 * Discord section of a URL inspection: the preview Discord would draw (the
 * component embed when the page has a usable one, with the Open Graph fallback
 * on demand), component-embed meters and payload problems, then every check.
 */
@Composable
fun DiscordSection(report: DiscordReport) {
    var showFallback by rememberSaveable(report) { mutableStateOf(false) }
    val active = report.activeComponentEmbed
    val activeRoot = active?.validation?.root

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.discord_preview),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (activeRoot != null) stringResource(R.string.discord_component_embed) else stringResource(report.preview.layout.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DiscordColors.message)
                    .padding(10.dp),
            ) {
                if (activeRoot != null) ComponentEmbedView(activeRoot) else StandardEmbedView(report.preview)
            }

            if (activeRoot != null) {
                TextButton(onClick = { showFallback = !showFallback }) {
                    Text(stringResource(if (showFallback) R.string.discord_hide_fallback else R.string.discord_show_fallback))
                }
                if (showFallback) {
                    Text(
                        text = "${stringResource(R.string.discord_fallback_note)} — ${stringResource(report.preview.layout.labelRes())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DiscordColors.message)
                            .padding(10.dp),
                    ) { StandardEmbedView(report.preview) }
                }
            }

            report.componentEmbeds.forEach { ComponentEmbedStatus(it) }

            Text(
                text = stringResource(R.string.discord_reading_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    DiscordChecksCard(report.checks)
}

// ── Component embed rendering ───────────────────────────────────────────────

/** A Components V2 Container as Discord draws it in a link preview. */
@Composable
private fun ComponentEmbedView(root: Json.Obj) {
    val accent = (root["accent_color"] as? Json.Num)?.asLong?.let { Color(0xFF000000 or it) } ?: DiscordColors.defaultAccent
    EmbedFrame(accent) {
        root["components"].arrOrNull?.items.orEmpty().forEach { child ->
            val node = child.objOrNull ?: return@forEach
            when ((node["type"] as? Json.Num)?.asLong?.toInt()) {
                ComponentType.TEXT_DISPLAY -> MarkdownText(node["content"].stringOrNull.orEmpty())
                ComponentType.SECTION -> SectionView(node)
                ComponentType.MEDIA_GALLERY -> GalleryView(node["items"].arrOrNull?.items.orEmpty().mapNotNull { it.objOrNull })
                ComponentType.SEPARATOR -> SeparatorView(node)
                ComponentType.ACTION_ROW -> ButtonRow(node["components"].arrOrNull?.items.orEmpty().mapNotNull { it.objOrNull })
            }
        }
    }
}

/** Embed body with the coloured strip on the left */
@Composable
private fun EmbedFrame(accent: Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(DiscordColors.embed),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun SectionView(node: Json.Obj) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node["components"].arrOrNull?.items.orEmpty().forEach { MarkdownText(it.objOrNull?.get("content").stringOrNull.orEmpty()) }
        }
        val accessory = node["accessory"].objOrNull ?: return@Row
        val altText = accessory["description"].stringOrNull ?: stringResource(R.string.discord_thumbnail_alt)
        when ((accessory["type"] as? Json.Num)?.asLong?.toInt()) {
            ComponentType.THUMBNAIL -> MediaTile(
                url = accessory["media"].objOrNull?.get("url").stringOrNull.orEmpty(),
                spoiler = (accessory["spoiler"] as? Json.Bool)?.value == true,
                contentDescription = altText,
                modifier = Modifier.size(72.dp),
            )
            ComponentType.BUTTON -> LinkButton(accessory)
        }
    }
}

@Composable
private fun SeparatorView(node: Json.Obj) {
    val large = (node["spacing"] as? Json.Num)?.asLong == 2L
    val divider = (node["divider"] as? Json.Bool)?.value != false
    Column {
        Spacer(Modifier.height(if (large) 8.dp else 2.dp))
        if (divider) HorizontalDivider(color = DiscordColors.muted.copy(alpha = 0.25f))
        Spacer(Modifier.height(if (large) 8.dp else 2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(buttons: List<Json.Obj>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { LinkButton(it) }
    }
}

/** A link button; component embeds are display-only, so it just opens the URL */
@Composable
private fun LinkButton(node: Json.Obj) {
    val uriHandler = LocalUriHandler.current
    val url = node["url"].stringOrNull
    val disabled = (node["disabled"] as? Json.Bool)?.value == true
    val label = node["label"].stringOrNull
    val emoji = node["emoji"].objOrNull?.get("name").stringOrNull
    Row(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DiscordColors.button.copy(alpha = if (disabled) 0.5f else 1f))
            .clickable(enabled = !disabled && url != null && url.startsWith("http")) { url?.let(uriHandler::openUri) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val alpha = if (disabled) 0.5f else 1f
        emoji?.let { Text(it, fontSize = 16.sp) }
        label?.let { Text(it, color = Color.White.copy(alpha = alpha), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = Color.White.copy(alpha = 0.7f * alpha),
        )
    }
}

/**
 * Discord's gallery mosaic: rows of 2 or 3, with an odd leading item on its
 * own row (1 → 1, 3 → 1+2, 5 → 2+3, 7 → 1+3+3, 10 → 1+3+3+3).
 */
internal fun galleryRows(count: Int): List<Int> = when (count.coerceIn(0, 10)) {
    0 -> emptyList()
    1 -> listOf(1)
    2 -> listOf(2)
    3 -> listOf(1, 2)
    4 -> listOf(2, 2)
    5 -> listOf(2, 3)
    6 -> listOf(3, 3)
    7 -> listOf(1, 3, 3)
    8 -> listOf(2, 3, 3)
    9 -> listOf(3, 3, 3)
    else -> listOf(1, 3, 3, 3)
}

@Composable
private fun GalleryView(items: List<Json.Obj>) {
    var index = 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
        for (perRow in galleryRows(items.size)) {
            val row = items.subList(index, index + perRow)
            index += perRow
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { item ->
                    val altText = item["description"].stringOrNull ?: stringResource(R.string.discord_media_alt)
                    MediaTile(
                        url = item["media"].objOrNull?.get("url").stringOrNull.orEmpty(),
                        spoiler = (item["spoiler"] as? Json.Bool)?.value == true,
                        contentDescription = altText,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(if (perRow == 1) 16f / 9f else 1f),
                    )
                }
            }
        }
    }
}

/**
 * One image or video tile. Videos show their first frame with a play badge
 * and open in the browser; spoilered media is hidden until tapped.
 */
@Composable
private fun MediaTile(url: String, spoiler: Boolean, contentDescription: String, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    var revealed by remember(url) { mutableStateOf(!spoiler) }
    val isVideo = mediaKind(url) == MediaKind.VIDEO
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DiscordColors.code)
            .clickable(enabled = url.startsWith("http")) {
                if (!revealed) revealed = true else if (isVideo) uriHandler.openUri(url)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (revealed) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            if (isVideo) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.discord_play_video), tint = Color.White)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.discord_spoiler).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ── Standard (Open Graph) embed ─────────────────────────────────────────────

@Composable
private fun StandardEmbedView(preview: StandardPreview) {
    if (preview.layout == PreviewLayout.NONE) {
        Text(stringResource(R.string.discord_layout_none), color = DiscordColors.muted, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val uriHandler = LocalUriHandler.current
    val accent = preview.color?.let { Color(0xFF000000 or it.toLong()) } ?: DiscordColors.defaultAccent
    val showText = preview.layout != PreviewLayout.PHOTO
    EmbedFrame(accent) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.siteName?.takeIf { showText }?.let {
                    Text(it, color = DiscordColors.muted, style = MaterialTheme.typography.labelSmall)
                }
                preview.authorName?.takeIf { showText }?.let {
                    Text(it, color = DiscordColors.heading, style = MaterialTheme.typography.labelLarge)
                }
                preview.title?.takeIf { showText }?.let {
                    Text(
                        text = it.text,
                        color = DiscordColors.link,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { uriHandler.openUri(preview.url) },
                    )
                }
                preview.description?.takeIf { showText }?.let {
                    if (preview.markdown) MarkdownText(it.text) else Text(it.text, color = DiscordColors.text, style = MaterialTheme.typography.bodySmall)
                }
            }
            // The small-image layout puts the image beside the text
            if (preview.layout == PreviewLayout.THUMBNAIL) {
                preview.images.firstOrNull()?.let {
                    MediaTile(it.url, spoiler = false, contentDescription = it.alt ?: stringResource(R.string.discord_thumbnail_alt), modifier = Modifier.size(80.dp))
                }
            }
        }
        when (preview.layout) {
            PreviewLayout.GALLERY -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                preview.images.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach {
                            MediaTile(it.url, false, it.alt ?: stringResource(R.string.discord_media_alt), Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
            PreviewLayout.LARGE_IMAGE, PreviewLayout.PHOTO, PreviewLayout.RICH -> preview.images.firstOrNull()?.let {
                val ratio = if (it.width != null && it.height != null) it.width.toFloat() / it.height else 16f / 9f
                MediaTile(it.url, false, it.alt ?: stringResource(R.string.discord_media_alt), Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f)))
            }
            PreviewLayout.VIDEO -> preview.video?.let { v ->
                val ratio = if (v.width != null && v.height != null) v.width.toFloat() / v.height else 16f / 9f
                MediaTile(v.url, false, stringResource(R.string.discord_play_video), Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f)))
            }
            else -> Unit
        }
        preview.timestamp?.let { iso ->
            // Shown in the viewer's locale, as Discord does; isRfc3339 already validated it
            val text = runCatching {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date.from(OffsetDateTime.parse(iso.replace(' ', 'T')).toInstant()))
            }.getOrDefault(iso)
            Text(text, color = DiscordColors.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Markdown ────────────────────────────────────────────────────────────────

/** Render Discord markdown blocks */
@Composable
private fun MarkdownText(source: String) {
    val blocks = remember(source) { parseDiscordMarkdown(source) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block -> MarkdownBlock(block) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    val text = markdownString(block.spans)
    val (size: TextUnit, weight: FontWeight, color: Color) = when (block.kind) {
        MdBlockKind.H1 -> Triple(22.sp, FontWeight.Bold, DiscordColors.heading)
        MdBlockKind.H2 -> Triple(19.sp, FontWeight.Bold, DiscordColors.heading)
        MdBlockKind.H3 -> Triple(16.sp, FontWeight.Bold, DiscordColors.heading)
        MdBlockKind.SUBTEXT -> Triple(12.sp, FontWeight.Normal, DiscordColors.muted)
        else -> Triple(14.sp, FontWeight.Normal, DiscordColors.text)
    }
    // Without an explicit line height, Text inherits the theme's bodyLarge 24sp,
    // which double-spaces the 12–14sp sizes used here.
    val lineHeight = size * 1.375f
    when (block.kind) {
        MdBlockKind.QUOTE -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(DiscordColors.muted.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, fontSize = size, lineHeight = lineHeight)
        }
        MdBlockKind.LIST_ITEM -> Row(Modifier.padding(start = (8 + 16 * block.indent).dp)) {
            Text("${block.marker} ", color = color, fontSize = size, lineHeight = lineHeight)
            Text(text, color = color, fontSize = size, lineHeight = lineHeight)
        }
        MdBlockKind.CODE_BLOCK -> Text(
            text = text,
            color = DiscordColors.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(DiscordColors.code)
                .padding(8.dp),
        )
        else -> Text(text, color = color, fontSize = size, fontWeight = weight, lineHeight = lineHeight)
    }
}

/** Spans → AnnotatedString; timestamps are formatted for the viewer's locale and zone */
@Composable
private fun markdownString(spans: List<MdSpan>): AnnotatedString {
    val context = LocalContext.current
    return buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = when {
                    span.underline && span.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    span.underline -> TextDecoration.Underline
                    span.strike -> TextDecoration.LineThrough
                    else -> null
                },
                fontFamily = if (span.code) FontFamily.Monospace else null,
                color = when {
                    span.spoiler -> DiscordColors.spoiler
                    span.color != null -> Color(0xFF000000 or span.color.toLong())
                    span.link != null -> DiscordColors.link
                    else -> Color.Unspecified
                },
                background = when {
                    span.spoiler -> DiscordColors.spoiler
                    span.background != null -> Color(0xFF000000 or span.background.toLong())
                    span.code && span.color == null -> DiscordColors.code
                    span.timestamp != null -> DiscordColors.code
                    else -> Color.Unspecified
                },
            )
            val text = span.timestamp?.let { formatTimestamp(context, it) } ?: span.text
            if (span.link != null) {
                withLink(LinkAnnotation.Url(span.link, TextLinkStyles(style))) { append(text) }
            } else {
                withStyle(style) { append(text) }
            }
        }
    }
}

/** Discord `<t:unix:style>` formatting with the platform's localized formats */
private fun formatTimestamp(context: android.content.Context, ts: MdTimestamp): String {
    val ms = ts.epochSeconds * 1000
    val date = Date(ms)
    return when (ts.style) {
        'R' -> DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        't' -> android.text.format.DateFormat.getTimeFormat(context).format(date)
        'T' -> DateFormat.getTimeInstance(DateFormat.MEDIUM).format(date)
        'd' -> android.text.format.DateFormat.getDateFormat(context).format(date)
        'D' -> DateFormat.getDateInstance(DateFormat.LONG).format(date)
        'F' -> DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(date)
        else -> DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(date)
    }
}

// ── Status and checks ───────────────────────────────────────────────────────

/** Meters and payload problems for one component-embed element */
@Composable
private fun ComponentEmbedStatus(report: ComponentEmbedReport) {
    val v = report.validation ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Meter(stringResource(R.string.discord_meter_bytes, v.bytes.toString(), ComponentEmbedRules.MAX_BYTES.toString()), v.bytes, ComponentEmbedRules.MAX_BYTES)
        Meter(stringResource(R.string.discord_meter_components, v.componentCount.toString(), ComponentEmbedRules.MAX_COMPONENTS.toString()), v.componentCount, ComponentEmbedRules.MAX_COMPONENTS)
        Meter(stringResource(R.string.discord_meter_media, v.galleryItemCount.toString(), ComponentEmbedRules.MAX_GALLERY_ITEMS.toString()), v.galleryItemCount, ComponentEmbedRules.MAX_GALLERY_ITEMS)
        if (v.issues.isNotEmpty()) {
            Text(stringResource(R.string.discord_issues_title), style = MaterialTheme.typography.labelLarge)
            v.issues.forEach { issue ->
                val error = issue.severity == IssueSeverity.ERROR
                StatusLine(
                    icon = if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
                    tint = if (error) DiscordColors.fail else DiscordColors.warn,
                    label = stringResource(if (error) R.string.discord_issue_error else R.string.discord_issue_warning),
                    text = buildString {
                        if (issue.path.isNotEmpty()) append(issue.path).append(": ")
                        append(stringResource(issue.kind.res(), *issue.args.toTypedArray()))
                    },
                )
            }
        }
    }
}

@Composable
private fun Meter(label: String, value: Int, max: Int) {
    val over = value > max
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (over) DiscordColors.fail else MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(
            progress = { (value.toFloat() / max).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (over) DiscordColors.fail else MaterialTheme.colorScheme.primary,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun DiscordChecksCard(checks: List<DiscordCheck>) {
    if (checks.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.discord_checks), style = MaterialTheme.typography.titleSmall)
            // Failures first, then warnings, passes and notes
            checks.sortedBy { it.status.ordinalForDisplay() }.forEach { check ->
                val (icon, tint, label) = when (check.status) {
                    CheckStatus.FAIL -> Triple(Icons.Outlined.ErrorOutline, DiscordColors.fail, R.string.discord_status_fail)
                    CheckStatus.WARN -> Triple(Icons.Outlined.WarningAmber, DiscordColors.warn, R.string.discord_status_warn)
                    CheckStatus.PASS -> Triple(Icons.Outlined.CheckCircle, DiscordColors.pass, R.string.discord_status_pass)
                    CheckStatus.INFO -> Triple(Icons.Outlined.Info, DiscordColors.muted, R.string.discord_status_info)
                }
                StatusLine(icon, tint, stringResource(label), stringResource(check.kind.res(), *check.args.toTypedArray()))
            }
        }
    }
}

private fun CheckStatus.ordinalForDisplay() = when (this) {
    CheckStatus.FAIL -> 0
    CheckStatus.WARN -> 1
    CheckStatus.PASS -> 2
    CheckStatus.INFO -> 3
}

/** One status row; the icon is decorative and the status word is read out instead */
@Composable
private fun StatusLine(icon: ImageVector, tint: Color, label: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = tint)) { append(label) }
                append("  ")
                append(text)
            },
            style = MaterialTheme.typography.bodySmall,
            overflow = TextOverflow.Ellipsis,
            maxLines = 6,
        )
    }
}

// ── Resource mapping ────────────────────────────────────────────────────────

@StringRes
private fun PreviewLayout.labelRes(): Int = when (this) {
    PreviewLayout.NONE -> R.string.discord_layout_none
    PreviewLayout.TEXT -> R.string.discord_layout_text
    PreviewLayout.THUMBNAIL -> R.string.discord_layout_thumbnail
    PreviewLayout.LARGE_IMAGE -> R.string.discord_layout_large_image
    PreviewLayout.PHOTO -> R.string.discord_layout_photo
    PreviewLayout.VIDEO -> R.string.discord_layout_video
    PreviewLayout.GALLERY -> R.string.discord_layout_gallery
    PreviewLayout.RICH -> R.string.discord_layout_rich
}

@StringRes
private fun CheckKind.res(): Int = when (this) {
    CheckKind.FETCHED_AS_DISCORDBOT -> R.string.discord_check_fetched_as_discordbot
    CheckKind.OEMBED_UNREADABLE -> R.string.discord_check_oembed_unreadable
    CheckKind.OEMBED_NOT_OBJECT -> R.string.discord_check_oembed_not_object
    CheckKind.OEMBED_READ -> R.string.discord_check_oembed_read
    CheckKind.OEMBED_IGNORED_FIELDS -> R.string.discord_check_oembed_ignored_fields
    CheckKind.OEMBED_MULTIPLE -> R.string.discord_check_oembed_multiple
    CheckKind.TITLE_DESCRIPTION_PAIR_USED -> R.string.discord_check_pair_used
    CheckKind.TITLE_DESCRIPTION_PAIR_INCOMPLETE -> R.string.discord_check_pair_incomplete
    CheckKind.TITLE -> R.string.discord_check_title
    CheckKind.TITLE_TRIMMED -> R.string.discord_check_title_trimmed
    CheckKind.NO_TITLE -> R.string.discord_check_no_title
    CheckKind.DESCRIPTION -> R.string.discord_check_description
    CheckKind.DESCRIPTION_TRIMMED -> R.string.discord_check_description_trimmed
    CheckKind.DESCRIPTION_MARKDOWN -> R.string.discord_check_description_markdown
    CheckKind.NO_DESCRIPTION -> R.string.discord_check_no_description
    CheckKind.SITE_NAME_TOO_LONG -> R.string.discord_check_site_name_too_long
    CheckKind.OEMBED_PROVIDER_OVERRIDES -> R.string.discord_check_oembed_provider
    CheckKind.OEMBED_AUTHOR -> R.string.discord_check_oembed_author
    CheckKind.OG_TYPE_NO_EFFECT -> R.string.discord_check_og_type_no_effect
    CheckKind.TWITTER_CARD_NO_EFFECT -> R.string.discord_check_twitter_card_no_effect
    CheckKind.ACCENT_COLOR -> R.string.discord_check_accent_color
    CheckKind.THEME_COLOR_IGNORED -> R.string.discord_check_theme_color_ignored
    CheckKind.TIMESTAMP -> R.string.discord_check_timestamp
    CheckKind.DATE_IGNORED -> R.string.discord_check_date_ignored
    CheckKind.PUBLISHED_TIME_NOT_READ -> R.string.discord_check_published_time
    CheckKind.OEMBED_THUMBNAIL_IGNORED -> R.string.discord_check_oembed_thumbnail_ignored
    CheckKind.RELATIVE_IMAGE -> R.string.discord_check_relative_image
    CheckKind.ORPHAN_IMAGE_META -> R.string.discord_check_orphan_image_meta
    CheckKind.VARIANT_IMAGE_META -> R.string.discord_check_variant_image_meta
    CheckKind.IMAGE_URL_TOO_LONG -> R.string.discord_check_image_url_too_long
    CheckKind.IMAGE_ALT_TOO_LONG -> R.string.discord_check_image_alt_too_long
    CheckKind.IMAGE_TYPE_UNSUPPORTED -> R.string.discord_check_image_type_unsupported
    CheckKind.IMAGE_EXTENSION_UNSUPPORTED -> R.string.discord_check_image_extension
    CheckKind.IMAGE_NO_SIZE -> R.string.discord_check_image_no_size
    CheckKind.TOO_MANY_OG_IMAGES -> R.string.discord_check_too_many_og_images
    CheckKind.RELATIVE_VIDEO -> R.string.discord_check_relative_video
    CheckKind.TWITTER_PLAYER_PAGE -> R.string.discord_check_twitter_player_page
    CheckKind.VIDEO_PLAYER_PAGE -> R.string.discord_check_video_player_page
    CheckKind.VIDEO_OK -> R.string.discord_check_video_ok
    CheckKind.VIDEO_NEEDS_TYPE -> R.string.discord_check_video_needs_type
    CheckKind.VIDEO_NEEDS_HTTPS -> R.string.discord_check_video_needs_https
    CheckKind.VIDEO_NEEDS_IMAGE -> R.string.discord_check_video_needs_image
    CheckKind.VIDEO_TYPE_UNSUPPORTED -> R.string.discord_check_video_type_unsupported
    CheckKind.VIDEO_EXTENSION_UNSUPPORTED -> R.string.discord_check_video_extension
    CheckKind.VIDEO_URL_TOO_LONG -> R.string.discord_check_video_url_too_long
    CheckKind.VIDEO_LAYOUT_NO_FILE -> R.string.discord_check_video_layout_no_file
    CheckKind.OEMBED_URL_TOO_LONG -> R.string.discord_check_oembed_url_too_long
    CheckKind.NO_PREVIEW_BLOCKED -> R.string.discord_check_no_preview_blocked
    CheckKind.NOTHING_TO_PREVIEW -> R.string.discord_check_nothing_to_preview
    CheckKind.ELEMENT_BAD_TYPE -> R.string.discord_check_element_bad_type
    CheckKind.ELEMENT_NOT_IN_HEAD -> R.string.discord_check_element_not_in_head
    CheckKind.ELEMENT_NO_HREF -> R.string.discord_check_element_no_href
    CheckKind.ELEMENT_HOST_NOT_ALLOWED -> R.string.discord_check_element_host
    CheckKind.ELEMENT_FETCH_FAILED -> R.string.discord_check_element_fetch_failed
    CheckKind.ELEMENT_TOO_LARGE -> R.string.discord_check_element_too_large
    CheckKind.COMPONENT_VALID -> R.string.discord_check_component_valid
    CheckKind.COMPONENT_WARNINGS -> R.string.discord_check_component_warnings
    CheckKind.COMPONENT_INVALID -> R.string.discord_check_component_invalid
    CheckKind.COMPONENT_MULTIPLE -> R.string.discord_check_component_multiple
    CheckKind.OG_FALLBACK_PRESENT -> R.string.discord_check_og_fallback_present
    CheckKind.OG_FALLBACK_INCOMPLETE -> R.string.discord_check_og_fallback_incomplete
}

@StringRes
private fun EmbedIssueKind.res(): Int = when (this) {
    EmbedIssueKind.PAYLOAD_TOO_LARGE -> R.string.discord_issue_payload_too_large
    EmbedIssueKind.NOT_JSON -> R.string.discord_issue_not_json
    EmbedIssueKind.NOT_OBJECT -> R.string.discord_issue_not_object
    EmbedIssueKind.UNKNOWN_TOP_LEVEL_KEY -> R.string.discord_issue_unknown_key
    EmbedIssueKind.MISSING_COMPONENT -> R.string.discord_issue_missing_component
    EmbedIssueKind.ROOT_NOT_CONTAINER -> R.string.discord_issue_root_not_container
    EmbedIssueKind.BAD_ACCENT_COLOR -> R.string.discord_issue_bad_accent
    EmbedIssueKind.NO_COMPONENTS_ARRAY -> R.string.discord_issue_no_components_array
    EmbedIssueKind.EMPTY_CONTAINER -> R.string.discord_issue_empty_container
    EmbedIssueKind.TOO_MANY_COMPONENTS -> R.string.discord_issue_too_many_components
    EmbedIssueKind.TOO_MANY_GALLERY_ITEMS -> R.string.discord_issue_too_many_gallery_items
    EmbedIssueKind.CHILD_NOT_OBJECT -> R.string.discord_issue_child_not_object
    EmbedIssueKind.NESTED_CONTAINER -> R.string.discord_issue_nested_container
    EmbedIssueKind.FILE_UNSUPPORTED -> R.string.discord_issue_file_unsupported
    EmbedIssueKind.BUTTON_OUTSIDE_ROW -> R.string.discord_issue_button_outside_row
    EmbedIssueKind.THUMBNAIL_OUTSIDE_SECTION -> R.string.discord_issue_thumbnail_outside_section
    EmbedIssueKind.UNSUPPORTED_TYPE -> R.string.discord_issue_unsupported_type
    EmbedIssueKind.BAD_ID -> R.string.discord_issue_bad_id
    EmbedIssueKind.BAD_BOOLEAN -> R.string.discord_issue_bad_boolean
    EmbedIssueKind.BAD_DESCRIPTION -> R.string.discord_issue_bad_description
    EmbedIssueKind.ALT_TOO_LONG -> R.string.discord_issue_alt_too_long
    EmbedIssueKind.MEDIA_MISSING -> R.string.discord_issue_media_missing
    EmbedIssueKind.MEDIA_ATTACHMENT -> R.string.discord_issue_media_attachment
    EmbedIssueKind.MEDIA_NOT_HTTP -> R.string.discord_issue_media_not_http
    EmbedIssueKind.MEDIA_URL_TOO_LONG -> R.string.discord_issue_media_url_too_long
    EmbedIssueKind.THUMBNAIL_IS_VIDEO -> R.string.discord_issue_thumbnail_video
    EmbedIssueKind.MEDIA_UNKNOWN_EXTENSION -> R.string.discord_issue_media_extension
    EmbedIssueKind.MEDIA_EXTRA_KEY -> R.string.discord_issue_media_extra_key
    EmbedIssueKind.BUTTON_EXTRA_KEY -> R.string.discord_issue_button_extra_key
    EmbedIssueKind.BUTTON_NOT_LINK -> R.string.discord_issue_button_not_link
    EmbedIssueKind.BUTTON_NO_URL -> R.string.discord_issue_button_no_url
    EmbedIssueKind.BUTTON_URL_NOT_HTTP -> R.string.discord_issue_button_url_not_http
    EmbedIssueKind.BUTTON_URL_TOO_LONG -> R.string.discord_issue_button_url_too_long
    EmbedIssueKind.BUTTON_NO_LABEL_OR_EMOJI -> R.string.discord_issue_button_no_label
    EmbedIssueKind.LABEL_TOO_LONG -> R.string.discord_issue_label_too_long
    EmbedIssueKind.BAD_EMOJI -> R.string.discord_issue_bad_emoji
    EmbedIssueKind.TEXT_EMPTY -> R.string.discord_issue_text_empty
    EmbedIssueKind.GALLERY_ITEM_COUNT -> R.string.discord_issue_gallery_item_count
    EmbedIssueKind.GALLERY_OVER_TEN -> R.string.discord_issue_gallery_over_ten
    EmbedIssueKind.GALLERY_ITEM_NOT_OBJECT -> R.string.discord_issue_gallery_item_not_object
    EmbedIssueKind.BAD_SPACING -> R.string.discord_issue_bad_spacing
    EmbedIssueKind.SECTION_TEXT_COUNT -> R.string.discord_issue_section_text_count
    EmbedIssueKind.SECTION_CHILD_NOT_TEXT -> R.string.discord_issue_section_child_not_text
    EmbedIssueKind.SECTION_NO_ACCESSORY -> R.string.discord_issue_section_no_accessory
    EmbedIssueKind.SECTION_BAD_ACCESSORY -> R.string.discord_issue_section_bad_accessory
    EmbedIssueKind.ROW_BUTTON_COUNT -> R.string.discord_issue_row_button_count
    EmbedIssueKind.ROW_CHILD_NOT_BUTTON -> R.string.discord_issue_row_child_not_button
}
