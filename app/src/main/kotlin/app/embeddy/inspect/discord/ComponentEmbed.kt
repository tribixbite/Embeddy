package app.embeddy.inspect.discord

/**
 * Validation for Discord component embeds — the `discord:component-embed`
 * JSON payload a page can expose to replace its link preview with a
 * Components V2 layout. Kotlin port of the web inspector's
 * site/src/lib/inspect/component-embed.ts; keep the two in step.
 *
 * Rules come from two Discord pages and are tagged by severity:
 *   ERROR   — documented to break the embed (Discord falls back to the
 *             Open Graph preview, or shows nothing)
 *   WARNING — a limit from the general Components reference, or a likely
 *             mistake Discord doesn't document either way
 * https://docs.discord.com/developers/link-previews/component-embeds
 * https://docs.discord.com/developers/components/reference
 */
object ComponentEmbedRules {
    /** Payload size limit; escape sequences count at their full written length */
    const val MAX_BYTES = 3_000

    /** Components allowed in one embed, the parent Container included */
    const val MAX_COMPONENTS = 40

    /** Media Gallery items across the whole embed (thumbnails don't count) */
    const val MAX_GALLERY_ITEMS = 10

    const val MEDIA_URL_MAX_CHARS = 2_048
    const val BUTTON_URL_MAX_CHARS = 512
    const val LABEL_MAX_CHARS = 80
    const val ALT_MAX_CHARS = 1_024

    /** The element id / link rel Discord looks for */
    const val ELEMENT_ID = "discord:component-embed"

    /** `type` attributes Discord accepts on the script/link element */
    val MIME_TYPES = listOf("application/json", "application/vnd.discord.component-embed+json")

    val IMAGE_EXTENSIONS = listOf("png", "gif", "jpg", "jpeg", "webp", "avif")
    val VIDEO_EXTENSIONS = listOf("mp4", "webm", "mov")
}

/** Component type numbers */
object ComponentType {
    const val ACTION_ROW = 1
    const val BUTTON = 2
    const val SECTION = 9
    const val TEXT_DISPLAY = 10
    const val THUMBNAIL = 11
    const val MEDIA_GALLERY = 12
    const val FILE = 13
    const val SEPARATOR = 14
    const val CONTAINER = 17

    private val NAMES = mapOf(
        1 to "Action Row", 2 to "Button", 3 to "String Select", 4 to "Text Input", 5 to "User Select",
        6 to "Role Select", 7 to "Mentionable Select", 8 to "Channel Select", 9 to "Section",
        10 to "Text Display", 11 to "Thumbnail", 12 to "Media Gallery", 13 to "File", 14 to "Separator",
        17 to "Container", 18 to "Label", 19 to "File Upload", 21 to "Radio Group", 22 to "Checkbox Group",
        23 to "Checkbox",
    )

    /** "Section (type 9)" — component names are Discord's API identifiers, not UI copy */
    fun describe(type: Json?): String {
        val n = (type as? Json.Num)?.asLong?.toInt()
        val name = n?.let { NAMES[it] }
        return when {
            name != null -> "$name (type $n)"
            type is Json.Num -> "type ${n ?: type.value}"
            type is Json.Str -> "type \"${type.value}\""
            else -> "type ${type ?: "missing"}"
        }
    }
}

enum class IssueSeverity { ERROR, WARNING }

/**
 * Each problem the validator can report. The screen maps a kind to a string
 * resource and formats it with [EmbedIssue.args].
 */
