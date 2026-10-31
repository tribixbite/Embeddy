package app.embeddy.inspect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class InspectUrlTest {

    @Test
    fun `http is upgraded to https and bare hosts get https`() {
        assertEquals("https://example.com/a", normalizeInspectUrl("http://example.com/a"))
        assertEquals("https://example.com/a", normalizeInspectUrl("HTTP://example.com/a".replaceFirst("HTTP", "http")))
        assertEquals("https://example.com", normalizeInspectUrl("  example.com "))
        assertEquals("https://x.y/z?q=1", normalizeInspectUrl("https://x.y/z?q=1"))
    }

    @Test
    fun `fetch failures map to specific kinds`() {
        assertEquals(FetchError.UNKNOWN_HOST, classifyFetchError(UnknownHostException("x")))
        assertEquals(FetchError.TIMEOUT, classifyFetchError(SocketTimeoutException()))
        assertEquals(FetchError.TLS, classifyFetchError(SSLHandshakeException("bad cert")))
        assertEquals(
            FetchError.CLEARTEXT_BLOCKED,
            classifyFetchError(IOException("Cleartext HTTP traffic to example.com not permitted")),
        )
        assertEquals(FetchError.INVALID_URL, classifyFetchError(IllegalArgumentException("Malformed URL")))
        assertEquals(FetchError.OTHER, classifyFetchError(IOException("reset")))
    }
}
