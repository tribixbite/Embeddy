package app.embeddy.conversion

import org.junit.Assert.*
import org.junit.Test

/** Tests for [Preset] enum and its configuration values. */
class PresetTest {

    @Test
    fun `all presets have positive target size`() {
        Preset.entries.forEach { preset ->
            assertTrue(
                "${preset.name} should have positive targetSizeBytes",
                preset.targetSizeBytes > 0,
            )
        }
    }

    @Test
    fun `all presets have valid quality range`() {
        Preset.entries.forEach { preset ->
            assertTrue("${preset.name}: minQuality should be > 0", preset.minQuality > 0)
            assertTrue("${preset.name}: startQuality should be > minQuality",
                preset.startQuality > preset.minQuality)
            assertTrue("${preset.name}: qualityStep should be > 0", preset.qualityStep > 0)
        }
    }

    @Test
    fun `all presets have valid dimensions`() {
        Preset.entries.forEach { preset ->
            assertTrue("${preset.name}: maxDimension > 0", preset.maxDimension > 0)
            assertTrue("${preset.name}: fps > 0", preset.fps > 0)
        }
    }

    @Test
    fun `discord preset matches expected constraints`() {
        assertEquals(10_000_000L, Preset.DISCORD.targetSizeBytes)
        assertEquals(720, Preset.DISCORD.maxDimension)
    }

    @Test
    fun `telegram preset targets sticker size`() {
        assertEquals(256_000L, Preset.TELEGRAM.targetSizeBytes)
        assertEquals(512, Preset.TELEGRAM.maxDimension)
    }

    @Test
    fun `custom preset label is Custom`() {
        assertEquals("Custom", Preset.CUSTOM.label)
    }

    @Test
    fun `quality step produces valid iteration count`() {
        Preset.entries.forEach { preset ->
            val steps = (preset.startQuality - preset.minQuality) / preset.qualityStep
            assertTrue("${preset.name}: should have at least 1 quality step", steps >= 1)
        }
    }
}