enum class EmbedIssueKind {
    PAYLOAD_TOO_LARGE, // args: bytes
    NOT_JSON, // args: parser message
    NOT_OBJECT,
    UNKNOWN_TOP_LEVEL_KEY, // args: key
    MISSING_COMPONENT,
    ROOT_NOT_CONTAINER, // args: type
    BAD_ACCENT_COLOR,
    NO_COMPONENTS_ARRAY,
    EMPTY_CONTAINER,
    TOO_MANY_COMPONENTS, // args: count
    TOO_MANY_GALLERY_ITEMS, // args: count
    CHILD_NOT_OBJECT,
    NESTED_CONTAINER,
    FILE_UNSUPPORTED,
    BUTTON_OUTSIDE_ROW, // args: type
    THUMBNAIL_OUTSIDE_SECTION, // args: type
    UNSUPPORTED_TYPE, // args: type
    BAD_ID,
    BAD_BOOLEAN, // args: key
    BAD_DESCRIPTION,
    ALT_TOO_LONG, // args: length
    MEDIA_MISSING,
    MEDIA_ATTACHMENT,
    MEDIA_NOT_HTTP,
    MEDIA_URL_TOO_LONG, // args: length
    THUMBNAIL_IS_VIDEO,
    MEDIA_UNKNOWN_EXTENSION,
    MEDIA_EXTRA_KEY, // args: key
    BUTTON_EXTRA_KEY, // args: key
    BUTTON_NOT_LINK,
    BUTTON_NO_URL,
    BUTTON_URL_NOT_HTTP,
    BUTTON_URL_TOO_LONG, // args: length
    BUTTON_NO_LABEL_OR_EMOJI,
    LABEL_TOO_LONG, // args: length
    BAD_EMOJI,
    TEXT_EMPTY,
    GALLERY_ITEM_COUNT,
    GALLERY_OVER_TEN, // args: count
    GALLERY_ITEM_NOT_OBJECT,
    BAD_SPACING,
    SECTION_TEXT_COUNT,
    SECTION_CHILD_NOT_TEXT, // args: type
    SECTION_NO_ACCESSORY,
    SECTION_BAD_ACCESSORY, // args: type
    ROW_BUTTON_COUNT,
    ROW_CHILD_NOT_BUTTON, // args: type
}

data class EmbedIssue(
    val severity: IssueSeverity,
    /** JSON path of the offending value, e.g. `component.components[2].accessory` */
    val path: String,
    val kind: EmbedIssueKind,
    val args: List<String> = emptyList(),
)

data class ComponentEmbedValidation(
    /** True when there are no errors (warnings allowed) */
    val ok: Boolean,
    /** UTF-8 size of the payload exactly as written */
    val bytes: Int,
    val componentCount: Int,
    val galleryItemCount: Int,
    /** The root Container when the payload parsed far enough to preview */
    val root: Json.Obj?,
    val issues: List<EmbedIssue>,
)

/** UTF-8 byte length of a string */
fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/** Lower-cased extension of a URL's path, or "" */
fun urlExtension(url: String): String {
    val path = runCatching { java.net.URI(url).rawPath }.getOrNull() ?: return ""
    val dot = path.lastIndexOf('.')
    return if (dot > path.lastIndexOf('/')) path.substring(dot + 1).lowercase() else ""
}

enum class MediaKind { IMAGE, VIDEO, UNKNOWN }

fun mediaKind(url: String): MediaKind = when (urlExtension(url)) {
    in ComponentEmbedRules.IMAGE_EXTENSIONS -> MediaKind.IMAGE
    in ComponentEmbedRules.VIDEO_EXTENSIONS -> MediaKind.VIDEO
    else -> MediaKind.UNKNOWN
}

private val HTTP_URL = Regex("^https?://", RegexOption.IGNORE_CASE)

