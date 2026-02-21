package app.embeddy.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.embeddy.squoosh.OutputFormat
import app.embeddy.squoosh.SquooshConfig
import app.embeddy.squoosh.SquooshEngine
import app.embeddy.squoosh.SquooshState
import app.embeddy.util.FileInfoUtils
import app.embeddy.util.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SquooshViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = SquooshEngine(application)
    private val settingsRepo = SettingsRepository(application)

    private val _state = MutableStateFlow<SquooshState>(SquooshState.Idle)
    val state: StateFlow<SquooshState> = _state.asStateFlow()

    private val _config = MutableStateFlow(SquooshConfig())
    val config: StateFlow<SquooshConfig> = _config.asStateFlow()

    private var compressionJob: Job? = null

    private companion object {
        const val KEY_FILE_NAME = "sq_file_name"
        const val KEY_FILE_SIZE = "sq_file_size"
        const val KEY_URI = "sq_uri"
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { engine.cleanup() }
        // Restore saved config from DataStore
        viewModelScope.launch {
            _config.value = settingsRepo.squooshConfig.first()
        }
        // Restore Ready state from SavedStateHandle after process death
        restoreReadyState()
    }

    private fun restoreReadyState() {
        val fileName = savedState.get<String>(KEY_FILE_NAME) ?: return
        val fileSize = savedState.get<Long>(KEY_FILE_SIZE) ?: return
        val uri = savedState.get<String>(KEY_URI) ?: return
        _state.value = SquooshState.Ready(fileName = fileName, fileSize = fileSize, uri = uri)
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

    /** Persist current config to DataStore whenever it changes. */
    private fun persistConfig() {
        viewModelScope.launch {
            settingsRepo.saveSquooshConfig(_config.value)
        }
    }

    /** Handle a file picked from the image picker. Single query for name+size. */
    fun onFilePicked(uri: Uri) {
        val ctx = getApplication<Application>()
        // Take persistable permission so URI survives process death
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) { /* non-fatal */ }

        val (fileName, fileSize) = FileInfoUtils.queryFileInfo(ctx, uri)

        _state.value = SquooshState.Ready(
            fileName = fileName,
            fileSize = fileSize,
            uri = uri.toString(),
        )
        saveReadyState(fileName, fileSize, uri.toString())
    }

    /** Start compression — cancels any prior in-flight job. */
    fun compress() {
        val ready = _state.value as? SquooshState.Ready ?: return
        val uri = Uri.parse(ready.uri)

        compressionJob?.cancel()
        _state.value = SquooshState.Compressing(ready.fileName)
        compressionJob = viewModelScope.launch {
            try {
                val result = engine.compress(uri, _config.value)
                _state.value = SquooshState.Done(result, ready.fileName, ready.uri)
            } catch (e: Exception) {
                _state.value = SquooshState.Error(e.message ?: "Compression failed")
            }
        }
    }

    fun setFormat(format: OutputFormat) {
        _config.update { it.copy(format = format) }
        persistConfig()
    }

    fun setQuality(quality: Int) {
        _config.update { it.copy(quality = quality) }
        persistConfig()
    }

    fun setLossless(lossless: Boolean) {
        _config.update { it.copy(lossless = lossless) }
        persistConfig()
    }

    fun setMaxDimension(maxDim: Int) {
        _config.update { it.copy(maxDimension = maxDim) }
        persistConfig()
    }

    fun setExactWidth(width: Int) {
        _config.update { it.copy(exactWidth = width) }
        persistConfig()
    }

    fun setExactHeight(height: Int) {
        _config.update { it.copy(exactHeight = height) }
        persistConfig()
    }

    fun cancel() {
        compressionJob?.cancel()
        compressionJob = null
        _state.value = SquooshState.Idle
        clearSavedReadyState()
    }

    fun reset() {
        compressionJob?.cancel()
        compressionJob = null
        _state.value = SquooshState.Idle
        clearSavedReadyState()
    }
}
