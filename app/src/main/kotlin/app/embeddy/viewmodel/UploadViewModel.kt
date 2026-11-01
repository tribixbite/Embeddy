package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
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

class UploadViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = UploadEngine(application)
    private val settingsRepo = SettingsRepository(application)
    private val pickedFile = PickedFileState(savedState, prefix = "up")

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    private val _host = MutableStateFlow(UploadHost.ZER0X0)
    val host: StateFlow<UploadHost> = _host.asStateFlow()

    private val _stripMetadata = MutableStateFlow(true)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata.asStateFlow()

    private var uploadJob: Job? = null

    /**
     * Sweeps temp copies left behind by a process that died mid-upload. Runs
     * once per ViewModel, and every upload waits for it first: sweeping the
     * directory while an upload is staging into it would delete that upload's
     * file mid-transfer. (Normal uploads delete their own temp file, including
     * on cancel, so nothing else needs sweeping.)
     */
    private val leftoverCleanup: Job = viewModelScope.launch(Dispatchers.IO) { engine.cleanup() }

    /** The Ready state the current upload started from (Cancel returns to it). */
    private var uploadingFrom: UploadState.Ready? = null

    init {
        // Restore host + strip-metadata preferences from DataStore
        viewModelScope.launch {
            val prefs = settingsRepo.uploadPrefs.first()
            _host.value = prefs.host
            _stripMetadata.value = prefs.stripMetadata
        }
        // Restore Ready state from SavedStateHandle after process death
        pickedFile.restore()?.let { _state.value = UploadState.Ready(it.fileName, it.fileSize, it.uri) }
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

    /** Handle a file picked from the file picker. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val file = resolvePickedFile(getApplication(), uri)
            _state.value = UploadState.Ready(fileName = file.fileName, fileSize = file.fileSize, uri = file.uri)
            pickedFile.save(file)
        }
    }

    /** Start the upload process with progress tracking. */
    fun startUpload() {
        val ready = _state.value as? UploadState.Ready ?: return
        val uri = ready.uri.toUri()
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
        uploadingFrom = ready
        _state.value = UploadState.Uploading(ready.fileName, progress = 0f)
        uploadJob = viewModelScope.launch {
            try {
                leftoverCleanup.join()
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

    /** Cancel an in-progress upload; the picked file stays selected. */
    fun cancelUpload() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = uploadingFrom ?: UploadState.Idle
    }

    /** Reset to idle for a new upload. */
    fun reset() {
        uploadJob?.cancel()
        uploadJob = null
        _state.value = UploadState.Idle
        pickedFile.clear()
    }
}