/** Validate a component-embed payload exactly as written on the page. */
fun validateComponentEmbed(raw: String): ComponentEmbedValidation {
    val walk = EmbedWalk()
    val bytes = utf8Length(raw)
    fun result(root: Json.Obj? = null) = ComponentEmbedValidation(
        ok = walk.issues.none { it.severity == IssueSeverity.ERROR },
        bytes = bytes,
        componentCount = walk.components,
        galleryItemCount = walk.galleryItems,
        root = root,
        issues = walk.issues.toList(),
    )

    if (bytes > ComponentEmbedRules.MAX_BYTES) {
        walk.error("", EmbedIssueKind.PAYLOAD_TOO_LARGE, bytes.toString())
    }
    val doc = try {
        parseJson(raw)
    } catch (e: JsonParseException) {
        walk.error("", EmbedIssueKind.NOT_JSON, e.message ?: "")
        return result()
    }
    if (doc !is Json.Obj) {
        walk.error("", EmbedIssueKind.NOT_OBJECT)
        return result()
    }
    for (key in doc.keys) {
        if (key != "component") walk.warn(key, EmbedIssueKind.UNKNOWN_TOP_LEVEL_KEY, key)
    }
    val root = doc["component"]
    if (root !is Json.Obj) {
        walk.error("component", EmbedIssueKind.MISSING_COMPONENT)
        return result()
    }
    if (root["type"].typeNumber() != ComponentType.CONTAINER) {
        walk.error("component.type", EmbedIssueKind.ROOT_NOT_CONTAINER, ComponentType.describe(root["type"]))
        return result()
    }

    walk.components++
    walk.checkId(root, "component")
    walk.checkBool(root, "spoiler", "component")
    val accent = root["accent_color"]
    if (accent != null && accent != Json.Null) {
        val n = (accent as? Json.Num)?.asLong
        if (n == null || n < 0 || n > 0xFFFFFF) walk.error("component.accent_color", EmbedIssueKind.BAD_ACCENT_COLOR)
    }
    val children = root["components"]
    if (children !is Json.Arr) {
        walk.error("component.components", EmbedIssueKind.NO_COMPONENTS_ARRAY)
        return result()
    }
    if (children.items.isEmpty()) walk.warn("component.components", EmbedIssueKind.EMPTY_CONTAINER)
    children.items.forEachIndexed { i, child -> walk.containerChild(child, "component.components[$i]") }

    if (walk.components > ComponentEmbedRules.MAX_COMPONENTS) {
        walk.error("", EmbedIssueKind.TOO_MANY_COMPONENTS, walk.components.toString())
    }
    if (walk.galleryItems > ComponentEmbedRules.MAX_GALLERY_ITEMS) {
        walk.error("", EmbedIssueKind.TOO_MANY_GALLERY_ITEMS, walk.galleryItems.toString())
    }
    return result(root)
}

/** The integer `type` of a component, or null */
internal fun Json?.typeNumber(): Int? = (this as? Json.Num)?.asLong?.toInt()

/** Mutable walk state for [validateComponentEmbed] */
private class EmbedWalk {
    val issues = mutableListOf<EmbedIssue>()
    var components = 0
    var galleryItems = 0

    fun error(path: String, kind: EmbedIssueKind, vararg args: String) {
        issues += EmbedIssue(IssueSeverity.ERROR, path, kind, args.toList())
    }

    fun warn(path: String, kind: EmbedIssueKind, vararg args: String) {
        issues += EmbedIssue(IssueSeverity.WARNING, path, kind, args.toList())
    }

    /** Optional `id` must be an integer when present */
    fun checkId(node: Json.Obj, path: String) {
        val id = node["id"] ?: return
        if ((id as? Json.Num)?.asLong == null) warn("$path.id", EmbedIssueKind.BAD_ID)
    }

    fun checkBool(node: Json.Obj, key: String, path: String) {
        val v = node[key] ?: return
        if (v !is Json.Bool) warn("$path.$key", EmbedIssueKind.BAD_BOOLEAN, key)
    }

    /** Optional alt text (max 1,024 chars per the Components reference) */
    fun checkDescription(node: Json.Obj, path: String) {
        val d = node["description"] ?: return
        if (d == Json.Null) return
        if (d !is Json.Str) {
            warn("$path.description", EmbedIssueKind.BAD_DESCRIPTION)
        } else if (d.value.length > ComponentEmbedRules.ALT_MAX_CHARS) {
            warn("$path.description", EmbedIssueKind.ALT_TOO_LONG, d.value.length.toString())
        }
    }

