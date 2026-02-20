package app.embeddy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple40,
    onPrimary = DarkSurface,
    primaryContainer = Purple80,
    secondary = Teal40,
    onSecondary = DarkSurface,
    secondaryContainer = Teal80,
    tertiary = Amber40,
    tertiaryContainer = Amber80,
    background = DarkSurface,
    surface = DarkSurface,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceVariant,
    surfaceVariant = DarkSurfaceVariant,
    error = ErrorRed,
)

@Composable
fun EmbeddyTheme(content: @Composable () -> Unit) {
    // Use Material You dynamic colors on Android 12+ with dark-only fallback
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
