package app.embeddy.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Response classification and the progress/cancellation stream behind uploads. */
class UploadResponseTest {

    @Test
    fun `2xx with a URL body is success, trimmed`() {
        assertEquals(UploadOutcome.Success("https://0x0.st/abc.webp"), classifyUploadResponse(200, "https://0x0.st/abc.webp\n", "0x0.st"))
    }

    @Test
    fun `2xx without a URL is not treated as a link`() {
        assertTrue(classifyUploadResponse(200, "<html>maintenance</html>", "catbox.moe") is UploadOutcome.Retryable)
    }

    @Test
    fun `server errors and rate limits retry, client errors do not`() {
        assertTrue(classifyUploadResponse(503, "busy", "h") is UploadOutcome.Retryable)
        assertTrue(classifyUploadResponse(429, "slow down", "h") is UploadOutcome.Retryable)
        val tooLarge = classifyUploadResponse(413, "", "0x0.st")
        assertEquals(UploadOutcome.Permanent("0x0.st returned HTTP 413: Unknown error"), tooLarge)
    }

    @Test
    fun `hosts declare their multipart field names`() {
        assertEquals("file", UploadHost.ZER0X0.fileField)
        assertEquals(listOf("reqtype" to "fileupload"), UploadHost.CATBOX.extraFields)
    }

    @Test
    fun `progress stream forwards bytes and reports throttled fractions ending at 1`() {
        // Start well past the stream's initial throttle timestamp (0).
        var now = 1_000L
        val reports = mutableListOf<Float>()
        val sink = ByteArrayOutputStream()
        val stream = ProgressOutputStream(sink, 100, { reports += it }, clock = { now })
        stream.write(ByteArray(10))
        now += 10 // within the 50 ms throttle → suppressed
        stream.write(ByteArray(10))
        now += 60
        stream.write(ByteArray(30))
        stream.write(ByteArray(50)) // completes → always reported
        assertEquals(100, sink.size())
        assertEquals(listOf(0.1f, 0.5f, 1f), reports)
    }

    @Test(expected = IllegalStateException::class)
    fun `a cancelled upload aborts before writing more bytes`() {
        val sink = ByteArrayOutputStream()
        var cancelled = false
        val stream = ProgressOutputStream(sink, 100, {}, checkCancelled = { check(!cancelled) })
        stream.write(ByteArray(10))
        cancelled = true
        try {
            stream.write(ByteArray(10))
        } finally {
            assertEquals(10, sink.size())
        }
    }
}
