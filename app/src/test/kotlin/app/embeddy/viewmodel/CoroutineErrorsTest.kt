package app.embeddy.viewmodel

import kotlinx.coroutines.CancellationException
import org.junit.Assert.fail
import org.junit.Test

/** M27: cancellation must never be mapped to a user-facing Error state. */
class CoroutineErrorsTest {

    @Test(expected = CancellationException::class)
    fun `CancellationException is rethrown`() {
        CancellationException("cancelled").rethrowIfCancellation()
        fail("should have rethrown")
    }

    @Test(expected = CancellationException::class)
    fun `CancellationException subclasses are rethrown`() {
        object : CancellationException("job cancelled") {}.rethrowIfCancellation()
        fail("should have rethrown")
    }

    @Test
    fun `ordinary exceptions pass through untouched`() {
        // Must NOT throw — the caller then maps these to an Error state.
        IllegalStateException("engine failure").rethrowIfCancellation()
        RuntimeException("boom").rethrowIfCancellation()
    }
}
