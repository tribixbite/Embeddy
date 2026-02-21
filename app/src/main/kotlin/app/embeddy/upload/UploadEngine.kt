package app.embeddy.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
     * @param onProgress callback with fraction 0f..1f of bytes written
     */
    suspend fun upload(
        uri: Uri,
        host: UploadHost,
        stripMetadata: Boolean,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = withContext(Dispatchers.IO) {
        val fileName = queryFileName(uri) ?: "file"
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        // Copy to temp once — retries reuse the same file
        val tempFile = copyToTemp(uri, fileName)
        if (stripMetadata && isExifSupported(mimeType)) {
            stripExifData(tempFile)
        }

        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val resultUrl = when (host) {
                    UploadHost.ZER0X0 -> uploadTo0x0(tempFile, fileName, mimeType, onProgress)
                    UploadHost.CATBOX -> uploadToCatbox(tempFile, fileName, mimeType, onProgress)
                }

                val fileSize = tempFile.length()
                tempFile.delete()

                return@withContext UploadResult(
                    url = resultUrl,
                    fileName = fileName,
                    host = host,
                    fileSizeBytes = fileSize,
                )
            } catch (e: IOException) {
                lastException = e
                Timber.w("Upload attempt %d/%d to %s failed: %s", attempt, MAX_RETRIES, host.label, e.message)
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt) // Linear backoff
                }
            } catch (e: UploadException) {
                // Server returned a non-retryable error (4xx) — don't retry
                tempFile.delete()
                Timber.e(e, "Upload to %s failed (non-retryable)", host.label)
                throw e
            }
        }

        tempFile.delete()
        Timber.e(lastException, "Upload to %s failed after %d attempts", host.label, MAX_RETRIES)
        throw UploadException("Upload failed after $MAX_RETRIES attempts: ${lastException?.message}", lastException)
    }

    /** POST to https://0x0.st with multipart/form-data. */
    private fun uploadTo0x0(
        file: File,
        fileName: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String {
        val boundary = "----Embeddy${System.currentTimeMillis()}"
        val url = URL("https://0x0.st")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Embeddy/1.0")
            // Enable chunked streaming to avoid buffering entire request in memory
            setChunkedStreamingMode(8192)
        }

        val totalBytes = file.length()

        connection.outputStream.use { rawOutput ->
            val progressOutput = ProgressOutputStream(rawOutput, totalBytes, onProgress)
            writeMultipartFile(progressOutput, boundary, "file", fileName, mimeType, file)
            progressOutput.write("--$boundary--\r\n".toByteArray())
        }

        return handleResponse(connection, "0x0.st")
    }

    /** POST to https://catbox.moe/user/api.php with multipart/form-data. */
    private fun uploadToCatbox(
        file: File,
        fileName: String,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): String {
        val boundary = "----Embeddy${System.currentTimeMillis()}"
        val url = URL("https://catbox.moe/user/api.php")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Embeddy/1.0")
            setChunkedStreamingMode(8192)
        }

        val totalBytes = file.length()

        connection.outputStream.use { rawOutput ->
            val progressOutput = ProgressOutputStream(rawOutput, totalBytes, onProgress)
            // Catbox requires a "reqtype" field
            writeMultipartField(progressOutput, boundary, "reqtype", "fileupload")
            writeMultipartFile(progressOutput, boundary, "fileToUpload", fileName, mimeType, file)
            progressOutput.write("--$boundary--\r\n".toByteArray())
        }

        return handleResponse(connection, "catbox.moe")
    }

    /**
     * Read HTTP response: 2xx returns body, 5xx throws IOException (retryable),
     * 4xx throws UploadException (non-retryable).
     */
    private fun handleResponse(connection: HttpURLConnection, hostName: String): String {
        val responseCode = connection.responseCode
        val responseBody = try {
            if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText().trim()
            } else {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                if (responseCode >= 500) {
                    // Server error — retryable via IOException
                    throw IOException("$hostName returned HTTP $responseCode: $error")
                }
                // Client error (4xx) — non-retryable
                throw UploadException("$hostName returned HTTP $responseCode: $error")
            }
        } finally {
            connection.disconnect()
        }
        return responseBody
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
        val header = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $mimeType\r\n\r\n"
        output.write(header.toByteArray())
        file.inputStream().use { it.copyTo(output) }
        output.write("\r\n".toByteArray())
    }

    /** Strip EXIF metadata from an image file in-place. */
    private fun stripExifData(file: File) {
        try {
            val exif = ExifInterface(file)
            EXIF_TAGS_TO_STRIP.forEach { tag ->
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
        } catch (_: Exception) {
            // Non-fatal — file may not support EXIF
        }
    }

    /** Check if the MIME type supports EXIF metadata. */
    private fun isExifSupported(mimeType: String): Boolean =
        mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic")

    private fun copyToTemp(uri: Uri, fileName: String): File {
        val tempFile = File(tempDir, "upload_${System.currentTimeMillis()}_$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw UploadException("Cannot read file")
        return tempFile
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    /** Clean up leftover temp files. */
    fun cleanup() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L // Linear backoff: 1.5s, 3s, 4.5s

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
) : OutputStream() {

    private var bytesWritten: Long = 0
    private var lastCallbackTime: Long = 0

    override fun write(b: Int) {
        wrapped.write(b)
        bytesWritten++
        reportProgress()
    }

    override fun write(b: ByteArray) {
        wrapped.write(b)
        bytesWritten += b.size
        reportProgress()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        wrapped.write(b, off, len)
        bytesWritten += len
        reportProgress()
    }

    override fun flush() = wrapped.flush()
    override fun close() = wrapped.close()

    private fun reportProgress() {
        val now = System.currentTimeMillis()
        // Throttle to max ~20 callbacks/sec to avoid UI stutter
        if (now - lastCallbackTime < 50 && bytesWritten < totalBytes) return
        lastCallbackTime = now

        val fraction = if (totalBytes > 0) {
            (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f
        onProgress(fraction)
    }
}

/** Supported upload hosts. */
enum class UploadHost(val label: String, val maxSizeMb: Int) {
    ZER0X0("0x0.st", 512),
    CATBOX("catbox.moe", 200),
}

/** Upload result containing the public URL. */
data class UploadResult(
    val url: String,
    val fileName: String,
    val host: UploadHost,
    val fileSizeBytes: Long = 0,
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
