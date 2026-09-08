package app.embeddy.viewmodel

import kotlinx.coroutines.CancellationException

/**
 * Rethrow when this is a [CancellationException]; no-op otherwise.
 *
 * Every ViewModel that maps engine failures to an Error state must call this at
 * the top of its `catch (e: Exception)` block: cancelling a job (Cancel button,
 * a new input superseding in-flight work) resumes the suspended coroutine with
 * a CancellationException, and swallowing it would both break structured
 * concurrency and paint a bogus Error card over whatever state the
 * cancellation-initiator just set (e.g. "Upload failed" right after tapping
 * Cancel, or a stale job clobbering the new job's progress state).
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