    /** An unfurled media item: http(s), ≤ 2,048 chars, a supported format */
    fun media(value: Json?, path: String, allowVideo: Boolean) {
        val url = (value as? Json.Obj)?.get("url").stringOrNull
        if (value !is Json.Obj || url.isNullOrEmpty()) {
            error(path, EmbedIssueKind.MEDIA_MISSING)
            return
        }
        if (url.startsWith("attachment://")) {
            error("$path.url", EmbedIssueKind.MEDIA_ATTACHMENT)
            return
        }
        if (!HTTP_URL.containsMatchIn(url)) {
            error("$path.url", EmbedIssueKind.MEDIA_NOT_HTTP)
            return
        }
        if (url.length > ComponentEmbedRules.MEDIA_URL_MAX_CHARS) {
            error("$path.url", EmbedIssueKind.MEDIA_URL_TOO_LONG, url.length.toString())
        }
        when (mediaKind(url)) {
            MediaKind.VIDEO -> if (!allowVideo) warn("$path.url", EmbedIssueKind.THUMBNAIL_IS_VIDEO)
            MediaKind.UNKNOWN -> warn("$path.url", EmbedIssueKind.MEDIA_UNKNOWN_EXTENSION)
            MediaKind.IMAGE -> Unit
        }
        for (key in value.keys) {
            if (key != "url") warn("$path.$key", EmbedIssueKind.MEDIA_EXTRA_KEY, key)
        }
    }

    fun button(node: Json.Obj, path: String) {
        components++
        for (key in node.keys) {
            if (key !in BUTTON_KEYS) error("$path.$key", EmbedIssueKind.BUTTON_EXTRA_KEY, key)
        }
        if ((node["style"] as? Json.Num)?.asLong != 5L) error("$path.style", EmbedIssueKind.BUTTON_NOT_LINK)
        val url = node["url"].stringOrNull
        if (url.isNullOrEmpty()) {
            error("$path.url", EmbedIssueKind.BUTTON_NO_URL)
        } else {
            if (!HTTP_URL.containsMatchIn(url)) warn("$path.url", EmbedIssueKind.BUTTON_URL_NOT_HTTP)
            if (url.length > ComponentEmbedRules.BUTTON_URL_MAX_CHARS) {
                warn("$path.url", EmbedIssueKind.BUTTON_URL_TOO_LONG, url.length.toString())
            }
        }
        val label = node["label"].stringOrNull
        val hasLabel = !label.isNullOrEmpty()
        val hasEmoji = node["emoji"] is Json.Obj
        if (!hasLabel && !hasEmoji) error(path, EmbedIssueKind.BUTTON_NO_LABEL_OR_EMOJI)
        if (label != null && label.length > ComponentEmbedRules.LABEL_MAX_CHARS) {
            warn("$path.label", EmbedIssueKind.LABEL_TOO_LONG, label.length.toString())
        }
        if (node["emoji"] != null && !hasEmoji) warn("$path.emoji", EmbedIssueKind.BAD_EMOJI)
        checkId(node, path)
        checkBool(node, "disabled", path)
    }

    fun textDisplay(node: Json.Obj, path: String) {
        components++
        if (node["content"].stringOrNull.isNullOrEmpty()) error("$path.content", EmbedIssueKind.TEXT_EMPTY)
        checkId(node, path)
    }

    fun thumbnail(node: Json.Obj, path: String) {
        components++
        media(node["media"], "$path.media", allowVideo = false)
        checkDescription(node, path)
        checkBool(node, "spoiler", path)
        checkId(node, path)
    }

    fun mediaGallery(node: Json.Obj, path: String) {
        components++
        val items = node["items"]
        if (items !is Json.Arr || items.items.isEmpty()) {
            error("$path.items", EmbedIssueKind.GALLERY_ITEM_COUNT)
            return
        }
        if (items.items.size > 10) error("$path.items", EmbedIssueKind.GALLERY_OVER_TEN, items.items.size.toString())
        items.items.forEachIndexed { i, item ->
            val itemPath = "$path.items[$i]"
            galleryItems++
            if (item !is Json.Obj) {
                error(itemPath, EmbedIssueKind.GALLERY_ITEM_NOT_OBJECT)
                return@forEachIndexed
            }
            media(item["media"], "$itemPath.media", allowVideo = true)
            checkDescription(item, itemPath)
            checkBool(item, "spoiler", itemPath)
        }
        checkId(node, path)
    }

