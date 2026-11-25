package app.embeddy.inspect.discord

import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Discord link-preview resolution: given what was fetched from a page, work
 * out the preview Discord would build and the documented rules the page
 * passes or breaks. Kotlin port of site/src/lib/inspect/discord-unfurl.ts;
 * keep the two in step.
 *
 * Source of truth: https://docs.discord.com/developers/link-previews/overview
 * and https://docs.discord.com/developers/link-previews/component-embeds.
 * Where Discord leaves precedence open (conflicting layout tags, several
 * component-embed elements) the rule used was checked in Discord's Embed
 * Debugger on 2026-11-15 and is commented where it is applied.
 *
 * Not ported from the web version: fetch-level checks (redirect count, timing,
 * Content-Disposition). The app fetches through Jsoup, which follows redirects
 * internally and doesn't report the chain or the headers per hop.
 * TODO: fetch with a client that exposes the redirect chain to port those checks.
 */
object DiscordLimits {
    const val TITLE_BYTES = 70
    const val TITLE_LINES = 3
    const val DESCRIPTION_BYTES = 350
    const val DESCRIPTION_LINES = 20
    const val SITE_NAME_CHARS = 256
    const val ALT_CHARS = 4_096
    const val URL_CHARS = 2_048
    const val IMAGES_PER_LINK = 4
}

/** Discord's crawler user agents (the page fetch, and JSON documents) */
object DiscordUserAgents {
    const val PAGE = "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"
    const val JSON = "Discordbot/2.0"
}

/** One `<meta>` read the way Discord keys it: `name` before `property`, `content` before `value` */
data class MetaTag(val key: String, val value: String, val attr: String)

enum class EmbedSourceKind { INLINE, LINKED }

/** A `discord:component-embed` element found on the page */
data class ComponentEmbedSource(
    val source: EmbedSourceKind,
    /** The element's `type` attribute */
    val type: String?,
    /** Payload text (inline script body, or the linked document), when read */
    val raw: String?,
    val bytes: Int,
    /** Inline only: whether the script sits in `<head>` */
    val inHead: Boolean? = null,
    /** Linked only */
    val href: String? = null,
    val hostAllowed: Boolean? = null,
    val error: String? = null,
    /** The linked document was larger than the read cap */
    val truncated: Boolean = false,
)

/** The first JSON oEmbed discovery link and what fetching it returned */
data class OEmbedSource(val href: String, val raw: String?, val error: String?, val count: Int)

/** Everything the analysis needs from a fetched page */
data class PageFacts(
    val url: String,
    /** Text of the first `<title>` */
    val htmlTitle: String,
    /** Meta tags in document order */
    val tags: List<MetaTag>,
    val componentEmbeds: List<ComponentEmbedSource> = emptyList(),
    val oembed: OEmbedSource? = null,
    /** Raw text of the page's `application/ld+json` scripts, in document order */
    val jsonLd: List<String> = emptyList(),
)

enum class PreviewLayout { NONE, TEXT, THUMBNAIL, LARGE_IMAGE, PHOTO, VIDEO, GALLERY, RICH }

/** A text field after Discord's byte/line trimming */
data class TrimmedText(val text: String, val full: String, val trimmed: Boolean, val bytes: Int, val lines: Int)

data class PreviewImage(
    val url: String,
    val key: String,
    val width: Int? = null,
    val height: Int? = null,
    val type: String? = null,
    val alt: String? = null,
)

data class PreviewVideo(val url: String, val key: String, val type: String?, val width: Int?, val height: Int?)

data class StandardPreview(
    val layout: PreviewLayout,
    val url: String,
    val title: TrimmedText? = null,
    val description: TrimmedText? = null,
    /** Description is Discord markdown (og:type rich) */
    val markdown: Boolean = false,
    val siteName: String? = null,
    val authorName: String? = null,
    /** Images the layout shows (≤ 4) */
    val images: List<PreviewImage> = emptyList(),
    val video: PreviewVideo? = null,
    /** Accent colour as 0xRRGGBB */
    val color: Int? = null,
    /** RFC 3339 publish date, as declared */
    val timestamp: String? = null,
)

enum class CheckStatus { PASS, WARN, FAIL, INFO }
enum class CheckGroup { TEXT, MEDIA, OEMBED, COMPONENT }

