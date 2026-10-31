package app.embeddy.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.embeddy.BuildConfig
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.ConversionEngine
import app.embeddy.conversion.SourceKind
import app.embeddy.util.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android wiring for the Convert tab: builds a [ConvertController] around the
 * FFmpeg engine, DataStore preferences and SavedStateHandle, and routes share
 * intents. All state-machine logic lives in the controller.
 */
class MainViewModel(
    application: Application,
    savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)
    private val settingsRepo = SettingsRepository(application)

    /** The Convert tab's state machine. */
    val controller = ConvertController(
        scope = viewModelScope,
        engine = engine,
        configStore = object : ConfigStore {
            override suspend fun load(): ConversionConfig = settingsRepo.conversionConfig.first()
            override suspend fun save(config: ConversionConfig) = settingsRepo.saveConversionConfig(config)
        },
        sessionStore = object : SessionStore {
            override fun read(): String? = savedState.get<String>(KEY_SESSION)
            override fun write(value: String?) {
                if (value == null) savedState.remove<String>(KEY_SESSION) else savedState[KEY_SESSION] = value
            }
        },
    )

    /** One-shot signal telling the scaffold to bring the Convert tab to the front. */
    private val _navigateToConvert = NavigationSignal()
    val navigateToConvert: SharedFlow<Unit> = _navigateToConvert.signal

    /** Called by the scaffold after it has switched tabs, so the signal is not replayed. */
    fun onNavigateToConvertHandled() = _navigateToConvert.markHandled()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            engine.cleanupOldFiles()
            // A synchronous FFmpeg run purely for logcat: debug builds only.
            if (BuildConfig.DEBUG) engine.checkEncoderAvailability()
        }
    }

    /** Handle a shared intent (ACTION_SEND, ACTION_SEND_MULTIPLE or ACTION_VIEW). */
    fun onSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }.orEmpty()
            if (uris.isEmpty()) return
            controller.onFilesPicked(uris.map(Uri::toString), sharedMultipleKind(intent.type))
            _navigateToConvert.emit()
            return
        }
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
            controller.onFilePicked(uri.toString())
            // A share that arrives while another tab is open would otherwise load the
            // file into an invisible Convert tab. NavigationSignal replays the emission,
            // covering the onNewIntent-before-first-frame-collector race as well.
            _navigateToConvert.emit()
        }
    }

    private companion object {
        /**
         * Several shared images become an image sequence; anything else (videos,
         * mixed media) is joined. The intent's MIME type is the senders' common
         * type, e.g. "image/(any)" for a gallery multi-select of photos.
         */
        fun sharedMultipleKind(mimeType: String?): SourceKind =
            if (mimeType?.startsWith("image/") == true && mimeType != "image/gif") SourceKind.IMAGE_SEQUENCE else SourceKind.JOIN

        /** SavedStateHandle key for the encoded [app.embeddy.conversion.ConvertSession]. */
        const val KEY_SESSION = "convert_session"
    }
}
