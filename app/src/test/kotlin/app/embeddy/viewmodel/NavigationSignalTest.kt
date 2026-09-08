package app.embeddy.viewmodel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M72: the navigate-to-Convert signal must survive being emitted before the
 * scaffold's collector exists (share intent handled in onNewIntent before the
 * first frame).
 */
class NavigationSignalTest {

    @Test
    fun `emission before any collector is replayed to the first collector`() = runTest {
        val nav = NavigationSignal()
        nav.emit() // nobody is listening yet — the old config dropped this
        // first() completes only if the replay cache delivers the signal
        assertEquals(Unit, nav.signal.first())
    }

    @Test
    fun `handled signal is not replayed to a later collector`() {
        val nav = NavigationSignal()
        nav.emit()
        nav.markHandled()
        assertTrue("replay cache must be empty after markHandled", nav.signal.replayCache.isEmpty())
    }

    @Test
    fun `repeated emissions collapse to a single latest signal`() {
        val nav = NavigationSignal()
        repeat(3) { nav.emit() }
        assertEquals(1, nav.signal.replayCache.size)
    }

    /** Documents the bug being fixed: the previous flow config drops the emission. */
    @Test
    fun `old extraBufferCapacity config loses an emission with no subscriber`() {
        val old = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        old.tryEmit(Unit)
        assertTrue(
            "extraBufferCapacity does not replay — a late collector never sees the signal",
            old.replayCache.isEmpty(),
        )
    }
}