/** Every check the analysis can report; the screen maps each to a string resource with [DiscordCheck.args]. */
enum class CheckKind {
    FETCHED_AS_DISCORDBOT,
    OEMBED_UNREADABLE, // href, error
    OEMBED_NOT_OBJECT, // href
    OEMBED_READ, // href
    OEMBED_IGNORED_FIELDS, // comma-separated fields
    OEMBED_MULTIPLE, // count
    TITLE_DESCRIPTION_PAIR_USED,
    TITLE_DESCRIPTION_PAIR_INCOMPLETE,
    TITLE, // source
    TITLE_TRIMMED, // bytes, lines, source
    NO_TITLE,
    DESCRIPTION, // source
    DESCRIPTION_TRIMMED, // bytes, lines, source
    DESCRIPTION_MARKDOWN,
    NO_DESCRIPTION,
    SITE_NAME_TOO_LONG, // length
    OEMBED_PROVIDER_OVERRIDES,
    OEMBED_AUTHOR,
    OG_TYPE_NO_EFFECT, // value
    TWITTER_CARD_NO_EFFECT, // value
    LAYOUT_TAGS_CONFLICT, // count, winning key=value
    ACCENT_COLOR, // value
    THEME_COLOR_IGNORED, // value
    TIMESTAMP, // source key
    DATE_IGNORED, // key, value
    PUBLISHED_TIME_NOT_READ,
    OEMBED_THUMBNAIL_IGNORED,
    JSONLD_INVALID,
    JSONLD_NOT_TOP_LEVEL,
    JSONLD_CONTEXT_IGNORED, // @context as written, or empty when missing
    JSONLD_VIDEO_OBJECT,
    RELATIVE_IMAGE, // key: value
    ORPHAN_IMAGE_META,
    VARIANT_IMAGE_META,
    IMAGE_URL_TOO_LONG, // url label
    IMAGE_ALT_TOO_LONG, // length
    IMAGE_TYPE_UNSUPPORTED, // type
    IMAGE_EXTENSION_UNSUPPORTED, // ext, url label
    IMAGE_NO_SIZE, // url label
    TOO_MANY_OG_IMAGES, // count
    RELATIVE_VIDEO, // value
    TWITTER_PLAYER_PAGE,
    VIDEO_PLAYER_PAGE, // type
    VIDEO_OK, // url
    VIDEO_NEEDS_TYPE,
    VIDEO_LAYOUT_OVERRIDDEN, // last layout key=value
    VIDEO_NEEDS_HTTPS,
    VIDEO_NEEDS_IMAGE,
    VIDEO_TYPE_UNSUPPORTED, // type
    VIDEO_EXTENSION_UNSUPPORTED, // ext
    VIDEO_URL_TOO_LONG,
    VIDEO_LAYOUT_NO_FILE,
    OEMBED_URL_TOO_LONG, // field
    NO_PREVIEW_BLOCKED,
    NOTHING_TO_PREVIEW,
    ELEMENT_BAD_TYPE, // where, type
    ELEMENT_NOT_IN_HEAD,
    ELEMENT_NO_HREF,
    ELEMENT_HOST_NOT_ALLOWED, // href
    ELEMENT_FETCH_FAILED, // href, error
    ELEMENT_TOO_LARGE, // where
    COMPONENT_VALID, // where, bytes, components, gallery items
    COMPONENT_WARNINGS, // where, warnings, bytes, components, gallery items
    COMPONENT_INVALID, // where, errors, bytes, components, gallery items
    COMPONENT_MULTIPLE, // count
    COMPONENT_IGNORED, // where, chosen where
    OG_FALLBACK_PRESENT,
    OG_FALLBACK_INCOMPLETE,
}

data class DiscordCheck(val group: CheckGroup, val status: CheckStatus, val kind: CheckKind, val args: List<String> = emptyList())

data class ComponentEmbedReport(
    val element: ComponentEmbedSource,
    val validation: ComponentEmbedValidation?,
    /** Element-level problems (type attribute, host, fetch, size) and notes (placement) as checks */
    val elementProblems: List<DiscordCheck>,
    /** True when Discord should render this payload */
    val usable: Boolean,
) {
    /** Only elements with an accepted type attribute are candidates; Discord passes over the rest */
    val hasEmbedType: Boolean get() = element.type?.trim()?.lowercase() in ComponentEmbedRules.MIME_TYPES

    /** "inline" or the linked href — an argument for check strings */
    val where: String get() = if (element.source == EmbedSourceKind.INLINE) "<script>" else element.href.orEmpty()
}

data class DiscordReport(
    val preview: StandardPreview,
    val componentEmbeds: List<ComponentEmbedReport>,
    /** The one element Discord reads, valid or not */
    val chosenComponentEmbed: ComponentEmbedReport? = null,
    /** The component embed Discord should render instead of [preview], if any */
    val activeComponentEmbed: ComponentEmbedReport?,
    val checks: List<DiscordCheck>,
)

// ── Helpers ─────────────────────────────────────────────────────────────────

/**
 * Trim to Discord's byte and line limits without splitting a code point; a
 * trimmed value gets an ellipsis (3 bytes reserved for it).
 */
fun trimForDiscord(full: String, maxBytes: Int, maxLines: Int): TrimmedText {
    val lines = full.split("\n")
    var text = if (lines.size > maxLines) lines.take(maxLines).joinToString("\n") else full
    var trimmed = lines.size > maxLines
    if (utf8Length(text) > maxBytes) {
        trimmed = true
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            val size = utf8Length(ch)
            if (used + size > maxBytes - 3) break
            out.append(ch)
            used += size
            i += Character.charCount(cp)
        }
        text = out.toString()
    }
    return TrimmedText(
        text = if (trimmed) "${text.trimEnd()}…" else text,
        full = full,
        trimmed = trimmed,
        bytes = utf8Length(full),
        lines = lines.size,
    )
}

/** Absolute http(s) URLs pass; relative URLs must start with "/" */
fun resolveDiscordUrl(value: String, pageUrl: String): String? {
    val v = value.trim()
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(v)) return v
    if (!v.startsWith("/")) return null
    return runCatching { URI(pageUrl).resolve(v).toString() }.getOrNull()
}

