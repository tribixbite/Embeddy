package app.embeddy.inspect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fetches and parses HTML metadata from URLs using Jsoup for robust parsing.
 * Extracts Open Graph, Twitter Card, and general meta tags along with
 * structured data needed for social embed previews.
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
