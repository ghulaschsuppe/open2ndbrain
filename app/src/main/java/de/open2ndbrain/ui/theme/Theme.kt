package de.open2ndbrain.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val O2bShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 4/8-dp-Raster für Abstände. */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** Seitenrand der Bildschirme. */
    val screen = 20.dp

    /** Maximale Inhaltsbreite auf Tablets/Querformat. */
    val maxContentWidth = 720.dp
}

private val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

/** Zugriff auf die Zusatzfarben: `O2bTheme.extra.live`. */
object O2bTheme {
    val extra: O2bExtraColors
        @Composable @ReadOnlyComposable
        get() = LocalExtraColors.current
}

/**
 * Eigene Markenpalette (dunkel zuerst, vollständiges helles Thema). Dynamische Farben (Android 12+) sind optional
 * und standardmäßig aus; Live-, Meeting- und Sprecherfarben bleiben auch dann markentreu.
 */
@Composable
fun O2bTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalExtraColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = colors, typography = O2bTypography, shapes = O2bShapes, content = content)
    }
}
