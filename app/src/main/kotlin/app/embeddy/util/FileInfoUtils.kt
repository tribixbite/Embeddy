package app.embeddy.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Shared file info queries — avoids duplicating ContentResolver boilerplate across ViewModels. */
object FileInfoUtils {

    /** Query the display name of a content:// URI. */
    fun queryFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    }

    /** Query the byte size of a content:// URI. */
    fun queryFileSize(context: Context, uri: Uri): Long? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else null
        }
    }

    /** Query both name and size in a single ContentResolver query. */
    fun queryFileInfo(context: Context, uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: "file"
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        return name to size
    }

    /** Format bytes to human-readable string. */
    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
