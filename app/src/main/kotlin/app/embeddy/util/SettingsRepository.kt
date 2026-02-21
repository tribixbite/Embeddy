package app.embeddy.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.embeddy.conversion.ColorSpace
import app.embeddy.conversion.ConversionConfig
import app.embeddy.conversion.DitherMode
import app.embeddy.conversion.Preset
import app.embeddy.squoosh.OutputFormat
import app.embeddy.squoosh.SquooshConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single DataStore instance for the app, scoped via extension property. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "embeddy_settings")

/**
 * Persists user preferences for Squoosh and Convert tabs via Preferences DataStore.
 * Stores simple types (Int, Boolean, String) — enums saved as their name.
 */
class SettingsRepository(private val context: Context) {

    // ── Squoosh preference keys ──
    private object SquooshKeys {
        val FORMAT = stringPreferencesKey("squoosh_format")
        val QUALITY = intPreferencesKey("squoosh_quality")
        val LOSSLESS = booleanPreferencesKey("squoosh_lossless")
        val MAX_DIMENSION = intPreferencesKey("squoosh_max_dimension")
        val EXACT_WIDTH = intPreferencesKey("squoosh_exact_width")
        val EXACT_HEIGHT = intPreferencesKey("squoosh_exact_height")
    }

    // ── Convert preference keys ──
    private object ConvertKeys {
        val PRESET = stringPreferencesKey("convert_preset")
        val MAX_DIMENSION = intPreferencesKey("convert_max_dimension")
        val FPS = intPreferencesKey("convert_fps")
        val QUALITY = intPreferencesKey("convert_quality")
        val TARGET_SIZE = stringPreferencesKey("convert_target_size") // stored as Long string
        val SHARPEN = booleanPreferencesKey("convert_sharpen")
        val COMPRESSION_LEVEL = intPreferencesKey("convert_compression_level")
        val DENOISE = intPreferencesKey("convert_denoise")
        val COLOR_SPACE = stringPreferencesKey("convert_color_space")
        val DITHER_MODE = stringPreferencesKey("convert_dither_mode")
        val KEYFRAME_INTERVAL = intPreferencesKey("convert_keyframe_interval")
    }

    /** Flow of saved SquooshConfig, falls back to defaults for missing keys. */
    val squooshConfig: Flow<SquooshConfig> = context.dataStore.data.map { prefs ->
        SquooshConfig(
            format = prefs[SquooshKeys.FORMAT]?.let { name ->
                OutputFormat.entries.firstOrNull { it.name == name }
            } ?: OutputFormat.WEBP,
            quality = prefs[SquooshKeys.QUALITY] ?: 80,
            lossless = prefs[SquooshKeys.LOSSLESS] ?: false,
            maxDimension = prefs[SquooshKeys.MAX_DIMENSION] ?: 0,
            exactWidth = prefs[SquooshKeys.EXACT_WIDTH] ?: 0,
            exactHeight = prefs[SquooshKeys.EXACT_HEIGHT] ?: 0,
        )
    }

    /** Flow of saved ConversionConfig, falls back to defaults for missing keys. */
    val conversionConfig: Flow<ConversionConfig> = context.dataStore.data.map { prefs ->
        val preset = prefs[ConvertKeys.PRESET]?.let { name ->
            Preset.entries.firstOrNull { it.name == name }
        } ?: Preset.DISCORD
        ConversionConfig(
            preset = preset,
            maxDimension = prefs[ConvertKeys.MAX_DIMENSION] ?: preset.maxDimension,
            fps = prefs[ConvertKeys.FPS] ?: preset.fps,
            startQuality = prefs[ConvertKeys.QUALITY] ?: preset.startQuality,
            targetSizeBytes = prefs[ConvertKeys.TARGET_SIZE]?.toLongOrNull()
                ?: preset.targetSizeBytes,
            sharpen = prefs[ConvertKeys.SHARPEN] ?: preset.sharpen,
            compressionLevel = prefs[ConvertKeys.COMPRESSION_LEVEL] ?: 4,
            denoiseStrength = prefs[ConvertKeys.DENOISE] ?: 0,
            colorSpace = prefs[ConvertKeys.COLOR_SPACE]?.let { name ->
                ColorSpace.entries.firstOrNull { it.name == name }
            } ?: ColorSpace.AUTO,
            ditherMode = prefs[ConvertKeys.DITHER_MODE]?.let { name ->
                DitherMode.entries.firstOrNull { it.name == name }
            } ?: DitherMode.NONE,
            keyframeInterval = prefs[ConvertKeys.KEYFRAME_INTERVAL] ?: 0,
        )
    }

    /** Persist the current Squoosh settings. */
    suspend fun saveSquooshConfig(config: SquooshConfig) {
        context.dataStore.edit { prefs ->
            prefs[SquooshKeys.FORMAT] = config.format.name
            prefs[SquooshKeys.QUALITY] = config.quality
            prefs[SquooshKeys.LOSSLESS] = config.lossless
            prefs[SquooshKeys.MAX_DIMENSION] = config.maxDimension
            prefs[SquooshKeys.EXACT_WIDTH] = config.exactWidth
            prefs[SquooshKeys.EXACT_HEIGHT] = config.exactHeight
        }
    }

    /** Persist the current Convert settings. */
    suspend fun saveConversionConfig(config: ConversionConfig) {
        context.dataStore.edit { prefs ->
            prefs[ConvertKeys.PRESET] = config.preset.name
            prefs[ConvertKeys.MAX_DIMENSION] = config.maxDimension
            prefs[ConvertKeys.FPS] = config.fps
            prefs[ConvertKeys.QUALITY] = config.startQuality
            prefs[ConvertKeys.TARGET_SIZE] = config.targetSizeBytes.toString()
            prefs[ConvertKeys.SHARPEN] = config.sharpen
            prefs[ConvertKeys.COMPRESSION_LEVEL] = config.compressionLevel
            prefs[ConvertKeys.DENOISE] = config.denoiseStrength
            prefs[ConvertKeys.COLOR_SPACE] = config.colorSpace.name
            prefs[ConvertKeys.DITHER_MODE] = config.ditherMode.name
            prefs[ConvertKeys.KEYFRAME_INTERVAL] = config.keyframeInterval
        }
    }
}
