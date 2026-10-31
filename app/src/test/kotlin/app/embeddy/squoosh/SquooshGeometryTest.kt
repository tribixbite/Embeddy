package app.embeddy.squoosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode budgeting (H4) and center-crop arithmetic for [SquooshEngine]. */
class SquooshGeometryTest {

    private val generousBudget = Long.MAX_VALUE

    @Test
    fun `no subsampling when the image is near the target`() {
        assertEquals(1, sampleSizeFor(4000, 3000, 2000, generousBudget))
    }

    @Test
    fun `subsamples while more than twice the target on both axes`() {
        assertEquals(4, sampleSizeFor(4032, 3024, 720, generousBudget))
    }

    @Test
    fun `a 200 MP photo with no resize is subsampled to fit the pixel budget`() {
        // 16320x12240 ≈ 200 MP; a 512 MB heap gives 16 M pixels of budget.
        val budget = 512L * 1024 * 1024 / 8 / 4
        val sample = sampleSizeFor(16_320, 12_240, 0, budget)
        assertEquals(4, sample)
        assertTrue((16_320L / sample) * (12_240L / sample) <= budget)
    }

    @Test
    fun `degenerate sizes decode at sample 1`() {
        assertEquals(1, sampleSizeFor(0, 0, 100, 10))
    }

    @Test
    fun `center crop covers the target and centers the window`() {
        assertEquals(CropGeometry(178, 100, 39, 0), centerCropGeometry(1920, 1080, 100, 100))
        assertEquals(CropGeometry(100, 178, 0, 39), centerCropGeometry(1080, 1920, 100, 100))
    }

    @Test
    fun `rounding never leaves the scaled image smaller than the crop`() {
        for (w in 99..105) for (h in 99..105) {
            val g = centerCropGeometry(w, h, 37, 61)
            assertTrue(g.scaledWidth >= 37 && g.scaledHeight >= 61)
            assertTrue(g.cropX + 37 <= g.scaledWidth && g.cropY + 61 <= g.scaledHeight)
        }
    }
}
