package app.embeddy.ui

import app.embeddy.ui.components.ESTIMATE_MIN_ELAPSED_MS
import app.embeddy.ui.components.ESTIMATE_MIN_PROGRESS
import app.embeddy.ui.components.INDETERMINATE_AFTER_MS
import app.embeddy.ui.components.effectiveElapsedMs
import app.embeddy.ui.components.estimateRemainingMs
import app.embeddy.ui.components.formatRemainingShort
import app.embeddy.ui.components.isProgressIndeterminate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M73: with elapsed driven only by FFmpeg stats events, "stats not arriving"
 * (the very case the indeterminate fallback exists for) left elapsedMs at 0
 * forever — a frozen determinate "0% · 0s" bar. The wall-clock design below
 * makes the fallback reachable.
 */
class ProgressLogicTest {

    @Test
    fun `stats never arriving still trips the indeterminate fallback via wall clock`() {
        // Engine reported nothing (0), the card has been visible for 5s.
        val elapsed = effectiveElapsedMs(reportedElapsedMs = 0L, wallClockElapsedMs = 5_000L)
        assertEquals(5_000L, elapsed)
        assertTrue(isProgressIndeterminate(progressFraction = 0f, elapsedMs = elapsed))
    }

    @Test
    fun `engine-reported elapsed wins when ahead of the wall clock`() {
        // A restored/attached card may start its wall clock after the engine did.
        assertEquals(9_000L, effectiveElapsedMs(reportedElapsedMs = 9_000L, wallClockElapsedMs = 2_000L))
    }

    @Test
    fun `elapsed never goes negative`() {
        assertEquals(0L, effectiveElapsedMs(reportedElapsedMs = -50L, wallClockElapsedMs = -1L))
    }

    @Test
    fun `moving progress keeps the bar determinate`() {
        assertFalse(isProgressIndeterminate(progressFraction = 0.25f, elapsedMs = 60_000L))
    }

    @Test
    fun `grace period before switching to indeterminate`() {
        assertFalse(isProgressIndeterminate(0f, INDETERMINATE_AFTER_MS))
        assertTrue(isProgressIndeterminate(0f, INDETERMINATE_AFTER_MS + 1))
    }

    // --- estimateRemainingMs -------------------------------------------------

    @Test
    fun `no estimate below the minimum progress`() {
        assertNull(estimateRemainingMs(progress = 0f, elapsedMs = 10_000L))
        assertNull(estimateRemainingMs(progress = 0.04f, elapsedMs = 10_000L))
    }

    @Test
    fun `no estimate below the minimum elapsed time`() {
        assertNull(estimateRemainingMs(progress = 0.5f, elapsedMs = 0L))
        assertNull(estimateRemainingMs(progress = 0.5f, elapsedMs = ESTIMATE_MIN_ELAPSED_MS - 1))
    }

    @Test
    fun `estimate becomes available exactly at both thresholds`() {
        assertNotNull(estimateRemainingMs(ESTIMATE_MIN_PROGRESS, ESTIMATE_MIN_ELAPSED_MS))
    }

    @Test
    fun `estimate is the linear projection of elapsed over remaining fraction`() {
        // 25% done in 10s → the other 75% should take ~30s.
        assertEquals(30_000L, estimateRemainingMs(progress = 0.25f, elapsedMs = 10_000L))
        // Halfway: remaining mirrors elapsed.
        assertEquals(10_000L, estimateRemainingMs(progress = 0.5f, elapsedMs = 10_000L))
    }

    @Test
    fun `estimate reaches zero at full progress`() {
        assertEquals(0L, estimateRemainingMs(progress = 1f, elapsedMs = 45_000L))
    }

    @Test
    fun `estimate is never negative even for overshooting progress`() {
        val remaining = estimateRemainingMs(progress = 1.5f, elapsedMs = 45_000L)
        assertNotNull(remaining)
        assertTrue(remaining!! >= 0L)
    }

    // --- formatRemainingShort ------------------------------------------------

    @Test
    fun `remaining under a minute formats as whole seconds`() {
        assertEquals("30s", formatRemainingShort(30_000L))
        assertEquals("59s", formatRemainingShort(59_000L))
    }

    @Test
    fun `remaining at or above a minute formats as m colon ss`() {
        assertEquals("1:00", formatRemainingShort(60_000L))
        assertEquals("1:30", formatRemainingShort(90_000L))
        assertEquals("12:05", formatRemainingShort(725_000L))
    }

    @Test
    fun `remaining rounds partial seconds up so the countdown never lies low`() {
        assertEquals("30s", formatRemainingShort(29_001L))
        assertEquals("1:00", formatRemainingShort(59_500L))
        assertEquals("0s", formatRemainingShort(0L))
    }
}
