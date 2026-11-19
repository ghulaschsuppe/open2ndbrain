package de.open2ndbrain.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback-Farben (vor Android 12), abgeleitet vom App-Icon: Mint auf Nachtblau.
private val Mint = Color(0xFF2E8B74)
private val MintLight = Color(0xFF7FD1B9)
private val Navy = Color(0xFF1E2A44)

private val LightColors = lightColorScheme(
    primary = Mint,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0DD),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Navy,
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = MintLight,
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF005140),
    onPrimaryContainer = Color(0xFFB7F0DD),
    secondary = Color(0xFFB8C6E6),
    onSecondary = Navy,
)

/** Material 3, dynamische Farben ab Android 12, hell/dunkel nach System. */
@Composable
fun O2bTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