/** RFC 3339 with a time and an offset, as Discord requires for publish dates */
fun isRfc3339(value: String): Boolean {
    val v = value.trim()
    if (!Regex("""^\d{4}-\d{2}-\d{2}[Tt ]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$""").matches(v)) return false
    return try {
        OffsetDateTime.parse(v.replace(' ', 'T').replace('t', 'T').replace('z', 'Z'), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}

/** Discord accepts `#RRGGBB` or `#RRGGBBAA` only; returns 0xRRGGBB */
fun parseThemeColor(value: String): Int? {
    val m = Regex("^#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$").find(value.trim()) ?: return null
    return m.groupValues[1].toInt(16)
}

/**
 * Discord's rule for a linked component embed: the href must be an absolute
 * URL on the page's host, a subdomain of it, or its parent domain.
 */
fun isLinkedEmbedHostAllowed(href: String, pageUrl: String): Boolean {
    val target = runCatching { URI(href) }.getOrNull() ?: return false
    if (target.scheme?.lowercase() !in setOf("http", "https")) return false
    val a = target.host?.lowercase()?.trimEnd('.') ?: return false
    val b = runCatching { URI(pageUrl).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return false
    return a == b || a.endsWith(".$b") || b.endsWith(".$a")
}

/** Positive integer from a tag value */
private fun toDimension(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

private fun List<MetaTag>.firstNonEmpty(vararg keys: String) = firstOrNull { it.key in keys && it.value.isNotBlank() }
private fun List<MetaTag>.lastNonEmpty(vararg keys: String) = lastOrNull { it.key in keys && it.value.isNotBlank() }

/** The layout an og:type / twitter:card value asks for */
enum class LayoutSignal { RICH, VIDEO, PHOTO, LARGE_IMAGE }

private fun layoutSignalOf(tag: MetaTag): LayoutSignal? {
    val value = tag.value.trim()
    return when (tag.key) {
        "og:type" -> when {
            value == "rich" -> LayoutSignal.RICH
            value == "video" || value.startsWith("video.") -> LayoutSignal.VIDEO
            value == "instapp:photo" -> LayoutSignal.PHOTO
            else -> null
        }
        "twitter:card" -> when (value) {
            "summary_large_image" -> LayoutSignal.LARGE_IMAGE
            "photo" -> LayoutSignal.PHOTO
            "player" -> LayoutSignal.VIDEO
            else -> null
        }
        else -> null
    }
}

/**
 * Discord reads a single layout signal: the last og:type or twitter:card in
 * document order whose value changes the layout. Values that don't (summary,
 * website…) are skipped, so summary_large_image followed by summary is still a
 * large image. Checked in Discord's Embed Debugger (2026-11-15) for every
 * ordering of rich, video, photo and large-image.
 */
private fun List<MetaTag>.lastLayoutSignal(): Pair<LayoutSignal, MetaTag>? =
    asReversed().firstNotNullOfOrNull { tag -> layoutSignalOf(tag)?.let { it to tag } }

private fun label(url: String) = if (url.length > 80) url.take(77) + "…" else url

private val IMAGE_MIME_TYPES = listOf("image/png", "image/gif", "image/jpeg", "image/webp", "image/avif")
private val VIDEO_MIME_TYPES = listOf("video/mp4", "video/webm", "video/quicktime")
private val PLAIN_IMAGE_KEYS = listOf("og:image", "twitter:image")
private val VARIANT_IMAGE_KEYS = listOf("og:image:url", "og:image:secure_url", "twitter:image:src")
private val DIMENSION_KEYS = listOf("og:image:width", "og:image:height", "twitter:image:width", "twitter:image:height")
private val IMAGE_META_KEYS = listOf("og:image:type", "og:image:alt", "twitter:image:alt")

/** oEmbed fields Discord reads */
private data class OEmbedFields(
    val type: String?,
    val title: String?,
    val authorName: String?,
    val authorUrl: String?,
    val providerName: String?,
    val providerUrl: String?,
    val thumbnailUrl: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
)

private fun Json.Obj.str(key: String): String? = this[key].stringOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun Json.Obj.dim(key: String): Int? = when (val v = this[key]) {
    is Json.Num -> v.asLong?.toInt()?.takeIf { it > 0 }
    is Json.Str -> toDimension(v.value)
    else -> null
}

/**
 * The only @context Discord accepts on a VideoObject (Embed Debugger, 2026-11-25:
 * "http://schema.org", "https://schema.org/" and a missing @context were all ignored).
 */
private const val JSONLD_CONTEXT = "https://schema.org"

/** True when a JSON-LD node's @type is (or includes) VideoObject */
private fun isVideoObject(node: Json?): Boolean {
    val type = node.objOrNull?.get("@type") ?: return false
    return type.stringOrNull == "VideoObject" || type.arrOrNull?.items?.any { it.stringOrNull == "VideoObject" } == true
}

// ── Main analysis ───────────────────────────────────────────────────────────

/** Work out the Discord preview for an inspected page, and the checks behind it. */
fun analyzeUnfurl(page: PageFacts): DiscordReport {
    val checks = mutableListOf<DiscordCheck>()
    fun check(group: CheckGroup, status: CheckStatus, kind: CheckKind, vararg args: String) {
        checks += DiscordCheck(group, status, kind, args.toList())
    }
    val tags = page.tags
    val pageUrl = page.url
    /** Set when a documented rule means Discord shows no preview at all */
    var blocked = false

    check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.FETCHED_AS_DISCORDBOT)

    // ── oEmbed ──
    var oembed: OEmbedFields? = null
    page.oembed?.let { o ->
        if (o.error != null || o.raw == null) {
            check(CheckGroup.OEMBED, CheckStatus.WARN, CheckKind.OEMBED_UNREADABLE, o.href, o.error.orEmpty())
        } else {
            val doc = runCatching { parseJson(o.raw) }.getOrNull() as? Json.Obj
            if (doc == null) {
                check(CheckGroup.OEMBED, CheckStatus.WARN, CheckKind.OEMBED_NOT_OBJECT, o.href)
            } else {
                oembed = OEmbedFields(
                    type = doc.str("type"),
                    title = doc.str("title"),
                    authorName = doc.str("author_name"),
                    authorUrl = doc.str("author_url"),
                    providerName = doc.str("provider_name"),
                    providerUrl = doc.str("provider_url"),
                    thumbnailUrl = doc.str("thumbnail_url"),
                    thumbnailWidth = doc.dim("thumbnail_width"),
                    thumbnailHeight = doc.dim("thumbnail_height"),
                )
                check(CheckGroup.OEMBED, CheckStatus.PASS, CheckKind.OEMBED_READ, o.href)
                val ignored = listOf("html", "url", "width", "height", "cache_age").filter { it in doc.keys }
                if (ignored.isNotEmpty()) check(CheckGroup.OEMBED, CheckStatus.INFO, CheckKind.OEMBED_IGNORED_FIELDS, ignored.joinToString(", "))
            }
        }
        if (o.count > 1) check(CheckGroup.OEMBED, CheckStatus.INFO, CheckKind.OEMBED_MULTIPLE, o.count.toString())
    }
    val oe = oembed

    // JSON-LD VideoObject: read before the text fields because its name and description fill them.
    // Discord reads only a top-level object, so one inside an array or @graph gets a warning.
    var ldVideo: Json.Obj? = null
    for (raw in page.jsonLd) {
        val node = runCatching { parseJson(raw) }.getOrNull()
        if (node == null) {
            check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.JSONLD_INVALID)
            continue
        }
        if (isVideoObject(node)) {
            val obj = node.objOrNull ?: continue
            val context = obj["@context"]
            if (context.stringOrNull == JSONLD_CONTEXT) {
                if (ldVideo == null) ldVideo = obj
            } else {
                check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.JSONLD_CONTEXT_IGNORED, context.stringOrNull.orEmpty())
            }
        } else if (
            node.arrOrNull?.items?.any(::isVideoObject) == true ||
            node.objOrNull?.get("@graph").arrOrNull?.items?.any(::isVideoObject) == true
        ) {
            check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.JSONLD_NOT_TOP_LEVEL)
        }
    }
    val ld = ldVideo

    // ── Text fields (first non-empty wins) ──
    val ogTitle = tags.firstNonEmpty("og:title", "twitter:title")
    val ogDesc = tags.firstNonEmpty("og:description", "twitter:description")
    var titleValue = ogTitle?.value?.trim()
    var titleSource = ogTitle?.key
    var descValue = ogDesc?.value?.trim()
    var descSource = ogDesc?.key

    // JSON-LD name/description fill what Open Graph/Twitter left empty, and win over the
    // <title> + meta description pair (Embed Debugger, 2026-11-15: a page with all three
    // showed the JSON-LD text; with og:title/og:description too, Open Graph won). They fill
    // field by field (2026-11-25: a VideoObject without a name left the title empty).
    val ldName = ld?.str("name")
    val ldDesc = ld?.str("description")
    if (ogTitle == null && ldName != null) {
        titleValue = ldName
        titleSource = "JSON-LD name"
    }
    if (ogDesc == null && ldDesc != null) {
        descValue = ldDesc
        descSource = "JSON-LD description"
    }
    if (ogTitle == null && ogDesc == null && ldName == null && ldDesc == null) {
        // <title> + <meta name="description"> are used only as a pair, and only
        // when Open Graph/Twitter supply neither field
        val htmlTitle = page.htmlTitle.trim()
        val metaDesc = tags.firstOrNull { it.key == "description" && it.attr == "name" }?.value?.trim()
        if (htmlTitle.isNotEmpty() && !metaDesc.isNullOrEmpty()) {
            titleValue = htmlTitle
            titleSource = "<title>"
            descValue = metaDesc
            descSource = "<meta name=\"description\">"
            check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.TITLE_DESCRIPTION_PAIR_USED)
        } else if (htmlTitle.isNotEmpty() || !metaDesc.isNullOrEmpty()) {
            check(CheckGroup.TEXT, CheckStatus.WARN, CheckKind.TITLE_DESCRIPTION_PAIR_INCOMPLETE)
        }
    }
    if (ogTitle == null && ldName == null && oe?.title != null) {
        // oEmbed fills the title only when Open Graph/Twitter and JSON-LD gave none
        // (Embed Debugger, 2026-11-25: a JSON-LD name beat a fetched oEmbed title)
        titleValue = oe.title
        titleSource = "oEmbed title"
    }

    val ogType = tags.lastNonEmpty("og:type")?.value?.trim()
    val twitterCard = tags.lastNonEmpty("twitter:card")?.value?.trim()
    val layoutSignal = tags.lastLayoutSignal()
    val layoutTagLabel = layoutSignal?.let { (_, tag) -> "${tag.key}=\"${tag.value.trim()}\"" }
    val isRich = layoutSignal?.first == LayoutSignal.RICH

    val title = titleValue?.let { trimForDiscord(it, DiscordLimits.TITLE_BYTES, DiscordLimits.TITLE_LINES) }
    val description = descValue?.let { trimForDiscord(it, DiscordLimits.DESCRIPTION_BYTES, DiscordLimits.DESCRIPTION_LINES) }

    when {
        title == null -> check(CheckGroup.TEXT, CheckStatus.WARN, CheckKind.NO_TITLE)
        title.trimmed -> check(CheckGroup.TEXT, CheckStatus.WARN, CheckKind.TITLE_TRIMMED, title.bytes.toString(), title.lines.toString(), titleSource.orEmpty())
        else -> check(CheckGroup.TEXT, CheckStatus.PASS, CheckKind.TITLE, titleSource.orEmpty())
    }
    when {
        description == null -> check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.NO_DESCRIPTION)
        description.trimmed -> check(
            CheckGroup.TEXT, CheckStatus.WARN, CheckKind.DESCRIPTION_TRIMMED,
            description.bytes.toString(), description.lines.toString(), descSource.orEmpty(),
        )
        else -> check(CheckGroup.TEXT, CheckStatus.PASS, CheckKind.DESCRIPTION, descSource.orEmpty())
    }
    if (description != null && isRich) check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.DESCRIPTION_MARKDOWN)

    // Site name (first wins), overridden by oEmbed provider_name
    var siteName = tags.firstNonEmpty("og:site_name")?.value?.trim()
    if (siteName != null && siteName.length > DiscordLimits.SITE_NAME_CHARS) {
        check(CheckGroup.TEXT, CheckStatus.FAIL, CheckKind.SITE_NAME_TOO_LONG, siteName.length.toString())
        blocked = true
    }
    if (oe?.providerName != null) {
        siteName = oe.providerName
        check(CheckGroup.OEMBED, CheckStatus.INFO, CheckKind.OEMBED_PROVIDER_OVERRIDES)
    }
    val authorName = oe?.authorName
    if (authorName != null) check(CheckGroup.OEMBED, CheckStatus.PASS, CheckKind.OEMBED_AUTHOR)

    // Layout tags: the last one that changes the layout wins (see lastLayoutSignal)
    if (ogType != null && !(ogType == "rich" || ogType == "video" || ogType.startsWith("video.") || ogType == "instapp:photo")) {
        check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.OG_TYPE_NO_EFFECT, ogType)
    }
    if (twitterCard != null && twitterCard !in listOf("player", "summary_large_image", "photo")) {
        check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.TWITTER_CARD_NO_EFFECT, twitterCard)
    }
    val layoutTags = tags.filter { layoutSignalOf(it) != null }
    if (layoutTagLabel != null && layoutTags.map { "${it.key}=${it.value.trim()}" }.toSet().size > 1) {
        check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.LAYOUT_TAGS_CONFLICT, layoutTags.size.toString(), layoutTagLabel)
    }

    // Accent colour (last wins)
    var color: Int? = null
    tags.lastNonEmpty("theme-color")?.let { tag ->
        color = parseThemeColor(tag.value)
        if (color != null) {
            check(CheckGroup.TEXT, CheckStatus.PASS, CheckKind.ACCENT_COLOR, tag.value.trim())
        } else {
            check(CheckGroup.TEXT, CheckStatus.WARN, CheckKind.THEME_COLOR_IGNORED, tag.value)
        }
    }

    // Publish date: a JSON-LD uploadDate beats the meta tags (Embed Debugger, 2026-11-25:
    // og:pubdate alone was used, but lost to uploadDate on the same page); among the
    // tags the last one wins
    var timestamp: String? = null
    val dateTag = tags.lastNonEmpty("og:pubdate", "pubdate", "iso-8601-publish-date")
    val uploadDate = ld?.str("uploadDate")?.takeIf(::isRfc3339)
    if (uploadDate != null) {
        timestamp = uploadDate
        check(CheckGroup.TEXT, CheckStatus.PASS, CheckKind.TIMESTAMP, "JSON-LD uploadDate")
    } else if (dateTag != null) {
        if (isRfc3339(dateTag.value)) {
            timestamp = dateTag.value.trim()
            check(CheckGroup.TEXT, CheckStatus.PASS, CheckKind.TIMESTAMP, dateTag.key)
        } else {
            check(CheckGroup.TEXT, CheckStatus.WARN, CheckKind.DATE_IGNORED, dateTag.key, dateTag.value)
        }
    } else if (tags.any { it.key == "article:published_time" }) {
        check(CheckGroup.TEXT, CheckStatus.INFO, CheckKind.PUBLISHED_TIME_NOT_READ)
    }

    // ── Images ──
    val rawImages = mutableListOf<PreviewImage>()
    var lastPlainIndex = -1
    var variantDims = false
    var orphanDims = false
    var lastStartWasVariant = false
    for (tag in tags) {
        val key = tag.key
        val value = tag.value.trim()
        if (key in PLAIN_IMAGE_KEYS || key in VARIANT_IMAGE_KEYS) {
            if (value.isEmpty()) continue
            val url = resolveDiscordUrl(value, pageUrl)
            if (url == null) {
                check(CheckGroup.MEDIA, CheckStatus.FAIL, CheckKind.RELATIVE_IMAGE, "$key: $value")
                continue
            }
            rawImages += PreviewImage(url, key)
            if (key in PLAIN_IMAGE_KEYS) {
                lastPlainIndex = rawImages.lastIndex
                lastStartWasVariant = false
            } else {
                lastStartWasVariant = true
            }
            continue
        }
        val isDim = key in DIMENSION_KEYS
        if (!isDim && key !in IMAGE_META_KEYS) continue
        if (lastStartWasVariant) variantDims = true
        // Dimension/type/alt tags attach to the most recent plain og:image / twitter:image
        if (lastPlainIndex < 0) {
            orphanDims = true
            continue
        }
        val img = rawImages[lastPlainIndex]
        rawImages[lastPlainIndex] = when {
            key.endsWith(":width") -> img.copy(width = toDimension(value))
            key.endsWith(":height") -> img.copy(height = toDimension(value))
            key == "og:image:type" -> img.copy(type = value)
            else -> img.copy(alt = tag.value)
        }
    }

    // The same image declared under several keys is one image
    val byUrl = LinkedHashMap<String, PreviewImage>()
    for (img in rawImages) {
        val seen = byUrl[img.url]
        byUrl[img.url] = if (seen == null) img else seen.copy(
            width = seen.width ?: img.width,
            height = seen.height ?: img.height,
            type = seen.type ?: img.type,
            alt = seen.alt ?: img.alt,
        )
    }
    var allImages = byUrl.values.toList()

    if (ld != null) {
        check(CheckGroup.MEDIA, CheckStatus.PASS, CheckKind.JSONLD_VIDEO_OBJECT)
        val thumb = ld["thumbnailUrl"].let { it.arrOrNull?.items?.firstOrNull() ?: it }.stringOrNull
        val thumbUrl = thumb?.let { resolveDiscordUrl(it, pageUrl) }
        if (allImages.isEmpty() && thumbUrl != null) allImages = listOf(PreviewImage(thumbUrl, "JSON-LD thumbnailUrl"))
    }

    // oEmbed thumbnail fills the image only when Open Graph gave none, and only with both dimensions
    oe?.thumbnailUrl?.let { thumb ->
        if (oe.thumbnailWidth == null || oe.thumbnailHeight == null) {
            check(CheckGroup.OEMBED, CheckStatus.WARN, CheckKind.OEMBED_THUMBNAIL_IGNORED)
        } else if (allImages.isEmpty()) {
            resolveDiscordUrl(thumb, pageUrl)?.let {
                allImages = listOf(PreviewImage(it, "oEmbed thumbnail_url", oe.thumbnailWidth, oe.thumbnailHeight))
            }
        }
    }

    if (orphanDims) check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.ORPHAN_IMAGE_META)
    if (variantDims) check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.VARIANT_IMAGE_META)

    for (img in allImages) {
        if (img.url.length > DiscordLimits.URL_CHARS) {
            check(CheckGroup.MEDIA, CheckStatus.FAIL, CheckKind.IMAGE_URL_TOO_LONG, label(img.url))
            blocked = true
        }
        if (img.alt != null && img.alt.length > DiscordLimits.ALT_CHARS) {
            check(CheckGroup.MEDIA, CheckStatus.FAIL, CheckKind.IMAGE_ALT_TOO_LONG, img.alt.length.toString())
            blocked = true
        }
        val ext = urlExtension(img.url)
        if (img.type != null) {
            if (img.type.lowercase() !in IMAGE_MIME_TYPES) {
                check(CheckGroup.MEDIA, CheckStatus.FAIL, CheckKind.IMAGE_TYPE_UNSUPPORTED, img.type)
            }
        } else if (ext.isNotEmpty() && ext !in ComponentEmbedRules.IMAGE_EXTENSIONS) {
            check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.IMAGE_EXTENSION_UNSUPPORTED, ext, label(img.url))
        }
        if (img.key in PLAIN_IMAGE_KEYS && (img.width == null || img.height == null)) {
            check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.IMAGE_NO_SIZE, label(img.url))
        }
    }

    val ogImageUrls = rawImages.filter { it.key == "og:image" }.map { it.url }.distinct()
    val isGallery = ogImageUrls.size >= 2
    if (isGallery && ogImageUrls.size > DiscordLimits.IMAGES_PER_LINK) {
        check(CheckGroup.MEDIA, CheckStatus.INFO, CheckKind.TOO_MANY_OG_IMAGES, ogImageUrls.size.toString())
    }

    // ── Video ──
    val videoTag = tags.firstNonEmpty("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")
    var video: PreviewVideo? = null
    val videoUrlRaw = videoTag?.value ?: ld?.str("embedUrl")
    if (videoUrlRaw != null) {
        val url = resolveDiscordUrl(videoUrlRaw, pageUrl)
        if (url != null) {
            video = PreviewVideo(
                url = url,
                key = videoTag?.key ?: "JSON-LD embedUrl",
                type = tags.firstNonEmpty("og:video:type", "twitter:player:stream:content_type")?.value?.trim(),
                width = toDimension(tags.firstNonEmpty("og:video:width", "twitter:player:width", "og:width")?.value),
                height = toDimension(tags.firstNonEmpty("og:video:height", "twitter:player:height", "og:height")?.value),
            )
        } else {
            check(CheckGroup.MEDIA, CheckStatus.FAIL, CheckKind.RELATIVE_VIDEO, videoUrlRaw.trim())
        }
    } else if (tags.any { it.key == "twitter:player" }) {
        check(CheckGroup.MEDIA, CheckStatus.INFO, CheckKind.TWITTER_PLAYER_PAGE)
    }

    // og:type rich plays an og:video too (Embed Debugger, 2026-11-15: rich + og:video drew a
    // player); summary_large_image keeps the video in the embed but draws only the still.
    val videoSignal = layoutSignal?.first == LayoutSignal.VIDEO ||
        (ogType == null && twitterCard == null && oe?.type == "video")
    val wantsVideo = videoSignal || isRich
    var videoOk = false
    val playerPage = video?.type?.lowercase() in listOf("text/html", "application/x-shockwave-flash")
    if (video != null && playerPage) {
        check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.VIDEO_PLAYER_PAGE, video.type.orEmpty())
    } else if (video != null) {
        val problems = mutableListOf<DiscordCheck>()
        fun problem(kind: CheckKind, vararg args: String) {
            problems += DiscordCheck(CheckGroup.MEDIA, CheckStatus.FAIL, kind, args.toList())
        }
        if (!wantsVideo) {
            if (layoutTagLabel != null) problem(CheckKind.VIDEO_LAYOUT_OVERRIDDEN, layoutTagLabel) else problem(CheckKind.VIDEO_NEEDS_TYPE)
        }
        if (!video.url.startsWith("https://")) problem(CheckKind.VIDEO_NEEDS_HTTPS)
        if (allImages.isEmpty()) problem(CheckKind.VIDEO_NEEDS_IMAGE)
        val type = video.type
        if (type != null && type.lowercase() !in VIDEO_MIME_TYPES) {
            problem(CheckKind.VIDEO_TYPE_UNSUPPORTED, type)
        } else if (type == null) {
            val ext = urlExtension(video.url)
            if (ext.isNotEmpty() && ext !in ComponentEmbedRules.VIDEO_EXTENSIONS) problem(CheckKind.VIDEO_EXTENSION_UNSUPPORTED, ext)
        }
        if (video.url.length > DiscordLimits.URL_CHARS) {
            problem(CheckKind.VIDEO_URL_TOO_LONG)
            blocked = true
        }
        videoOk = problems.isEmpty()
        if (videoOk) check(CheckGroup.MEDIA, CheckStatus.PASS, CheckKind.VIDEO_OK, video.url) else checks += problems
    } else if (videoSignal) {
        check(CheckGroup.MEDIA, CheckStatus.WARN, CheckKind.VIDEO_LAYOUT_NO_FILE)
    }

    for ((field, url) in listOf("author_url" to oe?.authorUrl, "provider_url" to oe?.providerUrl)) {
        if (url != null && url.length > DiscordLimits.URL_CHARS) {
            check(CheckGroup.OEMBED, CheckStatus.FAIL, CheckKind.OEMBED_URL_TOO_LONG, field)
            blocked = true
        }
    }

    // ── Layout ──
    // One layout signal (lastLayoutSignal), checked in Discord's Embed Debugger. Two or more
    // og:image tags come back as a gallery even with a photo card, so the gallery goes first.
    // TODO: a playable video plus a second og:image comes back as a video embed followed by an
    // image embed; the Embed Debugger's preview draws only the first, so whether the client shows
    // the video, a gallery or both is unconfirmed. The video is kept here.
    val isPhoto = layoutSignal?.first == LayoutSignal.PHOTO ||
        (ogType == null && twitterCard == null && oe?.type == "photo")
    val oembedRich = ogType == null && twitterCard == null && oe?.type == "rich"
    var layout = when {
        videoOk -> PreviewLayout.VIDEO
        isGallery -> PreviewLayout.GALLERY
        isPhoto && allImages.isNotEmpty() -> PreviewLayout.PHOTO
        isRich || oembedRich -> PreviewLayout.RICH
        layoutSignal?.first == LayoutSignal.LARGE_IMAGE && allImages.isNotEmpty() -> PreviewLayout.LARGE_IMAGE
        allImages.isNotEmpty() -> PreviewLayout.THUMBNAIL
        title != null || description != null -> PreviewLayout.TEXT
        else -> PreviewLayout.NONE
    }
    if (blocked) layout = PreviewLayout.NONE

    val images = when (layout) {
        PreviewLayout.GALLERY -> ogImageUrls.take(DiscordLimits.IMAGES_PER_LINK).mapNotNull { byUrl[it] }
        PreviewLayout.NONE, PreviewLayout.TEXT -> emptyList()
        else -> allImages.take(1)
    }
    if (layout == PreviewLayout.NONE) {
        check(CheckGroup.TEXT, CheckStatus.FAIL, if (blocked) CheckKind.NO_PREVIEW_BLOCKED else CheckKind.NOTHING_TO_PREVIEW)
    }

    // ── Component embeds ──
    val ogFallback = tags.firstNonEmpty("og:title") != null && tags.firstNonEmpty("og:description") != null &&
        tags.firstNonEmpty("og:image") != null
    val componentEmbeds = page.componentEmbeds.map(::assessComponentEmbed)
    // Discord reads one element: the first inline <script> with an accepted type, else the
    // first such <link>, wherever they sit in the page. If that one fails, it shows the Open
    // Graph preview rather than trying the next (Embed Debugger, 2026-11-15).
    val chosen = componentEmbeds.firstOrNull { it.element.source == EmbedSourceKind.INLINE && it.hasEmbedType }
        ?: componentEmbeds.firstOrNull { it.element.source == EmbedSourceKind.LINKED && it.hasEmbedType }
    val active = chosen?.takeIf { it.usable }
    if (componentEmbeds.isNotEmpty()) {
        for (report in componentEmbeds) {
            if (chosen != null && report !== chosen) {
                check(CheckGroup.COMPONENT, CheckStatus.INFO, CheckKind.COMPONENT_IGNORED, report.where, chosen.where)
                continue
            }
            checks += report.elementProblems
            val v = report.validation ?: continue
            val errors = v.issues.count { it.severity == IssueSeverity.ERROR }
            val warnings = v.issues.size - errors
            val stats = arrayOf(v.bytes.toString(), v.componentCount.toString(), v.galleryItemCount.toString())
            when {
                errors > 0 -> check(CheckGroup.COMPONENT, CheckStatus.FAIL, CheckKind.COMPONENT_INVALID, report.where, errors.toString(), *stats)
                warnings > 0 -> check(CheckGroup.COMPONENT, CheckStatus.WARN, CheckKind.COMPONENT_WARNINGS, report.where, warnings.toString(), *stats)
                else -> check(CheckGroup.COMPONENT, CheckStatus.PASS, CheckKind.COMPONENT_VALID, report.where, *stats)
            }
        }
        if (componentEmbeds.size > 1) {
            check(CheckGroup.COMPONENT, CheckStatus.INFO, CheckKind.COMPONENT_MULTIPLE, componentEmbeds.size.toString())
        }
        check(
            CheckGroup.COMPONENT,
            if (ogFallback) CheckStatus.PASS else CheckStatus.WARN,
            if (ogFallback) CheckKind.OG_FALLBACK_PRESENT else CheckKind.OG_FALLBACK_INCOMPLETE,
        )
    }

    val none = layout == PreviewLayout.NONE
    return DiscordReport(
        preview = StandardPreview(
            layout = layout,
            url = pageUrl,
            title = if (none) null else title,
            description = if (none || layout == PreviewLayout.PHOTO || layout == PreviewLayout.VIDEO) null else description,
            markdown = layout == PreviewLayout.RICH,
            siteName = if (none) null else siteName,
            authorName = authorName,
            images = images,
            video = if (layout == PreviewLayout.VIDEO) video else null,
            color = color,
            timestamp = timestamp,
        ),
        componentEmbeds = componentEmbeds,
        chosenComponentEmbed = chosen,
        activeComponentEmbed = active,
        checks = checks,
    )
}

