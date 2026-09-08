package app.embeddy.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.embeddy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Returns a click handler `(path, mimeType) -> Unit` that saves a produced file
 * into the user's Downloads.
 *
 * Shared by the Convert and Squoosh result cards so both get the same
 * guarantees:
 * - the multi-MB copy runs on [Dispatchers.IO] (the old click-handler copies
 *   froze the frame and risked ANR),
 * - success AND failure surface as a Toast (failures used to be silent),
 * - a failed MediaStore write deletes its row instead of leaking an
 *   `IS_PENDING = 1` ghost entry,
 * - `MediaStore.Downloads` (API 29+) is version-guarded — on API 26-28 those
 *   fields don't exist (`NoSuchFieldError` crash before this guard); the
 *   fallback writes to the public Downloads directory, requesting the legacy
 *   WRITE_EXTERNAL_STORAGE permission at runtime when needed.
 */
@Composable
fun rememberDownloadsSaver(): (path: String, mimeType: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val (path, mime) = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (granted) {
            scope.launch { saveWithFeedback(context, path, mime) }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.save_permission_denied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    return { path, mimeType ->
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingSave = path to mimeType
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { saveWithFeedback(context, path, mimeType) }
        }
    }
}

/** Copy on IO, then toast the outcome on the caller's (main) dispatcher. */
private suspend fun saveWithFeedback(context: Context, path: String, mimeType: String) {
    val saved = withContext(Dispatchers.IO) {
        try {
            saveToDownloads(context, path, mimeType)
        } catch (e: Exception) {
            Timber.w(e, "Save to Downloads failed for %s", path)
            false
        }
    }
    val message = if (saved) R.string.saved_to_downloads else R.string.save_failed
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}

private fun saveToDownloads(context: Context, path: String, mimeType: String): Boolean {
    val file = File(path)
    if (!file.isFile) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveViaMediaStore(context, file, mimeType)
    } else {
        saveToPublicDownloads(context, file)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveViaMediaStore(context: Context, file: File, mimeType: String): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        val stream = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("openOutputStream returned null for $uri")
        stream.use { output -> file.inputStream().use { it.copyTo(output) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        // Remove the half-written row so no permanently-pending ghost entry remains.
        resolver.delete(uri, null, null)
        Timber.w(e, "MediaStore Downloads write failed")
        false
    }
}

/** API 26-28 fallback: MediaStore.Downloads does not exist below Q. */
private fun saveToPublicDownloads(context: Context, file: File): Boolean {
    @Suppress("DEPRECATION") // the non-deprecated API is the >=Q branch above
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!downloadsDir.isDirectory && !downloadsDir.mkdirs()) return false
    val dest = uniqueDestination(downloadsDir, file.name)
    return try {
        file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        // Index the file so Files/Downloads apps show it immediately.
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        true
    } catch (e: Exception) {
        dest.delete()
        Timber.w(e, "Legacy Downloads write failed")
        false
    }
}

/** Avoid clobbering an existing download with the same display name. */
private fun uniqueDestination(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', missingDelimiterValue = "")
    var counter = 1
    while (candidate.exists()) {
        val suffixed = if (ext.isEmpty()) "$base ($counter)" else "$base ($counter).$ext"
        candidate = File(dir, suffixed)
        counter++
    }
    return candidate
}
