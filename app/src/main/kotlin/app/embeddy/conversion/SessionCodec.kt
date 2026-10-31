package app.embeddy.conversion

/**
 * The Convert tab's per-input session: what was picked plus how it is edited.
 * Saved to SavedStateHandle so process death restores the full edit.
 */
data class ConvertSession(
    val source: SourceMedia,
    val edit: EditSpec,
)

/**
 * Line-based text codec for [ConvertSession].
 *
 * SavedStateHandle only stores Bundle-compatible values; a single String avoids
 * Parcelable plumbing (and a Gradle plugin) for nested lists, and keeps the
 * format readable in a debugger. Format, one record per line:
 *
 * ```
 * v=1
 * kind=SINGLE
 * clip=<uri>|<name>|<size>|<durationMs>|<width>|<height>|<hasAudio>
 * trim=<startMs>|<endMs>
 * seg=<startMs>|<endMs>|<volume>
 * fx=<muted>|<speed>|<rotation>|<flipH>|<flipV>|<reverse>|<frameDurationMs>
 * crop=<left>|<top>|<right>|<bottom>
 * range=<startMs>|<endMs>          (JOIN, one per clip, in clip order)
 * ```
 *
 * Field values percent-escape `%`, `|`, CR and LF, so URIs and display names
 * round-trip exactly. Unknown keys are ignored, so a newer build can add fields
 * without breaking restoration of an older saved session (and vice versa).
 */
object SessionCodec {

    private const val VERSION = 1
    private const val SEP = '|'

    /** Serialize [session] to the line format described on [SessionCodec]. */
    fun encode(session: ConvertSession): String = buildString {
        appendLine("v=$VERSION")
        appendLine("kind=${session.source.kind.name}")
        session.source.clips.forEach { c ->
            appendLine(
                "clip=" + listOf(
                    escape(c.uri), escape(c.fileName), c.fileSize, c.durationMs,
                    c.width, c.height, c.hasAudio,
                ).joinToString(SEP.toString()),
            )
        }
        val e = session.edit
        appendLine("trim=${e.trimStartMs}$SEP${e.trimEndMs}")
        e.segments.forEach { s -> appendLine("seg=${s.startMs}$SEP${s.endMs}$SEP${s.volume}") }
        appendLine(
            "fx=" + listOf(
                e.muted, e.speed, e.rotationDegrees, e.flipHorizontal, e.flipVertical, e.reverse, e.frameDurationMs,
            ).joinToString(SEP.toString()),
        )
        e.crop?.let { c -> appendLine("crop=${c.left}$SEP${c.top}$SEP${c.right}$SEP${c.bottom}") }
        e.clipRanges.forEach { r -> appendLine("range=${r.startMs}$SEP${r.endMs}") }
    }

    /**
     * Parse a saved session, or null when [text] is missing, from an unknown
     * version, or has no clips (a session without input is meaningless).
     */
    fun decode(text: String?): ConvertSession? {
        if (text.isNullOrBlank()) return null
        var version = -1
        var kind = SourceKind.SINGLE
        val clips = mutableListOf<SourceClip>()
        var edit = EditSpec()
        val segments = mutableListOf<TrimSegment>()
        val clipRanges = mutableListOf<TrimSegment>()

        for (line in text.lineSequence()) {
            val key = line.substringBefore('=', missingDelimiterValue = "")
            if (key.isEmpty()) continue
            val value = line.substringAfter('=')
            val parts = value.split(SEP)
            when (key) {
                "v" -> version = value.toIntOrNull() ?: return null
                "kind" -> kind = SourceKind.entries.firstOrNull { it.name == value } ?: return null
                "clip" -> {
                    if (parts.size < 7) return null
                    clips += SourceClip(
                        uri = unescape(parts[0]),
                        fileName = unescape(parts[1]),
                        fileSize = parts[2].toLongOrNull() ?: 0L,
                        durationMs = parts[3].toLongOrNull() ?: 0L,
                        width = parts[4].toIntOrNull() ?: 0,
                        height = parts[5].toIntOrNull() ?: 0,
                        hasAudio = parts[6].toBooleanStrictOrNull() ?: false,
                    )
                }
                "trim" -> if (parts.size >= 2) {
                    edit = edit.copy(
                        trimStartMs = parts[0].toLongOrNull() ?: 0L,
                        trimEndMs = parts[1].toLongOrNull() ?: 0L,
                    )
                }
                "seg" -> if (parts.size >= 3) {
                    val start = parts[0].toLongOrNull()
                    val end = parts[1].toLongOrNull()
                    val volume = parts[2].toFloatOrNull()
                    if (start != null && end != null && volume != null) {
                        segments += TrimSegment(start, end, volume)
                    }
                }
                "fx" -> if (parts.size >= 7) {
                    edit = edit.copy(
                        muted = parts[0].toBooleanStrictOrNull() ?: false,
                        speed = parts[1].toFloatOrNull() ?: 1f,
                        rotationDegrees = parts[2].toIntOrNull() ?: 0,
                        flipHorizontal = parts[3].toBooleanStrictOrNull() ?: false,
                        flipVertical = parts[4].toBooleanStrictOrNull() ?: false,
                        reverse = parts[5].toBooleanStrictOrNull() ?: false,
                        frameDurationMs = parts[6].toIntOrNull() ?: DEFAULT_FRAME_DURATION_MS,
                    )
                }
                "crop" -> if (parts.size >= 4) {
                    val v = parts.take(4).map { it.toFloatOrNull() }
                    if (v.all { it != null }) edit = edit.copy(crop = CropRect(v[0]!!, v[1]!!, v[2]!!, v[3]!!))
                }
                "range" -> if (parts.size >= 2) {
                    val start = parts[0].toLongOrNull()
                    val end = parts[1].toLongOrNull()
                    if (start != null && end != null) clipRanges += TrimSegment(start, end)
                }
                // Unknown keys: written by a newer build — skip, don't fail.
            }
        }
        if (version != VERSION || clips.isEmpty()) return null
        return ConvertSession(SourceMedia(kind, clips), edit.copy(segments = segments, clipRanges = clipRanges))
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '%' -> append("%25")
                SEP -> append("%7C")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(ch)
            }
        }
    }

    private fun unescape(s: String): String = s
        .replace("%7C", SEP.toString())
        .replace("%0A", "\n")
        .replace("%0D", "\r")
        // Last, so an escaped "%257C" decodes to the literal text "%7C".
        .replace("%25", "%")
}
