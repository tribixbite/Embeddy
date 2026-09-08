package app.embeddy.ui

/**
 * File extensions the Convert tab accepts that Media3's default renderers
 * cannot decode (GIF and animated WebP are images, not video containers).
 * These get a Coil animated-image preview instead of an ExoPlayer surface.
 */
private val ANIMATED_IMAGE_EXTENSIONS = setOf("gif", "webp")

/**
 * True when [fileName] names a GIF/animated-WebP input that ExoPlayer would
 * render as a dead black surface. Extension-based on purpose: the display name
 * comes from the same ContentResolver row the rest of the Ready card uses, so
 * no extra I/O is needed at composition time. A name with no extension falls
 * through to the video path (the picker's MIME filter only admits video
 * types, image/gif, and image/webp).
 */
fun isAnimatedImageFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase() in ANIMATED_IMAGE_EXTENSIONS
