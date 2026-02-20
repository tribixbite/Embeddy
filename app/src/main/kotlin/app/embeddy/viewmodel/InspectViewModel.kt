package app.embeddy.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.inspect.InspectState
import app.embeddy.inspect.MetadataEngine
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

    fun updateUrl(url: String) {
        _urlInput.value = url
    }

    /** Fetch metadata for the current URL input. */
    fun fetchMetadata() {
        val url = _urlInput.value.trim()
        if (url.isBlank()) return

        _state.value = InspectState.Fetching(url)
        viewModelScope.launch {
            val result = engine.fetchMetadata(url)
            _state.value = if (result.error != null && !result.hasData) {
                InspectState.Error(result.error)
            } else {
                InspectState.Success(result)
            }
        }
    }

    /** Reset to idle state. */
    fun reset() {
        _state.value = InspectState.Idle
        _urlInput.value = ""
    }
}