    fun separator(node: Json.Obj, path: String) {
        components++
        checkBool(node, "divider", path)
        val spacing = node["spacing"]
        if (spacing != null) {
            val n = (spacing as? Json.Num)?.asLong
            if (n != 1L && n != 2L) warn("$path.spacing", EmbedIssueKind.BAD_SPACING)
        }
        checkId(node, path)
    }

    fun section(node: Json.Obj, path: String) {
        components++
        val children = node["components"]
        if (children !is Json.Arr || children.items.size !in 1..3) {
            error("$path.components", EmbedIssueKind.SECTION_TEXT_COUNT)
        }
        (children as? Json.Arr)?.items?.forEachIndexed { i, child ->
            val childPath = "$path.components[$i]"
            if (child is Json.Obj && child["type"].typeNumber() == ComponentType.TEXT_DISPLAY) {
                textDisplay(child, childPath)
            } else {
                error(childPath, EmbedIssueKind.SECTION_CHILD_NOT_TEXT, ComponentType.describe((child as? Json.Obj)?.get("type") ?: child))
                if (child is Json.Obj) components++
            }
        }
        val accessory = node["accessory"]
        when {
            accessory !is Json.Obj -> error("$path.accessory", EmbedIssueKind.SECTION_NO_ACCESSORY)
            accessory["type"].typeNumber() == ComponentType.BUTTON -> button(accessory, "$path.accessory")
            accessory["type"].typeNumber() == ComponentType.THUMBNAIL -> thumbnail(accessory, "$path.accessory")
            else -> {
                components++
                error("$path.accessory", EmbedIssueKind.SECTION_BAD_ACCESSORY, ComponentType.describe(accessory["type"]))
            }
        }
        checkId(node, path)
    }

    fun actionRow(node: Json.Obj, path: String) {
        components++
        val children = node["components"]
        if (children !is Json.Arr || children.items.size !in 1..5) {
            error("$path.components", EmbedIssueKind.ROW_BUTTON_COUNT)
        }
        (children as? Json.Arr)?.items?.forEachIndexed { i, child ->
            val childPath = "$path.components[$i]"
            if (child is Json.Obj && child["type"].typeNumber() == ComponentType.BUTTON) {
                button(child, childPath)
            } else {
                if (child is Json.Obj) components++
                error(childPath, EmbedIssueKind.ROW_CHILD_NOT_BUTTON, ComponentType.describe((child as? Json.Obj)?.get("type") ?: child))
            }
        }
        checkId(node, path)
    }

    /** A direct child of the root Container */
    fun containerChild(node: Json, path: String) {
        if (node !is Json.Obj) {
            error(path, EmbedIssueKind.CHILD_NOT_OBJECT)
            return
        }
        when (node["type"].typeNumber()) {
            ComponentType.ACTION_ROW -> actionRow(node, path)
            ComponentType.TEXT_DISPLAY -> textDisplay(node, path)
            ComponentType.SECTION -> section(node, path)
            ComponentType.MEDIA_GALLERY -> mediaGallery(node, path)
            ComponentType.SEPARATOR -> separator(node, path)
            ComponentType.CONTAINER -> {
                components++
                error(path, EmbedIssueKind.NESTED_CONTAINER)
            }
            ComponentType.FILE -> {
                components++
                error(path, EmbedIssueKind.FILE_UNSUPPORTED)
            }
            ComponentType.BUTTON -> {
                components++
                error(path, EmbedIssueKind.BUTTON_OUTSIDE_ROW, ComponentType.describe(node["type"]))
            }
            ComponentType.THUMBNAIL -> {
                components++
                error(path, EmbedIssueKind.THUMBNAIL_OUTSIDE_SECTION, ComponentType.describe(node["type"]))
            }
            else -> {
                components++
                error(path, EmbedIssueKind.UNSUPPORTED_TYPE, ComponentType.describe(node["type"]))
            }
        }
    }

    companion object {
        private val BUTTON_KEYS = setOf("id", "type", "url", "style", "label", "emoji", "disabled")
    }
}
