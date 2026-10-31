package app.embeddy.viewmodel

import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionProgress
import app.embeddy.conversion.ConversionState
import app.embeddy.conversion.ConvertEngine
import app.embeddy.conversion.ConvertJob
import app.embeddy.conversion.ConvertSession
import app.embeddy.conversion.EditSpec
import app.embeddy.conversion.ErrorKind
import app.embeddy.conversion.OutputMediaFormat
import app.embeddy.conversion.Preset
import app.embeddy.conversion.SessionCodec
import app.embeddy.conversion.SourceClip
import app.embeddy.conversion.SourceKind
import app.embeddy.conversion.SourceMedia
import app.embeddy.conversion.TrimSegment
import app.embeddy.ui.isAnimatedImageFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the "grab frame" action. */
sealed interface FrameGrabState {
    data object Idle : FrameGrabState
    data object Working : FrameGrabState

    /** The frame was saved to [path] (a PNG in the app cache). */
    data class Ready(val path: String) : FrameGrabState
    data class Failed(val message: String) : FrameGrabState
}

/** Loads/saves the persisted Convert preferences (DataStore in production). */
interface ConfigStore {
    suspend fun load(): ConversionConfig
    suspend fun save(config: ConversionConfig)
}

/** Holds the encoded per-input session across process death (SavedStateHandle in production). */
interface SessionStore {
    fun read(): String?
    fun write(value: String?)
}

/**
 * The Convert tab's state machine, free of Android types so it is unit-tested
 * with a fake [ConvertEngine] (MainViewModel only wires it to Android).
 *
 * Three pieces of state:
 * - [state]  — the screen state machine (Idle → Loading → Ready → Converting → Done …)
 * - [config] — persisted encoding preferences (debounced to [ConfigStore])
 * - [edit]   — per-input edit decisions; reset on every new input, preserved
 *              across preset changes, saved with the input to [SessionStore]
 */
