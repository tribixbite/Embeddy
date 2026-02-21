package app.embeddy.inspect

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fetches and parses metadata from URLs (HTML meta tags via Jsoup) and
 * local file URIs (EXIF + MediaMetadataRetriever).
 * Extracts Open Graph, Twitter Card, general meta tags, and media-specific
 * technical details for comprehensive inspection.
 */
class MetadataEngine {

    /** Fetch and parse metadata from the given URL. */
    suspend fun fetchMetadata(urlString: String): MetadataResult = withContext(Dispatchers.IO) {
        val normalizedUrl = if (!urlString.startsWith("http")) "https://$urlString" else urlString

        try {
            val doc: Document = Jsoup.connect(normalizedUrl)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .followRedirects(true)
                .maxBodySize(256_000) // Only parse first 256KB
                .get()

            parseDocument(normalizedUrl, doc)
        } catch (e: Exception) {
            MetadataResult(url = normalizedUrl, error = e.message ?: "Failed to fetch URL")
        }
    }

    /**
     * Inspect a local file URI for all available media metadata.
     * Uses MediaMetadataRetriever for video/audio and ExifInterface for images.
     * Returns a comprehensive MetadataResult with media-specific technical details.
     */
    suspend fun inspectLocalFile(context: Context, uri: Uri): MetadataResult = withContext(Dispatchers.IO) {
        val generalTags = linkedMapOf<String, String>()
        val mediaTags = linkedMapOf<String, String>()

        // Query basic file info
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "Unknown"
        val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else null
        } ?: 0L
        val mimeType = context.contentResolver.getType(uri) ?: "unknown"

        generalTags["File Name"] = fileName
        generalTags["MIME Type"] = mimeType
        generalTags["File Size"] = formatFileSize(fileSize)

        // MediaMetadataRetriever for video/audio metadata
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            fun extract(key: Int, label: String) {
                retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let {
                    mediaTags[label] = it
                }
            }

