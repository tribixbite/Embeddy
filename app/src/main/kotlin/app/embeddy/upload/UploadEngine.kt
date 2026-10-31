package app.embeddy.upload

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.UriStaging
import app.embeddy.util.deleteStaleEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads files to anonymous hosting services (0x0.st, catbox.moe).
 * Optionally strips EXIF metadata before upload.
 * Emits progress via callback for determinate progress UI.
 */
class UploadEngine(private val context: Context) {

    private val tempDir: File
        get() = File(context.cacheDir, "upload_temp").also { it.mkdirs() }

    /**
     * Upload a file to the selected host, optionally stripping metadata.
     * Retries up to [MAX_RETRIES] times on transient IO/HTTP failures.
     *
     * Cancellation actually stops the transfer: the body writer checks the
     * coroutine between chunks and a cancelled job disconnects the socket, so
     * a cancelled upload is never completed (and published) in the background.
     *
     * @param onProgress callback with fraction 0f..1f of bytes written
     */
    suspend fun upload(
        uri: Uri,
        host: UploadHost,
        stripMetadata: Boolean,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val fileName = FileInfoUtils.queryFileName(context, uri) ?: "file"
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val job = coroutineContext.job

        // Copy to temp once — retries reuse the same file.
        val tempFile = UriStaging.copyToTemp(
            context, uri, tempDir, "upload", "_" + FileInfoUtils.sanitizeFileName(fileName),
        )
        try {
            // Track the strip outcome instead of swallowing it: a HEIC with GPS data
            // must not upload publicly while the UI claims metadata was removed.
            val metadataStripped: Boolean? = if (stripMetadata) {
                isExifSupported(mimeType) && stripExifData(tempFile)
            } else {
                null
            }

            var lastException: Exception? = null
            for (attempt in 1..MAX_RETRIES) {
                job.ensureActive()
                try {
                    val resultUrl = uploadMultipart(job, host, tempFile, fileName, mimeType, onProgress)
                    return@withContext UploadResult(
                        url = resultUrl,
                        fileName = fileName,
                        host = host,
                        fileSizeBytes = tempFile.length(),
                        metadataStripped = metadataStripped,
                    )
                } catch (e: IOException) {
                    // A disconnect caused by cancellation surfaces as an IOException.
                    job.ensureActive()
                    lastException = e
                    Timber.w("Upload attempt %d/%d to %s failed: %s", attempt, MAX_RETRIES, host.label, e.message)
                    if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS * attempt) // Linear backoff
                }
            }
            Timber.e(lastException, "Upload to %s failed after %d attempts", host.label, MAX_RETRIES)
            throw UploadException("Upload failed after $MAX_RETRIES attempts: ${lastException?.message}", lastException)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * POST [file] as multipart/form-data to [host] and return the public URL.
     * Throws IOException for retryable failures, [UploadException] otherwise.
     */
    private fun uploadMultipart(
        job: Job,
        host: UploadHost,
        file: File,
        fileName: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String {
        val boundary = "----Embeddy${System.currentTimeMillis()}"
        val connection = (URL(host.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Embeddy/1.0")
            // Chunked streaming avoids buffering the entire request in memory
            setChunkedStreamingMode(8192)
        }
        // A blocked socket write can't see the cancellation check below;
        // disconnecting from the cancelling thread unblocks it.
        val cancelHandle = job.invokeOnCompletion { cause ->
            if (cause is CancellationException) connection.disconnect()
        }
        try {
            connection.outputStream.use { rawOutput ->
                val progressOutput = ProgressOutputStream(
                    rawOutput, file.length(), onProgress,
                    checkCancelled = { job.ensureActive() },
                )
                host.extraFields.forEach { (name, value) -> writeMultipartField(progressOutput, boundary, name, value) }
                writeMultipartFile(progressOutput, boundary, host.fileField, fileName, mimeType, file)
                progressOutput.write("--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: ""
            }
            return when (val outcome = classifyUploadResponse(code, body, host.label)) {
                is UploadOutcome.Success -> outcome.url
                is UploadOutcome.Retryable -> throw IOException(outcome.message)
                is UploadOutcome.Permanent -> throw UploadException(outcome.message)
            }
        } finally {
            cancelHandle.dispose()
            connection.disconnect()
        }
    }

    /** Write a multipart form field. */
    private fun writeMultipartField(
        output: OutputStream,
        boundary: String,
        name: String,
        value: String,
    ) {
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
            "$value\r\n"
        output.write(header.toByteArray())
    }

    /** Write a multipart file part. */
    private fun writeMultipartFile(
        output: OutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        file: File,
    ) {
        // Escape quotes and strip CR/LF — an unsanitized display name would otherwise
        // let a malicious content provider inject extra multipart headers.
        val safeFileName = fileName
            .replace("\r", "")
            .replace("\n", "")
            .replace("\"", "'")
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$safeFileName\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        output.write(header.toByteArray())
        file.inputStream().use { it.copyTo(output) }
        output.write("\r\n".toByteArray())
    }

    /**
     * Strip EXIF metadata from an image file in-place.
     * @return true when the tags were removed and saved; false on any failure,
     * so the caller can surface "metadata was NOT stripped" instead of silently
     * uploading GPS coordinates to a public host.
     */
    private fun stripExifData(file: File): Boolean {
        return try {
            val exif = ExifInterface(file)
            EXIF_TAGS_TO_STRIP.forEach { tag ->
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            Timber.w(e, "EXIF strip failed for %s", file.name)
            false
        }
    }



    /** Clean up leftover temp files (nothing in upload_temp outlives its upload). */
    fun cleanup() {
        deleteStaleEntries(tempDir, Long.MAX_VALUE)
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L // Linear backoff: 1.5s, 3s, 4.5s

        /**
         * MIME types androidx ExifInterface can both read AND write.
         *
         * heif/heic are deliberately excluded: [ExifInterface.saveAttributes]
         * only supports JPEG, PNG and WebP and throws
         * UnsupportedOperationException for HEIF — which was previously swallowed,
         * letting a HEIC with GPS coordinates upload publicly with no warning.
         */
        internal fun isExifSupported(mimeType: String): Boolean =
            mimeType in setOf("image/jpeg", "image/png", "image/webp")

        /** EXIF tags that may contain sensitive/identifying info. */
        private val EXIF_TAGS_TO_STRIP = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
        )
    }
}

/**
 * OutputStream wrapper that tracks bytes written and reports progress.
 * Throttles callbacks to avoid excessive UI updates (max every 50ms).
 */
class ProgressOutputStream(
    private val wrapped: OutputStream,
    private val totalBytes: Long,
    private val onProgress: (Float) -> Unit,
    /** Called before every write; throws to abort (cancellation). */
    private val checkCancelled: () -> Unit = {},
    /** Clock for the throttle — injectable so tests are deterministic. */
    private val clock: () -> Long = System::currentTimeMillis,
) : OutputStream() {

    private var bytesWritten: Long = 0
    private var lastCallbackTime: Long = 0

    override fun write(b: Int) {
        checkCancelled()
        wrapped.write(b)
        bytesWritten++
        reportProgress()
    }

    override fun write(b: ByteArray) {
        checkCancelled()
        wrapped.write(b)
        bytesWritten += b.size
        reportProgress()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkCancelled()
        wrapped.write(b, off, len)
        bytesWritten += len
        reportProgress()
    }

    override fun flush() = wrapped.flush()
    override fun close() = wrapped.close()

    private fun reportProgress() {
        val now = clock()
        // Throttle to max ~20 callbacks/sec to avoid UI stutter
        if (now - lastCallbackTime < 50 && bytesWritten < totalBytes) return
        lastCallbackTime = now

        val fraction = if (totalBytes > 0) {
            (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f
        onProgress(fraction)
    }
}

/**
 * Supported upload hosts and how each expects its multipart request.
 *
 * @property endpoint POST target
 * @property fileField multipart field name carrying the file
 * @property extraFields additional form fields sent before the file
 */
enum class UploadHost(
    val label: String,
    val maxSizeMb: Int,
    val endpoint: String,
    val fileField: String,
    val extraFields: List<Pair<String, String>> = emptyList(),
) {
    ZER0X0("0x0.st", 512, "https://0x0.st", "file"),
    CATBOX(
        "catbox.moe", 200, "https://catbox.moe/user/api.php", "fileToUpload",
        // Catbox requires a "reqtype" field
        extraFields = listOf("reqtype" to "fileupload"),
    ),
}

/** How an upload host's HTTP response should be treated. */
sealed interface UploadOutcome {
    /** 2xx with a URL body. */
    data class Success(val url: String) : UploadOutcome

    /** Server-side or transient failure — worth retrying. */
    data class Retryable(val message: String) : UploadOutcome

    /** Client error (bad request, too large, rejected) — retrying cannot help. */
    data class Permanent(val message: String) : UploadOutcome
}

/**
 * Classify a host response. 2xx bodies must be an http(s) URL — both hosts
 * answer with the bare link, and anything else (an HTML error page behind a
 * 200) would otherwise be shown and copied as if it were the link. 5xx and
 * 429 are retryable; every other status is permanent.
 */
fun classifyUploadResponse(code: Int, body: String, hostLabel: String): UploadOutcome {
    val trimmed = body.trim()
    return when {
        code in 200..299 && (trimmed.startsWith("https://") || trimmed.startsWith("http://")) ->
            UploadOutcome.Success(trimmed)
        code in 200..299 ->
            UploadOutcome.Retryable("$hostLabel returned an unexpected response: ${trimmed.take(120)}")
        code >= 500 || code == 429 ->
            UploadOutcome.Retryable("$hostLabel returned HTTP $code: ${trimmed.take(200)}")
        else -> UploadOutcome.Permanent("$hostLabel returned HTTP $code: ${trimmed.take(200).ifEmpty { "Unknown error" }}")
    }
}

/** Upload result containing the public URL. */
data class UploadResult(
    val url: String,
    val fileName: String,
    val host: UploadHost,
    val fileSizeBytes: Long = 0,
    /**
     * Outcome of the metadata strip: null = strip not requested,
     * true = EXIF removed, false = requested but the format is unsupported or
     * saving failed — the file may still carry GPS/EXIF data and the UI should warn.
     */
    val metadataStripped: Boolean? = null,
)

class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sealed interface for upload screen state. */
sealed interface UploadState {
    data object Idle : UploadState
    data class Ready(val fileName: String, val fileSize: Long, val uri: String) : UploadState
    data class Uploading(val fileName: String, val progress: Float = 0f) : UploadState
    data class Done(val result: UploadResult) : UploadState
    data class Error(val message: String) : UploadState
}