/** Element-level rules for one component-embed candidate, then payload validation */
fun assessComponentEmbed(element: ComponentEmbedSource): ComponentEmbedReport {
    val problems = mutableListOf<DiscordCheck>()
    fun problem(kind: CheckKind, vararg args: String) {
        problems += DiscordCheck(CheckGroup.COMPONENT, CheckStatus.FAIL, kind, args.toList())
    }
    val where = if (element.source == EmbedSourceKind.INLINE) "<script>" else element.href.orEmpty()
    val type = element.type?.trim()?.lowercase().orEmpty()
    if (type !in ComponentEmbedRules.MIME_TYPES) problem(CheckKind.ELEMENT_BAD_TYPE, where, element.type.orEmpty())
    // Discord's docs say <head>, but the Embed Debugger (2026-11-15) used a script in <body>, so this only warns
    val notes = mutableListOf<DiscordCheck>()
    if (element.source == EmbedSourceKind.INLINE && element.inHead == false) {
        notes += DiscordCheck(CheckGroup.COMPONENT, CheckStatus.WARN, CheckKind.ELEMENT_NOT_IN_HEAD)
    }
    if (element.source == EmbedSourceKind.LINKED) {
        if (element.href.isNullOrEmpty()) {
            problem(CheckKind.ELEMENT_NO_HREF)
        } else if (element.hostAllowed == false) {
            problem(CheckKind.ELEMENT_HOST_NOT_ALLOWED, element.href)
        }
        if (element.error != null) problem(CheckKind.ELEMENT_FETCH_FAILED, element.href.orEmpty(), element.error)
    }
    if (element.truncated) problem(CheckKind.ELEMENT_TOO_LARGE, where)
    val validation = if (element.raw != null && !element.truncated) validateComponentEmbed(element.raw) else null
    return ComponentEmbedReport(
        element = element,
        validation = validation,
        elementProblems = problems + notes,
        usable = problems.isEmpty() && validation?.ok == true,
    )
}
