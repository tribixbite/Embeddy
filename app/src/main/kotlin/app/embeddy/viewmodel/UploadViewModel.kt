package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.upload.UploadEngine
import app.embeddy.upload.UploadHost
import app.embeddy.upload.UploadState
import app.embeddy.util.FileInfoUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UploadViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = UploadEngine(application)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _host = MutableStateFlow(UploadHost.ZER0X0)
    val host: StateFlow<UploadHost> = _host.asStateFlow()

    private val _stripMetadata = MutableStateFlow(true)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata.asStateFlow()

    private var uploadJob: Job? = null

    fun setHost(host: UploadHost) {
        _host.value = host
    }

    fun setStripMetadata(strip: Boolean) {
        _stripMetadata.value = strip
    }

    /** Handle a file picked from the file picker. Single query for name+size. */
    fun onFilePicked(uri: Uri) {
        val ctx = getApplication<Application>()
        val (fileName, fileSize) = FileInfoUtils.queryFileInfo(ctx, uri)

        _state.value = UploadState.Ready(
            fileName = fileName,
            fileSize = fileSize,
            uri = uri.toString(),
        )
    }

    /** Start the upload process with progress tracking. */
    fun startUpload() {
        val ready = _state.value as? UploadState.Ready ?: return
        val uri = Uri.parse(ready.uri)

        // Pre-validate file size against host limit
        val maxBytes = _host.value.maxSizeMb * 1_000_000L
        if (ready.fileSize > maxBytes) {
            val sizeMb = String.format("%.1f MB", ready.fileSize / 1_000_000.0)
            _state.value = UploadState.Error(
                "File is $sizeMb but ${_host.value.label} limit is ${_host.value.maxSizeMb} MB"
            )
            return
        }

        uploadJob?.cancel()
        _state.value = UploadState.Uploading(ready.fileName, progress = 0f)
        uploadJob = viewModelScope.launch {
            try {
                val result = engine.upload(
                    uri = uri,
                    host = _host.value,
                    stripMetadata = _stripMetadata.value,
                    onProgress = { fraction ->
                        _state.update { current ->
                            if (current is UploadState.Uploading) {
                                current.copy(progress = fraction)
                            } else current
                        }
                    },
                )
                _state.value = UploadState.Done(result)
            } catch (e: Exception) {
                _state.value = UploadState.Error(e.message ?: "Upload failed")
            }
        }
    }

    /** Cancel an in-progress upload. */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = UploadState.Idle
    }

    /** Reset to idle for a new upload. */
    fun reset() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = UploadState.Idle
        engine.cleanup()
    }
}
