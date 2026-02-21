package app.embeddy.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.inspect.InspectState
import app.embeddy.inspect.MetadataEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InspectViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = MetadataEngine()

    private val _state = MutableStateFlow<InspectState>(InspectState.Idle)
    val state: StateFlow<InspectState> = _state.asStateFlow()

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private var fetchJob: Job? = null

    fun updateUrl(url: String) {
        _urlInput.value = url
    }

    /** Fetch metadata for the current URL input. */
    fun fetchMetadata() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) return

        fetchJob?.cancel()
        _state.value = InspectState.Fetching(url)
        fetchJob = viewModelScope.launch {
            val result = engine.fetchMetadata(url)
            _state.value = if (result.error != null && !result.hasData) {
                InspectState.Error(result.error)
            } else {
                InspectState.Success(result)
            }
        }
    }

    /** Inspect a local file URI for media metadata (EXIF, video/audio info). */
    fun inspectFile(uri: Uri) {
        fetchJob?.cancel()
        _state.value = InspectState.Fetching(uri.toString())
        fetchJob = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val result = engine.inspectLocalFile(ctx, uri)
                _state.value = InspectState.Success(result)
            } catch (e: Exception) {
                _state.value = InspectState.Error(e.message ?: "Failed to inspect file")
            }
        }
    }

    /** Reset to idle state. */
    fun reset() {
        fetchJob?.cancel()
        fetchJob = null
        _state.value = InspectState.Idle
        _urlInput.value = ""
    }
}
