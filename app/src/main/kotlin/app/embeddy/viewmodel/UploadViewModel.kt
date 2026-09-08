package app.embeddy.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.embeddy.R
import app.embeddy.upload.UploadEngine
import app.embeddy.upload.UploadHost
import app.embeddy.upload.UploadState
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.SettingsRepository
import app.embeddy.util.UploadPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = UploadEngine(application)
    private val settingsRepo = SettingsRepository(application)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _host = MutableStateFlow(UploadHost.ZER0X0)
    val host: StateFlow<UploadHost> = _host.asStateFlow()

    private val _stripMetadata = MutableStateFlow(true)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata.asStateFlow()

    private var uploadJob: Job? = null

    // SavedStateHandle keys for process death restoration (mirrors the sibling tabs)
    private companion object {
        const val KEY_FILE_NAME = "up_file_name"
        const val KEY_FILE_SIZE = "up_file_size"
        const val KEY_URI = "up_uri"
    }

    init {
        // Restore host + strip-metadata preferences from DataStore
        viewModelScope.launch {
            val prefs = settingsRepo.uploadPrefs.first()
            _host.value = prefs.host
            _stripMetadata.value = prefs.stripMetadata
        }
        // Restore Ready state from SavedStateHandle after process death
        restoreReadyState()
    }

    private fun restoreReadyState() {
        val fileName = savedState.get<String>(KEY_FILE_NAME) ?: return
        val fileSize = savedState.get<Long>(KEY_FILE_SIZE) ?: return
        val uri = savedState.get<String>(KEY_URI) ?: return
        _state.value = UploadState.Ready(fileName = fileName, fileSize = fileSize, uri = uri)
    }

    private fun saveReadyState(fileName: String, fileSize: Long, uri: String) {
        savedState[KEY_FILE_NAME] = fileName
        savedState[KEY_FILE_SIZE] = fileSize
        savedState[KEY_URI] = uri
    }

    private fun clearSavedReadyState() {
        savedState.remove<String>(KEY_FILE_NAME)
        savedState.remove<Long>(KEY_FILE_SIZE)
        savedState.remove<String>(KEY_URI)
    }

    private fun persistPrefs() {
        viewModelScope.launch {
            settingsRepo.saveUploadPrefs(UploadPrefs(_host.value, _stripMetadata.value))
        }
    }

    fun setHost(host: UploadHost) {
        _host.value = host
        persistPrefs()
    }

    fun setStripMetadata(strip: Boolean) {
        _stripMetadata.value = strip
        persistPrefs()
    }

    /** Handle a file picked from the file picker. Single query for name+size. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            // ContentResolver work off the main thread; state writes back on main.
            val (fileName, fileSize) = withContext(Dispatchers.IO) {
                // Take persistable permission so URI survives process death
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) { /* non-fatal */ }

                FileInfoUtils.queryFileInfo(ctx, uri)
            }

            _state.value = UploadState.Ready(
                fileName = fileName,
                fileSize = fileSize,
                uri = uri.toString(),
            )
            saveReadyState(fileName, fileSize, uri.toString())
        }
    }

    /** Start the upload process with progress tracking. */
    fun startUpload() {
        val ready = _state.value as? UploadState.Ready ?: return
        val uri = Uri.parse(ready.uri)
        val app = getApplication<Application>()

        // Pre-validate file size against host limit
        val maxBytes = _host.value.maxSizeMb * 1_000_000L
        if (ready.fileSize > maxBytes) {
            val sizeLabel = FileInfoUtils.formatFileSize(ready.fileSize)
            _state.value = UploadState.Error(
                app.getString(R.string.upload_size_error, sizeLabel, _host.value.label, _host.value.maxSizeMb),
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
                // Cancellation (Cancel button / superseding job) must not overwrite
                // the Idle state the canceller just set with an Error card.
                e.rethrowIfCancellation()
                _state.value = UploadState.Error(e.message ?: app.getString(R.string.upload_failed))
            }
        }
    }

    /** Cancel an in-progress upload. */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = UploadState.Idle
        clearSavedReadyState()
    }

    /** Reset to idle for a new upload. */
    fun reset() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = UploadState.Idle
        clearSavedReadyState()
        viewModelScope.launch(Dispatchers.IO) { engine.cleanup() }
    }
}
