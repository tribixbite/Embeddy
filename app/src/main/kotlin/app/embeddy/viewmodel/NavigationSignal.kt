package app.embeddy.viewmodel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot navigation signal that survives the gap between emission and the
 * first collector.
 *
 * A plain `MutableSharedFlow(extraBufferCapacity = 1)` drops emissions that
 * arrive while no subscriber exists — after process death on another tab, a
 * share intent's emission from `onNewIntent` precedes the scaffold's
 * first-frame collector, so the signal was silently lost and the UI stayed on
 * the wrong tab. `replay = 1` keeps the latest un-handled signal for whoever
 * subscribes next; [markHandled] clears it so a re-subscription (configuration
 * change, tab recreation) does not replay a stale navigation.
 */
class NavigationSignal {

    private val flow = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // latest-wins; tryEmit never fails
    )

    /** Observable signal — replays the latest un-handled emission to new collectors. */
    val signal: SharedFlow<Unit> = flow.asSharedFlow()

    /** Fire the signal. Never suspends and never drops (replay slot always accepts). */
    fun emit() {
        flow.tryEmit(Unit)
    }

    /** Clear the replayed signal once a collector has acted on it. */
    fun markHandled() {
        flow.resetReplayCache()
    }
}
