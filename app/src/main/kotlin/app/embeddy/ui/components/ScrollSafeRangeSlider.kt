package app.embeddy.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import kotlin.math.abs

/**
 * A Material 3 [RangeSlider] that ignores page scrolls which start on its track.
 *
 * Material 3 1.3's RangeSlider waits for horizontal touch slop, and when a parent
 * vertical scroll takes the gesture first it still moves the nearest thumb to the
 * touch-down point. On the Convert screen that meant scrolling past a trim or stitch
 * slider silently changed a range (seen on a Saga, 2026-11-25). Newer Material 3
 * releases only react to drags that are more horizontal than vertical.
 *
 * This wrapper watches each gesture in the Initial pass, before the slider, without
 * consuming anything, so the scroll still happens. Once a gesture moves past touch
 * slop more vertically than horizontally it is a scroll: the slider's callbacks for
 * it are dropped, and the slider is recreated so its internal thumb offsets come
 * back from [value].
 *
 * TODO: drop this wrapper once the Compose BOM upgrade (TASKS 11.7) brings a
 * Material 3 with the direction check in rangeSliderPressDragModifier.
 */
@Composable
fun ScrollSafeRangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    val guard = remember { ScrollGuard() }
    // Bumped when a scroll gesture is dropped, to rebuild the slider's state
    var reset by remember { mutableIntStateOf(0) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    key(reset) {
        RangeSlider(
            value = value,
            onValueChange = { range ->
                if (guard.scrolling) {
                    if (!guard.dropped) {
                        guard.dropped = true
                        reset++
                    }
                } else {
                    currentOnValueChange(range)
                }
            },
            // Modifiers before the slider's own pointer handling see each event first
            modifier = modifier.pointerInput(guard) { watchForScroll(guard) },
            valueRange = valueRange,
            onValueChangeFinished = {
                if (!guard.scrolling) currentOnValueChangeFinished?.invoke()
            },
            colors = colors,
        )
    }
}

/** Per-gesture state shared between the pointer watcher and the slider callbacks */
private class ScrollGuard {
    /** The current gesture became a vertical scroll before any horizontal drag */
    var scrolling = false

    /** A callback of the current scroll gesture was already dropped */
    var dropped = false
}

/**
 * Mark each gesture as a scroll once it passes touch slop more vertically than
 * horizontally. Runs in the Initial pass and consumes nothing.
 */
private suspend fun PointerInputScope.watchForScroll(guard: ScrollGuard) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        guard.scrolling = false
        guard.dropped = false
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            total += change.positionChangeIgnoreConsumed()
            // A horizontal drag established first belongs to the slider
            if (abs(total.x) > slop && abs(total.x) >= abs(total.y)) break
            if (abs(total.y) > slop && abs(total.y) > abs(total.x)) {
                guard.scrolling = true
                break
            }
        }
    }
}
