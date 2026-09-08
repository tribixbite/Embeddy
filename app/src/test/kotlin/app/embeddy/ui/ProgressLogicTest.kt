package app.embeddy.ui

import app.embeddy.ui.components.INDETERMINATE_AFTER_MS
import app.embeddy.ui.components.effectiveElapsedMs
import app.embeddy.ui.components.isProgressIndeterminate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
