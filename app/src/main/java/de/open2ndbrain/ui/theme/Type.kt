package de.open2ndbrain.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.open2ndbrain.R

/*
 * Schriften (beide SIL Open Font License 1.1, eingebettet, kein Netzwerk):
 * - Manrope (variabel) für Überschriften und Titel: ruhig, eigenständig, leicht geometrisch.
 * - Inter (variabel) für Fließtext, Transkripte und Zahlen: sehr gut lesbar, tabellarische Ziffern („tnum“).
 * Beide sind auf Latein/Latein-1 reduziert (Umlaute, ß, typografische Zeichen bleiben enthalten).
 */

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Tabellarische Ziffern für Uhrzeiten, Dauern und Zähler (springen beim Hochzählen nicht). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val Display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold)
private val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal)

val O2bTypography = Typography(
    displayLarge = Display.copy(fontSize = 52.sp, lineHeight = 60.sp, letterSpacing = (-0.02).em),
    displayMedium = Display.copy(fontSize = 42.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em),
    displaySmall = Display.copy(fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.015).em),
    headlineLarge = Display.copy(fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em),
    headlineMedium = Display.copy(fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em),
    headlineSmall = Display.copy(fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = Display.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = Display.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.005.em),
    titleSmall = Display.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.01.em),
    // Transkripte nutzen bodyLarge: großzügiger Zeilenabstand für langes Lesen.
    bodyLarge = Body.copy(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.003.em),
    bodyMedium = Body.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.005.em),
    bodySmall = Body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.01.em),
    labelLarge = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.01.em),
    labelMedium = Body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em),
    labelSmall = Body.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.03.em),
)

/** Zusätzliche Textstile außerhalb der Material-Skala. */
object O2bTextStyles {
    /** Fließtext im Transkript: etwas größer als bodyLarge, ruhiger Zeilenfall. */
    val transcript = Body.copy(fontSize = 17.sp, lineHeight = 28.sp, letterSpacing = 0.002.em)

    /** Große Zahl in Kennzahl-Kacheln. */
    val metric = Display.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

    /** Kleine Überschrift in Versalien über Gruppen („HEUTE“, „AUFNAHME“). */
    val overline = Body.copy(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)
}