class ConvertController(
    private val scope: CoroutineScope,
    private val engine: ConvertEngine,
    private val configStore: ConfigStore,
    private val sessionStore: SessionStore,
    persistDebounceMs: Long = PERSIST_DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    private val initialConfig = ConversionConfig.fromPreset(Preset.DISCORD)
    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<ConversionConfig> = _config.asStateFlow()

    private val _edit = MutableStateFlow(EditSpec())
    val edit: StateFlow<EditSpec> = _edit.asStateFlow()

    /**
     * The format the user last chose. Animated-image inputs force WebP for the
     * duration of that input; this remembers MP4/Original for the next video
     * instead of persisting the forced value over the user's choice.
     */
    private var preferredFormat = initialConfig.outputFormat

    /** Latest user-made config change awaiting the debounced DataStore write. */
    private val pendingSave = MutableStateFlow<ConversionConfig?>(null)

    private val _frameGrab = MutableStateFlow<FrameGrabState>(FrameGrabState.Idle)

    /** Result of the last "grab frame" request (shown as a sheet by the UI). */
    val frameGrab: StateFlow<FrameGrabState> = _frameGrab.asStateFlow()

    private var loadJob: Job? = null
    private var conversionJob: Job? = null
    private var previewJob: Job? = null

    init {
        // Restore the picked input + edit after process death.
        SessionCodec.decode(sessionStore.read())?.let { session ->
            _state.value = ConversionState.Ready(session.source)
            _edit.value = session.edit
        }
        scope.launch {
            val loaded = configStore.load()
            // A change the user made before DataStore answered wins over the
            // stored value instead of being silently overwritten.
            if (_config.value === initialConfig) {
                preferredFormat = loaded.outputFormat
                _config.value = coerceFormat(loaded, currentSource())
            }
        }
        // Slider drags emit dozens of changes per second; write the settled value.
        scope.persistDebounced(pendingSave, persistDebounceMs) { configStore.save(it) }
    }

    /** The input currently being worked on, whatever the screen shows. */
    private fun currentSource(): SourceMedia? = currentReady()?.source

    private fun currentReady(): ConversionState.Ready? = when (val s = _state.value) {
        is ConversionState.Ready -> s
        is ConversionState.Previewing -> s.previousReady
        is ConversionState.PreviewReady -> s.previousReady
        is ConversionState.Done -> s.returnTo
        is ConversionState.SizeWarning -> s.returnTo
        is ConversionState.Error -> s.returnTo
        else -> activeReady
    }

    /** The Ready state a running conversion started from. */
    private var activeReady: ConversionState.Ready? = null

    /** Handle a picked or shared input. Supersedes any in-flight work. */
    fun onFilePicked(uri: String) {
        cancelJobs()
        _state.value = ConversionState.Loading
        loadJob = scope.launch {
            val clip: SourceClip = try {
                engine.describe(uri)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = ConversionState.Error(e.message ?: "", ErrorKind.READ_FILE)
                sessionStore.write(null)
                return@launch
            }
            showSource(SourceMedia.single(clip))
        }
    }

    /**
     * Handle several picked files: joined videos ([SourceKind.JOIN]) or an
     * image sequence ([SourceKind.IMAGE_SEQUENCE]). A single video "joined"
     * is just a single input.
     */
    fun onFilesPicked(uris: List<String>, kind: SourceKind) {
        if (uris.isEmpty()) return
        if (kind == SourceKind.SINGLE || (kind == SourceKind.JOIN && uris.size == 1)) {
            onFilePicked(uris.first())
            return
        }
        cancelJobs()
        _state.value = ConversionState.Loading
        loadJob = scope.launch {
            val clips = try {
                describeAll(uris, kind)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = ConversionState.Error(e.message ?: "", ErrorKind.READ_FILE)
                sessionStore.write(null)
                return@launch
            }
            showSource(SourceMedia(kind, clips))
        }
    }

    /** Add more clips/images to the current multi-file source. */
    fun addFiles(uris: List<String>) {
        val ready = _state.value as? ConversionState.Ready ?: return
        if (uris.isEmpty() || !canAddClips(ready.source)) return
        // A single video grows into a join: its current edit becomes the first
        // clip(s) so adding a clip never discards a trim the user already made.
        val (base, baseEdit) = if (ready.source.kind == SourceKind.SINGLE) {
            promoteToJoin(ready.source.clips.single(), _edit.value)
        } else {
            ready.source to _edit.value
        }
        val kind = base.kind
        loadJob?.cancel()
        loadJob = scope.launch {
            val added = try {
                describeAll(uris, kind)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = ready.copy(notice = e.message)
                return@launch
            }
            val clips = base.clips + added
            // New clips start untrimmed: pad the range list so indices stay aligned.
            val ranges = baseEdit.clipRanges.let { r ->
                if (r.isEmpty()) r else r + added.map { TrimSegment(0, it.durationMs) }
            }
            replaceSource(ready, SourceMedia(kind, clips), baseEdit.copy(clipRanges = ranges))
        }
    }

    /**
     * Turn a single video and its edit into the equivalent join: each kept
     * range (one for a trim, several for stitch segments) becomes a clip of the
     * same file, so the joined output starts exactly as the single edit would
     * have. The single-input trim/segment fields are cleared because ranges now
     * live per clip.
     */
    private fun promoteToJoin(clip: SourceClip, edit: EditSpec): Pair<SourceMedia, EditSpec> {
        val ranges = edit.keptRanges(clip.durationMs)
            .map { if (it.endMs == 0L) TrimSegment(it.startMs, clip.durationMs) else it }
            .ifEmpty { listOf(TrimSegment(0, clip.durationMs)) }
        return SourceMedia(SourceKind.JOIN, ranges.map { clip }) to
            edit.copy(trimStartMs = 0, trimEndMs = 0, segments = emptyList(), clipRanges = ranges)
    }

    private suspend fun describeAll(uris: List<String>, kind: SourceKind): List<SourceClip> =
        uris.map { if (kind == SourceKind.IMAGE_SEQUENCE) engine.describeImage(it) else engine.describe(it) }

    /** Move clip [from] to position [to] (join order / image order). */
    fun moveClip(from: Int, to: Int) {
        val ready = _state.value as? ConversionState.Ready ?: return
        val clips = ready.source.clips
        if (from !in clips.indices || to !in clips.indices || from == to) return
        val edit = _edit.value
        val ranges = clips.indices.map { edit.clipRange(it, clips[it].durationMs) }
        replaceSource(
            ready,
            ready.source.copy(clips = clips.toMutableList().apply { add(to, removeAt(from)) }),
            edit.copy(clipRanges = if (edit.clipRanges.isEmpty()) emptyList() else ranges.toMutableList().apply { add(to, removeAt(from)) }),
        )
    }

    /**
     * Remove clip [index]. A join left with one clip becomes a plain single
     * input whose trim is that clip's kept range; removing the last clip resets.
     */
    fun removeClip(index: Int) {
        val ready = _state.value as? ConversionState.Ready ?: return
        val clips = ready.source.clips
        if (index !in clips.indices) return
        if (clips.size == 1) {
            reset()
            return
        }
        val edit = _edit.value
        val remaining = clips.toMutableList().apply { removeAt(index) }
        val ranges = clips.indices.map { edit.clipRange(it, clips[it].durationMs) }.toMutableList().apply { removeAt(index) }
        if (ready.source.kind == SourceKind.JOIN && remaining.size == 1) {
            val range = ranges.single()
            replaceSource(
                ready,
                SourceMedia.single(remaining.single()),
                edit.copy(trimStartMs = range.startMs, trimEndMs = range.endMs, clipRanges = emptyList()),
            )
            return
        }
        replaceSource(
            ready,
            ready.source.copy(clips = remaining),
            edit.copy(clipRanges = if (edit.clipRanges.isEmpty()) emptyList() else ranges),
        )
    }

    /** Set the kept range of joined clip [index]. */
    fun setClipRange(index: Int, range: TrimSegment) {
        val ready = currentReady() ?: return
        val clips = ready.source.clips
        if (index !in clips.indices) return
        updateEdit {
            val ranges = clips.indices.map { clipRange(it, clips[it].durationMs) }.toMutableList()
            ranges[index] = range
            copy(clipRanges = ranges)
        }
    }

    private fun replaceSource(old: ConversionState.Ready, source: SourceMedia, edit: EditSpec) {
        _edit.value = edit
        _config.update { coerceFormat(it.copy(outputFormat = preferredFormat), source) }
        val ready = old.copy(source = source, notice = null)
        _state.value = ready
        saveSession(ready)
    }

    /** Save the video frame at [positionMs] of the current input as a PNG. */
    fun grabFrame(positionMs: Long) {
        val ready = currentReady() ?: return
        _frameGrab.value = FrameGrabState.Working
        scope.launch {
            _frameGrab.value = try {
                FrameGrabState.Ready(engine.grabFrame(ready.inputUri, positionMs))
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                FrameGrabState.Failed(e.message ?: "")
            }
        }
    }

    /** Close the frame-grab sheet. The PNG stays in the cache (CleanupWorker ages it out). */
    fun dismissFrameGrab() {
        _frameGrab.value = FrameGrabState.Idle
    }

    /** Make [source] the current input with a fresh edit. */
    private fun showSource(source: SourceMedia) {
        // A new input never inherits the previous file's trim/segments.
        _edit.value = EditSpec()
        _config.update { coerceFormat(it.copy(outputFormat = preferredFormat), source) }
        val ready = ConversionState.Ready(source)
        _state.value = ready
        saveSession(ready)
    }

    /** Start the conversion of the current Ready input. */
    fun startConversion() {
        val ready = _state.value as? ConversionState.Ready ?: return
        val job = ConvertJob(ready.source, _config.value, _edit.value)
        conversionJob?.cancel()
        activeReady = ready
        _state.value = ConversionState.Converting(currentQuality = job.config.startQuality)
        conversionJob = scope.launch {
            try {
                engine.convert(job).collect { progress -> onConversionProgress(progress, ready) }
            } catch (e: Exception) {
                // The engine reports failures as events; anything thrown is a
                // bug or an I/O failure it didn't anticipate — never crash for it.
                e.rethrowIfCancellation()
                _state.value = ConversionState.Error(e.message ?: "", ErrorKind.CONVERSION, returnTo = ready)
            }
        }
    }

    private fun onConversionProgress(progress: ConversionProgress, ready: ConversionState.Ready) {
        when (progress) {
            is ConversionProgress.Attempt -> _state.update { current ->
                if (current is ConversionState.Converting) {
                    current.copy(currentQuality = progress.quality, attempt = progress.attemptNumber)
                } else current
            }
            is ConversionProgress.Progress -> _state.update { current ->
                if (current is ConversionState.Converting) {
                    current.copy(
                        progress = progress.fraction,
                        currentQuality = progress.currentQuality,
                        attempt = progress.attempt,
                        elapsedMs = progress.elapsedMs,
                    )
                } else current
            }
            is ConversionProgress.SizeExceeded -> Unit // the next Attempt updates the card
            is ConversionProgress.Complete -> _state.value = ConversionState.Done(
                outputPath = progress.outputPath,
                outputSizeBytes = progress.fileSizeBytes,
                qualityUsed = progress.qualityUsed,
                inputFileName = ready.fileName,
                returnTo = ready,
            )
            is ConversionProgress.CompletedOversize -> _state.value = ConversionState.SizeWarning(
                outputPath = progress.outputPath,
                outputSizeBytes = progress.fileSizeBytes,
                targetSizeBytes = progress.targetSizeBytes,
                qualityUsed = progress.qualityUsed,
                inputFileName = ready.fileName,
                returnTo = ready,
            )
            is ConversionProgress.Failed -> _state.value =
                ConversionState.Error(progress.message, ErrorKind.CONVERSION, returnTo = ready)
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
            returnTo = warning.returnTo,
        )
    }

    /**
     * Return to the input's Ready screen (edit intact) from a result or error —
     * "Adjust & retry" and "Try again" used to drop the file and go to Idle.
     * Falls back to [reset] when there is no input to return to.
     */
    fun backToReady() {
        val ready = when (val s = _state.value) {
            is ConversionState.Done -> s.returnTo
            is ConversionState.SizeWarning -> s.returnTo
            is ConversionState.Error -> s.returnTo
            else -> null
        }
        if (ready == null) reset() else _state.value = ready.copy(notice = null)
    }

    /** Cancel an in-progress conversion and return to the input. */
    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        val ready = activeReady
        if (ready != null) _state.value = ready else reset()
    }

    /** Generate a short preview clip using the current settings. */
    fun startPreview() {
        // Drop any stale notice from a previous failed preview.
        val ready = (_state.value as? ConversionState.Ready ?: return).copy(notice = null)
        val job = ConvertJob(ready.source, _config.value, _edit.value)
        previewJob?.cancel()
        _state.value = ConversionState.Previewing(previousReady = ready)
        previewJob = scope.launch {
            try {
                engine.preview(job).collect { progress ->
                    when (progress) {
                        is ConversionProgress.Progress -> _state.update { current ->
                            if (current is ConversionState.Previewing) {
                                current.copy(progress = progress.fraction, elapsedMs = progress.elapsedMs)
                            } else current
                        }
                        is ConversionProgress.Complete -> _state.value = ConversionState.PreviewReady(
                            previewPath = progress.outputPath,
                            fileSizeBytes = progress.fileSizeBytes,
                            previousReady = ready,
                        )
                        // Keep the file, but say why the preview didn't render.
                        is ConversionProgress.Failed -> _state.value = ready.copy(notice = progress.message)
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = ready.copy(notice = e.message)
            }
        }
    }

    /** Cancel a running preview (or dismiss a finished one) and restore Ready. */
    fun cancelPreview() {
        previewJob?.cancel()
        previewJob = null
        when (val current = _state.value) {
            is ConversionState.Previewing -> _state.value = current.previousReady
            is ConversionState.PreviewReady -> dismissPreview()
            else -> Unit
        }
    }

    /** Dismiss a preview result, delete its temp file, restore Ready. */
    fun dismissPreview() {
        val preview = _state.value as? ConversionState.PreviewReady ?: return
        scope.launch { engine.deleteFile(preview.previewPath) }
        _state.value = preview.previousReady
    }

    /** Drop the input and go back to the picker. */
    fun reset() {
        cancelJobs()
        activeReady = null
        _state.value = ConversionState.Idle
        _edit.value = EditSpec()
        sessionStore.write(null)
    }

    private fun cancelJobs() {
        loadJob?.cancel()
        loadJob = null
        conversionJob?.cancel()
        conversionJob = null
        previewJob?.cancel()
        previewJob = null
    }

    // ── Preferences ──

    /**
     * Apply a preset's size/quality settings. The output format is a separate
     * choice and the edit is per-input, so both survive.
     */
    fun setPreset(preset: Preset) {
        setConfig(ConversionConfig.fromPreset(preset).copy(outputFormat = preferredFormat))
    }

    /** Update individual config fields (switches to the CUSTOM preset). */
    fun updateConfig(transform: ConversionConfig.() -> ConversionConfig) {
        setConfig(_config.value.transform().copy(preset = Preset.CUSTOM))
    }

    private fun setConfig(newConfig: ConversionConfig) {
        val source = currentSource()
        val coerced = coerceFormat(newConfig, source)
        // Only a format the input actually allows counts as a user choice.
        if (coerced.outputFormat == newConfig.outputFormat) preferredFormat = newConfig.outputFormat
        _config.value = coerced
        pendingSave.value = coerced.copy(outputFormat = preferredFormat)
    }

    // ── Edit (per input) ──

    /** Legacy single-range trim. */
    fun setTrim(startMs: Long, endMs: Long) =
        updateEdit { copy(trimStartMs = startMs, trimEndMs = endMs, segments = emptyList()) }

    /** Replace the stitch segment list. */
    fun setSegments(segments: List<TrimSegment>) = updateEdit { copy(segments = segments) }

    /** Apply [transform] to the current edit and persist it with the input. */
    fun updateEdit(transform: EditSpec.() -> EditSpec) {
        _edit.update(transform)
        currentReady()?.let { saveSession(it) }
    }

    private fun saveSession(ready: ConversionState.Ready) {
        sessionStore.write(SessionCodec.encode(ConvertSession(ready.source, _edit.value)))
    }

    companion object {
        /** How long config changes settle before being written to DataStore. */
        const val PERSIST_DEBOUNCE_MS = app.embeddy.viewmodel.PERSIST_DEBOUNCE_MS

        /**
         * Restrict the output format to what [source] can produce (see
         * [availableOutputFormats]). The coerced value is not persisted — it is
         * a per-input constraint, not a preference.
         */
        fun coerceFormat(config: ConversionConfig, source: SourceMedia?): ConversionConfig {
            if (source == null) return config
            val allowed = availableOutputFormats(source)
            return if (config.outputFormat in allowed) config else config.copy(outputFormat = allowed.first())
        }
    }
}

/**
 * Whether "Add clips" applies to [source]. Joins and image sequences grow in
 * place; a single video or GIF is promoted to a join. Animated WebP is the one
 * exception: this FFmpeg build cannot demux it, so it is pre-split into frames
 * and cannot sit in a concat graph next to real video (see JOIN_MIME_TYPES).
 */
fun canAddClips(source: SourceMedia): Boolean = when (source.kind) {
    SourceKind.JOIN, SourceKind.IMAGE_SEQUENCE -> true
    SourceKind.SINGLE -> !source.clips.single().fileName.endsWith(".webp", ignoreCase = true)
}

/**
 * Output formats [source] can be converted to.
 *
 * GIF and animated-WebP inputs have no audio and no video container worth
 * keeping, so they encode to animated images; image sequences add MP4. Real
 * videos (single or joined) can produce everything, and audio extraction is
 * offered only when some input has an audio track.
 */
fun availableOutputFormats(source: SourceMedia): List<OutputMediaFormat> {
    val animated = listOf(OutputMediaFormat.WEBP, OutputMediaFormat.GIF)
    return when {
        source.kind == SourceKind.IMAGE_SEQUENCE -> animated + OutputMediaFormat.MP4
        source.clips.all { isAnimatedImageFile(it.fileName) } -> animated
        else -> OutputMediaFormat.entries.filter { it != OutputMediaFormat.AUDIO || source.clips.any { c -> c.hasAudio } }
    }
}
