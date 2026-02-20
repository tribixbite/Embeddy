package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.squoosh.OutputFormat
import app.embeddy.squoosh.SquooshConfig
import app.embeddy.squoosh.SquooshEngine
import app.embeddy.squoosh.SquooshState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SquooshViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = SquooshEngine(application)

    private val _state = MutableStateFlow<SquooshState>(SquooshState.Idle)
    val state: StateFlow<SquooshState> = _state.asStateFlow()

    private val _config = MutableStateFlow(SquooshConfig())
    val config: StateFlow<SquooshConfig> = _config.asStateFlow()

    private var compressionJob: Job? = null

    init {
        engine.cleanup()
    }

    /** Handle a file picked from the image picker. */
    fun onFilePicked(uri: Uri) {
        val ctx = getApplication<Application>()
        val fileName = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: "image"
        val fileSize = ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && idx >= 0) cursor.getLong(idx) else null
        } ?: 0L

        _state.value = SquooshState.Ready(
            fileName = fileName,
            fileSize = fileSize,
            uri = uri.toString(),
        )
    }

    /** Start compression. */
    fun compress() {
        val ready = _state.value as? SquooshState.Ready ?: return
        val uri = Uri.parse(ready.uri)

        _state.value = SquooshState.Compressing(ready.fileName)
        compressionJob = viewModelScope.launch {
            try {
                val result = engine.compress(uri, _config.value)
                _state.value = SquooshState.Done(result, ready.fileName)
            } catch (e: Exception) {
                _state.value = SquooshState.Error(e.message ?: "Compression failed")
            }
        }
    }

    fun setFormat(format: OutputFormat) {
        _config.update { it.copy(format = format) }
    }

    fun setQuality(quality: Int) {
        _config.update { it.copy(quality = quality) }
    }

    fun setEffort(effort: Int) {
        _config.update { it.copy(effort = effort) }
    }

    fun setLossless(lossless: Boolean) {
        _config.update { it.copy(lossless = lossless) }
    }

    fun setMaxDimension(maxDim: Int) {
        _config.update { it.copy(maxDimension = maxDim) }
    }

    fun cancel() {
        compressionJob?.cancel()
        compressionJob = null
        _state.value = SquooshState.Idle
    }

    fun reset() {
        compressionJob?.cancel()
        compressionJob = null
        _state.value = SquooshState.Idle
    }
}
