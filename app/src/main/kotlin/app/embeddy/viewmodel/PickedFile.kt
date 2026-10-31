package app.embeddy.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.embeddy.util.FileInfoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long preference changes settle before being written to DataStore. */
const val PERSIST_DEBOUNCE_MS = 500L

/** A file picked through SAF: its URI plus the provider's name and size. */
data class PickedFile(val uri: String, val fileName: String, val fileSize: Long)

/**
 * Take a persistable read grant when the provider offers one (so the URI
 * survives process death), then read the display name and size — on IO.
 * Shared by the Squoosh and Upload tabs, which each carried a copy.
 */
suspend fun resolvePickedFile(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Not every provider grants persistable access — non-fatal.
    }
    val (name, size) = FileInfoUtils.queryFileInfo(context, uri)
    PickedFile(uri.toString(), name, size)
}

/**
 * Saves the picked file of one tab in [SavedStateHandle] under
 * `<prefix>_uri` / `<prefix>_file_name` / `<prefix>_file_size` so the Ready
 * screen survives process death. (Key names match the pre-refactor ones.)
 */
class PickedFileState(private val savedState: SavedStateHandle, prefix: String) {
    private val keyUri = "${prefix}_uri"
    private val keyName = "${prefix}_file_name"
    private val keySize = "${prefix}_file_size"

    /** The saved file, or null when none was saved (or the record is incomplete). */
    fun restore(): PickedFile? {
        val uri = savedState.get<String>(keyUri) ?: return null
        val name = savedState.get<String>(keyName) ?: return null
        val size = savedState.get<Long>(keySize) ?: return null
        return PickedFile(uri, name, size)
    }

    fun save(file: PickedFile) {
        savedState[keyUri] = file.uri
        savedState[keyName] = file.fileName
        savedState[keySize] = file.fileSize
    }

    fun clear() {
        savedState.remove<String>(keyUri)
        savedState.remove<String>(keyName)
        savedState.remove<Long>(keySize)
    }
}

/**
 * Write each settled value of [changes] with [save], [debounceMs] after the
 * last emission — slider drags otherwise wrote DataStore on every frame.
 * Null emissions (the initial "nothing changed yet") are skipped.
 */
@OptIn(FlowPreview::class)
fun <T : Any> CoroutineScope.persistDebounced(
    changes: Flow<T?>,
    debounceMs: Long = PERSIST_DEBOUNCE_MS,
    save: suspend (T) -> Unit,
) {
    launch { changes.filterNotNull().debounce(debounceMs).collect { save(it) } }
}
