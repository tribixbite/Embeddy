package app.embeddy.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionEngine
import app.embeddy.conversion.ConversionProgress
import app.embeddy.conversion.ConversionState
import app.embeddy.conversion.Preset
import app.embeddy.conversion.TrimSegment
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

class MainViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)
    private val settingsRepo = SettingsRepository(application)

    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    private val _config = MutableStateFlow(ConversionConfig.fromPreset(Preset.DISCORD))
    val config: StateFlow<ConversionConfig> = _config.asStateFlow()

    private var conversionJob: Job? = null

    // SavedStateHandle keys for process death restoration
    private companion object {
        const val KEY_INPUT_URI = "ss_input_uri"
        const val KEY_FILE_NAME = "ss_file_name"
        const val KEY_FILE_SIZE = "ss_file_size"
        const val KEY_DURATION_MS = "ss_duration_ms"
        const val KEY_WIDTH = "ss_width"
        const val KEY_HEIGHT = "ss_height"
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { engine.cleanupOldFiles() }
        // Restore saved config from DataStore
        viewModelScope.launch {
            _config.value = settingsRepo.conversionConfig.first()
        }
        // Restore Ready state from SavedStateHandle if process was killed
        restoreReadyState()
    }

    /** Restore the Ready state from SavedStateHandle after process death. */
    private fun restoreReadyState() {
        val uri = savedState.get<String>(KEY_INPUT_URI) ?: return
        val fileName = savedState.get<String>(KEY_FILE_NAME) ?: return
        val fileSize = savedState.get<Long>(KEY_FILE_SIZE) ?: return
        _state.value = ConversionState.Ready(
            inputUri = uri,
            fileName = fileName,
            fileSize = fileSize,
            durationMs = savedState.get<Long>(KEY_DURATION_MS) ?: 0L,
            width = savedState.get<Int>(KEY_WIDTH) ?: 0,
            height = savedState.get<Int>(KEY_HEIGHT) ?: 0,
        )
    }

    /** Persist Ready state fields to SavedStateHandle. */
    private fun saveReadyState(ready: ConversionState.Ready) {
        savedState[KEY_INPUT_URI] = ready.inputUri
        savedState[KEY_FILE_NAME] = ready.fileName
        savedState[KEY_FILE_SIZE] = ready.fileSize
        savedState[KEY_DURATION_MS] = ready.durationMs
        savedState[KEY_WIDTH] = ready.width
        savedState[KEY_HEIGHT] = ready.height
    }

    /** Clear saved Ready state when leaving that state. */
    private fun clearSavedReadyState() {
        savedState.remove<String>(KEY_INPUT_URI)
        savedState.remove<String>(KEY_FILE_NAME)
        savedState.remove<Long>(KEY_FILE_SIZE)
        savedState.remove<Long>(KEY_DURATION_MS)
        savedState.remove<Int>(KEY_WIDTH)
        savedState.remove<Int>(KEY_HEIGHT)
    }

    /** Persist current config to DataStore. */
    private fun persistConfig() {
        viewModelScope.launch {
            settingsRepo.saveConversionConfig(_config.value)
        }
    }

    /** Handle a file picked via the system file picker. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                // Take persistable permission so URI survives process death
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Not all URIs support persistable permission — non-fatal
                }

                val (fileName, fileSize) = FileInfoUtils.queryFileInfo(ctx, uri)
                val info = engine.probeInput(uri)

                val ready = ConversionState.Ready(
                    inputUri = uri.toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    durationMs = info.durationMs,
                    width = info.width,
                    height = info.height,
                )
                _state.value = ready
                saveReadyState(ready)
            } catch (e: Exception) {
                _state.value = ConversionState.Error("Failed to read file: ${e.message}")
            }
        }
    }

    /** Handle a shared intent (ACTION_SEND or ACTION_VIEW). */
    fun onSharedIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            onFilePicked(uri)
        }
    }

    /** Merge overlapping segments to prevent duplicate frames in FFmpeg concat. */
    private fun mergeOverlappingSegments(segments: List<TrimSegment>): List<TrimSegment> {
        if (segments.size <= 1) return segments
        val sorted = segments.sortedBy { it.startMs }
        return sorted.fold(mutableListOf<TrimSegment>()) { acc, seg ->
            val last = acc.lastOrNull()
            if (last != null && seg.startMs <= last.endMs) {
                // Overlapping or adjacent — merge by extending end
                acc[acc.lastIndex] = TrimSegment(last.startMs, maxOf(last.endMs, seg.endMs))
            } else {
                acc.add(seg)
            }
            acc
        }
    }

    /** Start the conversion process. */
    fun startConversion() {
        val ready = _state.value as? ConversionState.Ready ?: return
        // Merge any overlapping segments before building FFmpeg command
        val currentConfig = _config.value.let { cfg ->
            if (cfg.segments.size > 1) cfg.copy(segments = mergeOverlappingSegments(cfg.segments))
            else cfg
        }
        val uri = Uri.parse(ready.inputUri)
        val baseName = ready.fileName.substringBeforeLast(".")

        conversionJob?.cancel()
        conversionJob = viewModelScope.launch {
            _state.value = ConversionState.Converting()

            engine.convert(uri, currentConfig, baseName).collect { progress ->
                when (progress) {
                    is ConversionProgress.Attempt -> {
                        _state.update { current ->
                            if (current is ConversionState.Converting) {
                                current.copy(
                                    currentQuality = progress.quality,
                                    attempt = progress.attemptNumber,
                                )
                            } else current
                        }
                    }

                    is ConversionProgress.Progress -> {
                        _state.update { current ->
                            if (current is ConversionState.Converting) {
                                current.copy(
                                    progress = progress.fraction,
                                    currentQuality = progress.currentQuality,
                                    attempt = progress.attempt,
                                    elapsedMs = progress.elapsedMs,
                                )
                            } else current
                        }
                    }

                    is ConversionProgress.SizeExceeded -> {
                        // Just let Converting state continue with updated attempt
                    }

                    is ConversionProgress.Complete -> {
                        _state.value = ConversionState.Done(
                            outputPath = progress.outputPath,
                            outputSizeBytes = progress.fileSizeBytes,
                            qualityUsed = progress.qualityUsed,
                            inputFileName = ready.fileName,
                        )
                    }

                    is ConversionProgress.CompletedOversize -> {
                        _state.value = ConversionState.SizeWarning(
                            outputPath = progress.outputPath,
                            outputSizeBytes = progress.fileSizeBytes,
                            targetSizeBytes = progress.targetSizeBytes,
                            qualityUsed = progress.qualityUsed,
                            inputFileName = ready.fileName,
                        )
                    }

                    is ConversionProgress.Failed -> {
                        _state.value = ConversionState.Error(progress.message)
                    }
                }
            }
        }
    }

    /** Accept the oversize output as-is. */
    fun acceptOversize() {
        val warning = _state.value as? ConversionState.SizeWarning ?: return
        _state.value = ConversionState.Done(
            outputPath = warning.outputPath,
            outputSizeBytes = warning.outputSizeBytes,
            qualityUsed = warning.qualityUsed,
            inputFileName = warning.inputFileName,
        )
    }

    /** Set trim points (in milliseconds from start of video). Legacy single-trim API. */
    fun setTrim(startMs: Long, endMs: Long) {
        _config.update { it.copy(trimStartMs = startMs, trimEndMs = endMs, segments = emptyList(), preset = Preset.CUSTOM) }
        // Don't persist trim values — they're per-file, not user preferences
    }

    /** Replace the segments list (for multi-segment stitching). */
    fun setSegments(segments: List<TrimSegment>) {
        _config.update { it.copy(segments = segments, preset = Preset.CUSTOM) }
    }

    /** Add a new segment to the keep list. */
    fun addSegment(segment: TrimSegment) {
        _config.update { current ->
            val newSegments = (current.segments + segment).sortedBy { it.startMs }
            current.copy(segments = newSegments, preset = Preset.CUSTOM)
        }
    }

    /** Remove a segment by index (bounds-checked). */
    fun removeSegment(index: Int) {
        _config.update { current ->
            if (index !in current.segments.indices) return@update current
            val newSegments = current.segments.toMutableList().apply { removeAt(index) }
            current.copy(segments = newSegments, preset = Preset.CUSTOM)
        }
    }

    /** Update a specific segment at the given index. */
    fun updateSegment(index: Int, segment: TrimSegment) {
        _config.update { current ->
            val newSegments = current.segments.toMutableList().apply { set(index, segment) }
            current.copy(segments = newSegments.sortedBy { it.startMs }, preset = Preset.CUSTOM)
        }
    }

    /** Cancel an in-progress conversion. */
    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        _state.value = ConversionState.Idle
        clearSavedReadyState()
    }

    /** Reset to idle state for a new conversion. */
    fun reset() {
        conversionJob?.cancel()
        conversionJob = null
        _state.value = ConversionState.Idle
        clearSavedReadyState()
    }

    /** Update the active preset, reconfiguring all settings. */
    fun setPreset(preset: Preset) {
        _config.value = ConversionConfig.fromPreset(preset)
        persistConfig()
    }

    /** Update individual config fields (switches to CUSTOM preset). */
    fun updateConfig(transform: ConversionConfig.() -> ConversionConfig) {
        _config.update { it.transform().copy(preset = Preset.CUSTOM) }
        persistConfig()
    }
}
