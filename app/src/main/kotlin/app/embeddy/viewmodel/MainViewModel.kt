package app.embeddy.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionEngine
import app.embeddy.conversion.ConversionProgress
import app.embeddy.conversion.ConversionState
import app.embeddy.conversion.Preset
import app.embeddy.util.FileInfoUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)

    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    private val _config = MutableStateFlow(ConversionConfig.fromPreset(Preset.DISCORD))
    val config: StateFlow<ConversionConfig> = _config.asStateFlow()

    private var conversionJob: Job? = null

    init {
        engine.cleanupOldFiles()
    }

    /** Handle a file picked via the system file picker. */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val (fileName, fileSize) = FileInfoUtils.queryFileInfo(ctx, uri)
                val info = engine.probeInput(uri)

                _state.value = ConversionState.Ready(
                    inputUri = uri.toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    durationMs = info.durationMs,
                    width = info.width,
                    height = info.height,
                )
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

    /** Start the conversion process. */
    fun startConversion() {
        val ready = _state.value as? ConversionState.Ready ?: return
        val currentConfig = _config.value
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

    /** Set trim points (in milliseconds from start of video). */
    fun setTrim(startMs: Long, endMs: Long) {
        _config.update { it.copy(trimStartMs = startMs, trimEndMs = endMs, preset = Preset.CUSTOM) }
    }

    /** Cancel an in-progress conversion. */
    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        _state.value = ConversionState.Idle
    }

    /** Reset to idle state for a new conversion. */
    fun reset() {
        conversionJob?.cancel()
        conversionJob = null
        _state.value = ConversionState.Idle
    }

    /** Update the active preset, reconfiguring all settings. */
    fun setPreset(preset: Preset) {
        _config.value = ConversionConfig.fromPreset(preset)
    }

    /** Update individual config fields (switches to CUSTOM preset). */
    fun updateConfig(transform: ConversionConfig.() -> ConversionConfig) {
        _config.update { it.transform().copy(preset = Preset.CUSTOM) }
    }
}
