package app.embeddy.conversion

/**
 * Predefined conversion presets for common platforms.
 * Each defines max file size, resolution, fps, and quality range.
 */
enum class Preset(
    val label: String,
    val targetSizeBytes: Long,
    val maxDimension: Int,
    val fps: Int,
    val startQuality: Int,
    val minQuality: Int,
    val qualityStep: Int,
    val sharpen: Boolean,
) {
    DISCORD(
        label = "Discord",
        targetSizeBytes = 10_000_000L,  // 10 MB
        maxDimension = 720,
        fps = 12,
        startQuality = 70,
        minQuality = 50,
        qualityStep = 5,
        sharpen = true,
    ),
    TELEGRAM(
        label = "Telegram",
        targetSizeBytes = 256_000L,     // 256 KB (sticker-friendly)
        maxDimension = 512,
        fps = 30,
        startQuality = 75,
        minQuality = 30,
        qualityStep = 5,
        sharpen = false,
    ),
    SLACK(
        label = "Slack",
        targetSizeBytes = 5_000_000L,   // 5 MB
        maxDimension = 640,
        fps = 12,
        startQuality = 65,
        minQuality = 40,
        qualityStep = 5,
        sharpen = true,
    ),
    CUSTOM(
        label = "Custom",
        targetSizeBytes = 10_000_000L,
        maxDimension = 720,
        fps = 12,
        startQuality = 70,
        minQuality = 30,
        qualityStep = 5,
        sharpen = true,
    );
}
