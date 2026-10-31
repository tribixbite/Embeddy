package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.embeddy.squoosh.OutputFormat
import app.embeddy.squoosh.SquooshConfig
import app.embeddy.squoosh.SquooshEngine
import app.embeddy.squoosh.SquooshState
import app.embeddy.util.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SquooshViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = SquooshEngine(application)
    private val settingsRepo = SettingsRepository(application)
    private val pickedFile = PickedFileState(savedState, prefix = "sq")

    private val _state = MutableStateFlow<SquooshState>(SquooshState.Idle)
    val state: StateFlow<SquooshState> = _state.asStateFlow()

    private val _config = MutableStateFlow(SquooshConfig())
    val config: StateFlow<SquooshConfig> = _config.asStateFlow()

    /** Latest user change awaiting the debounced DataStore write. */
    private val pendingSave = MutableStateFlow<SquooshConfig?>(null)

    private var compressionJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { engine.cleanup() }
        viewModelScope.launch {
            val loaded = settingsRepo.squooshConfig.first()
            // A change made before DataStore answered wins over the stored value.
            if (pendingSave.value == null) _config.value = loaded
        }
        viewModelScope.persistDebounced(pendingSave) { settingsRepo.saveSquooshConfig(it) }
        // Restore Ready state from SavedStateHandle after process death
        pickedFile.restore()?.let { _state.value = SquooshState.Ready(it.fileName, it.fileSize, it.uri) }
    }

    /** Handle a file picked from the image picker. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val file = resolvePickedFile(getApplication(), uri)
            _state.value = SquooshState.Ready(fileName = file.fileName, fileSize = file.fileSize, uri = file.uri)
            pickedFile.save(file)
        }
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
                // Cancellation must not paint an Error card over the canceller's state.
                e.rethrowIfCancellation()
                _state.value = SquooshState.Error(e.message ?: "Compression failed")
            }
        }
    }

    private fun updateConfig(transform: SquooshConfig.() -> SquooshConfig) {
        _config.value = _config.value.transform()
        pendingSave.value = _config.value
    }

    fun setFormat(format: OutputFormat) = updateConfig { copy(format = format) }
    fun setQuality(quality: Int) = updateConfig { copy(quality = quality) }
    fun setLossless(lossless: Boolean) = updateConfig { copy(lossless = lossless) }
    fun setMaxDimension(maxDim: Int) = updateConfig { copy(maxDimension = maxDim) }
    fun setExactWidth(width: Int) = updateConfig { copy(exactWidth = width) }
    fun setExactHeight(height: Int) = updateConfig { copy(exactHeight = height) }

    /** Cancel any compression and return to the picker. */
    fun reset() {
        compressionJob?.cancel()
        compressionJob = null
        _state.value = SquooshState.Idle
        pickedFile.clear()
    }
}
