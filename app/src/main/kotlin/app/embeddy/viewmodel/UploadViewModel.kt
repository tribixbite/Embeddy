package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.upload.UploadEngine
import app.embeddy.upload.UploadHost
import app.embeddy.upload.UploadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = UploadEngine(application)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _host = MutableStateFlow(UploadHost.ZER0X0)
    val host: StateFlow<UploadHost> = _host.asStateFlow()

    private val _stripMetadata = MutableStateFlow(true)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata.asStateFlow()

    fun setHost(host: UploadHost) {
        _host.value = host
    }

    fun setStripMetadata(strip: Boolean) {
        _stripMetadata.value = strip
    }

    /** Handle a file picked from the file picker. */
    fun onFilePicked(uri: Uri) {
        val ctx = getApplication<Application>()
        val fileName = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "file"
        val fileSize = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else null
        } ?: 0L

        _state.value = UploadState.Ready(
            fileName = fileName,
            fileSize = fileSize,
            uri = uri.toString(),
        )
    }

    /** Start the upload process. */
    fun startUpload() {
        val ready = _state.value as? UploadState.Ready ?: return
        val uri = Uri.parse(ready.uri)

        _state.value = UploadState.Uploading(ready.fileName)
        viewModelScope.launch {
            try {
                val result = engine.upload(
                    uri = uri,
                    host = _host.value,
                    stripMetadata = _stripMetadata.value,
                )
                _state.value = UploadState.Done(result)
            } catch (e: Exception) {
                _state.value = UploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** Reset to idle for a new upload. */
    fun reset() {
        _state.value = UploadState.Idle
        engine.cleanup()
    }
}
