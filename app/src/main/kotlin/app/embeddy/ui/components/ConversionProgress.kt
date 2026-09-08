package app.embeddy.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.embeddy.R
import app.embeddy.conversion.ConversionState
import kotlinx.coroutines.delay

@Composable
fun ConversionProgressCard(
    state: ConversionState.Converting,
    targetSizeBytes: Long = 0,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Optional label override — defaults to "Converting…" string resource. */
    label: String? = null,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(300),
        label = "progress",
    )

    // Wall-clock ticker: the engine only updates elapsedMs from FFmpeg stats
    // events, so "stats not arriving" (exactly what the indeterminate fallback
    // exists for) used to leave elapsedMs at 0 forever — a frozen "0% · 0s" bar
    // — and previews hardcode elapsedMs = 0. The displayed elapsed time is the
    // max of the engine's report and time since this card appeared.
    //
    // Both clocks re-baseline on state.attempt: the adaptive target-size loop
    // restarts encoding from 0% at each lower quality, but the engine reports
    // elapsedMs cumulatively since the FIRST attempt. Feeding cumulative
    // elapsed into the ETA at a fresh attempt's tiny fraction would project an
    // absurd remaining time, so elapsed (and the ETA built on it) is
    // per-attempt. The engine baseline is captured on the Attempt transition,
    // where state.elapsedMs still holds the previous attempt's final value.
    val startedAtMs = remember(state.attempt) { SystemClock.elapsedRealtime() }
    val engineElapsedBaseMs = remember(state.attempt) { state.elapsedMs }
    var wallElapsedMs by remember(state.attempt) { mutableLongStateOf(0L) }
    LaunchedEffect(state.attempt) {
        while (true) {
            wallElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            delay(1_000)
        }
    }
    val elapsedMs = effectiveElapsedMs(
        (state.elapsedMs - engineElapsedBaseMs).coerceAtLeast(0L),
        wallElapsedMs,
    )

    // Use indeterminate bar when progress hasn't moved (duration unknown / stats not arriving)
    val indeterminate = isProgressIndeterminate(state.progress, elapsedMs)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = label ?: stringResource(R.string.converting),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(4.dp))

            val attemptText = if (targetSizeBytes > 0) {
                val mb = String.format("%.1f MB", targetSizeBytes / 1_000_000.0)
                stringResource(R.string.attempt_quality_target, state.currentQuality, mb)
            } else {
                stringResource(R.string.attempt_quality, state.currentQuality)
            }
            Text(
                text = attemptText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            if (indeterminate) {
                // No progress data — show animated indeterminate bar
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val elapsed = formatElapsed(elapsedMs)
                // ETA projects from the real (per-attempt) fraction, not the
                // animated one — the tween lag would skew the estimate.
                val remainingMs = if (indeterminate) null else estimateRemainingMs(state.progress, elapsedMs)
                val statusText = when {
                    indeterminate -> stringResource(R.string.encoding_status, elapsed)
                    remainingMs != null -> stringResource(
                        R.string.progress_status_eta,
                        (animatedProgress * 100).toInt(),
                        elapsed,
                        formatRemainingShort(remainingMs),
                    )
                    else -> stringResource(R.string.progress_status, (animatedProgress * 100).toInt(), elapsed)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 60_000)
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