            // Video dimensions
            extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "Video Width")
            extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "Video Height")
            extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "Rotation")
            extract(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "Frame Count")

            // Duration
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { ms ->
                    val secs = ms / 1000
                    mediaTags["Duration"] = "${secs / 60}m ${secs % 60}s (${ms}ms)"
                }

            // Bitrate
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()?.let { bps ->
                    mediaTags["Bitrate"] = "${bps / 1000} kbps"
                }

            // Audio
            extract(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "Has Audio")
            extract(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "Has Video")
            extract(MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "Container Format")
            extract(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS, "Track Count")

            // Dates
            extract(MediaMetadataRetriever.METADATA_KEY_DATE, "Date")

            // Content info
            extract(MediaMetadataRetriever.METADATA_KEY_TITLE, "Title")
            extract(MediaMetadataRetriever.METADATA_KEY_ARTIST, "Artist")
            extract(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album")
            extract(MediaMetadataRetriever.METADATA_KEY_GENRE, "Genre")
            extract(MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Author")
            extract(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "Composer")
            extract(MediaMetadataRetriever.METADATA_KEY_WRITER, "Writer")
            extract(MediaMetadataRetriever.METADATA_KEY_LOCATION, "Location")

            // Codec info (API 28+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                extract(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE, "Sample Rate")
                extract(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE, "Bits Per Sample")
            }
        } catch (_: Exception) {
            // Not a valid media file for MediaMetadataRetriever
        } finally {
            retriever.release()
        }

        // EXIF metadata for images
        if (mimeType.startsWith("image/")) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val exifTags = linkedMapOf<String, String>()

                    fun readExif(tag: String, label: String) {
                        exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let {
                            exifTags[label] = it
                        }
                    }

                    // Camera info
                    readExif(ExifInterface.TAG_MAKE, "Camera Make")
                    readExif(ExifInterface.TAG_MODEL, "Camera Model")
                    readExif(ExifInterface.TAG_SOFTWARE, "Software")
                    readExif(ExifInterface.TAG_DATETIME, "Date/Time")
                    readExif(ExifInterface.TAG_DATETIME_ORIGINAL, "Original Date")

                    // Image dimensions
                    readExif(ExifInterface.TAG_IMAGE_WIDTH, "Image Width")
                    readExif(ExifInterface.TAG_IMAGE_LENGTH, "Image Height")
                    readExif(ExifInterface.TAG_ORIENTATION, "Orientation")
                    readExif(ExifInterface.TAG_X_RESOLUTION, "X Resolution")
                    readExif(ExifInterface.TAG_Y_RESOLUTION, "Y Resolution")
                    readExif(ExifInterface.TAG_COLOR_SPACE, "Color Space")

                    // Exposure settings
                    readExif(ExifInterface.TAG_EXPOSURE_TIME, "Exposure Time")
                    readExif(ExifInterface.TAG_F_NUMBER, "F-Number")
                    readExif(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "ISO")
                    readExif(ExifInterface.TAG_FOCAL_LENGTH, "Focal Length")
                    readExif(ExifInterface.TAG_FLASH, "Flash")
                    readExif(ExifInterface.TAG_WHITE_BALANCE, "White Balance")

                    // GPS
                    readExif(ExifInterface.TAG_GPS_LATITUDE, "GPS Latitude")
                    readExif(ExifInterface.TAG_GPS_LONGITUDE, "GPS Longitude")
                    readExif(ExifInterface.TAG_GPS_ALTITUDE, "GPS Altitude")

                    // Author
                    readExif(ExifInterface.TAG_ARTIST, "Artist")
                    readExif(ExifInterface.TAG_COPYRIGHT, "Copyright")
                    readExif(ExifInterface.TAG_USER_COMMENT, "Comment")

                    mediaTags.putAll(exifTags)
                }
            } catch (_: Exception) {
                // EXIF parsing failed silently
            }
        }

        MetadataResult(
            url = uri.toString(),
            generalTags = generalTags,
            mediaTags = mediaTags,
        )
    }

    /** Parse a Jsoup Document for meta tags, title, and link elements. */
    private fun parseDocument(url: String, doc: Document): MetadataResult {
        val ogTags = mutableMapOf<String, String>()
        val twitterTags = mutableMapOf<String, String>()
        val generalTags = mutableMapOf<String, String>()

        // Page title
        doc.title().takeIf { it.isNotBlank() }?.let { generalTags["title"] = it }

        // Canonical URL
        doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let { generalTags["canonical"] = it }

        // Favicon — try multiple common link[rel] patterns, use first match
        val favicon = doc.selectFirst("link[rel=icon], link[rel='shortcut icon'], link[rel=apple-touch-icon]")
            ?.attr("abs:href")
        if (!favicon.isNullOrBlank()) {
            generalTags["favicon"] = favicon
        }

        // Charset
        doc.selectFirst("meta[charset]")?.attr("charset")
            ?.takeIf { it.isNotBlank() }?.let { generalTags["charset"] = it }

        // All <meta> tags with property or name attributes
        doc.select("meta[property], meta[name]").forEach { meta ->
            val property = meta.attr("property").ifBlank { meta.attr("name") }
            val content = meta.attr("content")
            if (property.isBlank() || content.isBlank()) return@forEach

            when {
                property.startsWith("og:") -> ogTags[property] = content
                property.startsWith("twitter:") -> twitterTags[property] = content
                property == "description" -> generalTags["description"] = content
                property == "author" -> generalTags["author"] = content
                property == "theme-color" -> generalTags["theme-color"] = content
                property == "robots" -> generalTags["robots"] = content
                property == "generator" -> generalTags["generator"] = content
            }
        }

        // Resolve relative image URLs to absolute using Jsoup's abs: attribute
        ogTags["og:image"]?.let { img ->
            if (!img.startsWith("http")) {
                doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
                    ?.takeIf { it.isNotBlank() }?.let { ogTags["og:image"] = it }
            }
        }
        twitterTags["twitter:image"]?.let { img ->
            if (!img.startsWith("http")) {
                doc.selectFirst("meta[name=twitter:image]")?.attr("abs:content")
                    ?.takeIf { it.isNotBlank() }?.let { twitterTags["twitter:image"] = it }
            }
        }

        return MetadataResult(
            url = url,
            ogTags = ogTags,
            twitterTags = twitterTags,
            generalTags = generalTags,
        )
    }

    companion object {
        private const val USER_AGENT = "Embeddy/1.0 (Link Preview Bot)"

        private fun formatFileSize(bytes: Long): String = when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}

/** Result of metadata extraction from a URL or file. */
data class MetadataResult(
    val url: String,
    val ogTags: Map<String, String> = emptyMap(),
    val twitterTags: Map<String, String> = emptyMap(),
    val generalTags: Map<String, String> = emptyMap(),
    val mediaTags: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val hasData: Boolean get() = ogTags.isNotEmpty() || twitterTags.isNotEmpty() ||
        generalTags.isNotEmpty() || mediaTags.isNotEmpty()

    /** Best available title across all tag sources. */
    val title: String? get() = ogTags["og:title"] ?: twitterTags["twitter:title"] ?: generalTags["title"]

    /** Best available description. */
    val description: String? get() = ogTags["og:description"] ?: twitterTags["twitter:description"] ?: generalTags["description"]

    /** Best available image URL. */
    val imageUrl: String? get() = ogTags["og:image"] ?: twitterTags["twitter:image"]

    /** Site name from OG tags. */
    val siteName: String? get() = ogTags["og:site_name"]

    /** OG type (website, article, video, etc). */
    val ogType: String? get() = ogTags["og:type"]
}

/** Sealed interface for inspect screen state. */
sealed interface InspectState {
    data object Idle : InspectState
    data class Fetching(val url: String) : InspectState
    data class Success(val result: MetadataResult) : InspectState
    data class Error(val message: String) : InspectState
}
