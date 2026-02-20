package app.embeddy.inspect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and parses HTML metadata from URLs: Open Graph, Twitter Card, and general meta tags.
 * Uses regex-based parsing to avoid pulling in an HTML parser dependency.
 */
class MetadataEngine {

    /** Fetch and parse metadata from the given URL. */
    suspend fun fetchMetadata(urlString: String): MetadataResult = withContext(Dispatchers.IO) {
        val normalizedUrl = if (!urlString.startsWith("http")) "https://$urlString" else urlString

        try {
            val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Embeddy/1.0 (Link Preview Bot)")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext MetadataResult(
                    url = normalizedUrl,
                    error = "HTTP $responseCode: ${connection.responseMessage}",
                )
            }

            // Read only the first 128KB to avoid downloading huge pages
            val html = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(131_072)
                val read = reader.read(buffer)
                if (read > 0) String(buffer, 0, read) else ""
            }
            connection.disconnect()

            parseHtml(normalizedUrl, html)
        } catch (e: Exception) {
            MetadataResult(url = normalizedUrl, error = e.message ?: "Failed to fetch URL")
        }
    }

    /** Parse HTML string for meta tags, title, and link elements. */
    private fun parseHtml(url: String, html: String): MetadataResult {
        val ogTags = mutableMapOf<String, String>()
        val twitterTags = mutableMapOf<String, String>()
        val generalTags = mutableMapOf<String, String>()

        // Extract <title> tag
        TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { title ->
            generalTags["title"] = title.decodeHtmlEntities().trim()
        }

        // Extract <link rel="canonical">
        CANONICAL_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { canonical ->
            generalTags["canonical"] = canonical.trim()
        }

        // Extract <link rel="icon"> (favicon)
        FAVICON_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { favicon ->
            generalTags["favicon"] = resolveUrl(url, favicon.trim())
        }

        // Extract <meta charset>
        CHARSET_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { charset ->
            generalTags["charset"] = charset.trim()
        }

        // Extract all <meta> tags with property or name attributes
        META_REGEX.findAll(html).forEach { match ->
            val tag = match.value
            val property = extractAttr(tag, "property") ?: extractAttr(tag, "name") ?: return@forEach
            val content = extractAttr(tag, "content") ?: return@forEach

            when {
                property.startsWith("og:") -> ogTags[property] = content.decodeHtmlEntities()
                property.startsWith("twitter:") -> twitterTags[property] = content.decodeHtmlEntities()
                property == "description" -> generalTags["description"] = content.decodeHtmlEntities()
                property == "author" -> generalTags["author"] = content.decodeHtmlEntities()
                property == "theme-color" -> generalTags["theme-color"] = content
                property == "robots" -> generalTags["robots"] = content
            }
        }

        // Resolve relative image URLs to absolute
        ogTags["og:image"]?.let { ogTags["og:image"] = resolveUrl(url, it) }
        twitterTags["twitter:image"]?.let { twitterTags["twitter:image"] = resolveUrl(url, it) }

        return MetadataResult(
            url = url,
            ogTags = ogTags,
            twitterTags = twitterTags,
            generalTags = generalTags,
        )
    }

    /** Extract an attribute value from an HTML tag string. */
    private fun extractAttr(tag: String, attr: String): String? {
        val regex = Regex("""$attr\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.getOrNull(1)
    }

    /** Resolve a potentially relative URL against a base. */
    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("//")) return "https:$relative"
        return try {
            URL(URL(base), relative).toString()
        } catch (_: Exception) {
            relative
        }
    }

    /** Decode common HTML entities. */
    private fun String.decodeHtmlEntities(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    companion object {
        private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE)
        private val CANONICAL_REGEX = Regex(
            """<link[^>]+rel\s*=\s*["']canonical["'][^>]+href\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val FAVICON_REGEX = Regex(
            """<link[^>]+rel\s*=\s*["'](?:icon|shortcut icon)["'][^>]+href\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val CHARSET_REGEX = Regex(
            """<meta[^>]+charset\s*=\s*["']?([^"'\s>]+)""",
            RegexOption.IGNORE_CASE,
        )
        private val META_REGEX = Regex(
            """<meta\s[^>]*(?:property|name)\s*=\s*["'][^"']*["'][^>]*/?>""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Result of metadata extraction from a URL. */
data class MetadataResult(
    val url: String,
    val ogTags: Map<String, String> = emptyMap(),
    val twitterTags: Map<String, String> = emptyMap(),
    val generalTags: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val hasData: Boolean get() = ogTags.isNotEmpty() || twitterTags.isNotEmpty() || generalTags.isNotEmpty()

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
