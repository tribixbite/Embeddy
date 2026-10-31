package app.embeddy.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Copies content:// URIs into app-private files for tools that need a real
 * path (FFprobe fallbacks, EXIF rewriting, frame extraction).
 *
 * Shared by the Convert, Inspect and Upload engines, which each used to carry
 * their own copy loop — none of which could be cancelled (a 2 GB copy ran to
 * completion after the user tapped Cancel) or cleaned up after a failure.
 */
object UriStaging {

    /**
     * Copy [uri] into a new file in [dir] named `<prefix>_<uuid><suffix>`.
     *
     * Runs on [Dispatchers.IO], checks for cancellation between buffers, and
     * deletes the partial file on ANY failure (including cancellation), so
     * callers only ever see a complete copy.
     *
     * @throws IOException when the provider cannot open the URI
     */
    suspend fun copyToTemp(
        context: Context,
        uri: Uri,
        dir: File,
        prefix: String,
        suffix: String = "",
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, "${prefix}_${UUID.randomUUID()}$suffix")
        var complete = false
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open $uri")
            input.use { src -> target.outputStream().use { dst -> copyCancellable(src, dst) } }
            complete = true
            target
        } finally {
            if (!complete) target.delete()
        }
    }

    /**
     * Stream [input] to [output], checking the coroutine for cancellation every
     * buffer. Returns the number of bytes copied.
     */
    suspend fun copyCancellable(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            total += n
        }
        return total
    }

    /** 256 KB: few enough syscalls for GB-sized videos, small enough to cancel promptly. */
    private const val COPY_BUFFER_BYTES = 256 * 1024
}
