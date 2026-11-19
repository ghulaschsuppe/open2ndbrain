package de.open2ndbrain.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Markenpalette „Notizbuch, das zuhört“: tiefes Tinten-Navy, Mint aus dem App-Icon, warmes Amber für Meetings
 * und Lesezeichen, Koralle nur für „nimmt auf“ und Fehler. Alle Text/Hintergrund-Paare erfüllen WCAG AA.
 */
object Palette {
    // Tinte (dunkles Thema, gestaffelte Oberflächen)
    val Ink950 = Color(0xFF080D18)
    val Ink900 = Color(0xFF0B1220)
    val Ink850 = Color(0xFF0F1729)
    val Ink800 = Color(0xFF131C30)
    val Ink750 = Color(0xFF172238)
    val Ink700 = Color(0xFF1C2842)
    val Ink650 = Color(0xFF22304D)
    val Ink500 = Color(0xFF3A4868)
    val Ink400 = Color(0xFF56648A)
    val Ink200 = Color(0xFFA6B0C6)
    val Ink100 = Color(0xFFDCE2EE)
    val Ink050 = Color(0xFFEDF0F6)

    // Papier (helles Thema, leicht warm)
    val Paper = Color(0xFFF7F6F2)
    val PaperLow = Color(0xFFF1F0EB)
    val PaperMid = Color(0xFFEBEAE4)
    val PaperHigh = Color(0xFFE5E4DD)
    val PaperHighest = Color(0xFFDEDDD5)

    // Mint/Türkis (Akzent)
    val Mint = Color(0xFF7FD1B9)
    val MintDeep = Color(0xFF1C6B58)
    val MintInk = Color(0xFF00382B)
    val MintContainerDark = Color(0xFF16473C)
    val MintContainerLight = Color(0xFFC6EEDF)
    val MintPale = Color(0xFFA9EBD5)

    // Amber (Meetings, Lesezeichen)
    val Amber = Color(0xFFF2BD6B)
    val AmberDeep = Color(0xFF855400)
    val AmberContainerDark = Color(0xFF47330F)
    val AmberContainerLight = Color(0xFFFFE3B5)
    val AmberPale = Color(0xFFFFDDA8)

    // Koralle (Live-Aufnahme, Fehler)
    val Coral = Color(0xFFFF8A7A)
    val CoralLive = Color(0xFFFF6F61)
    val CoralDeep = Color(0xFFB3372B)
    val CoralContainerDark = Color(0xFF5A1F1A)
    val CoralContainerLight = Color(0xFFFFDAD4)
}

val DarkColors: ColorScheme = darkColorScheme(
    primary = Palette.Mint,
    onPrimary = Palette.MintInk,
    primaryContainer = Palette.MintContainerDark,
    onPrimaryContainer = Palette.MintPale,
    inversePrimary = Palette.MintDeep,
    secondary = Color(0xFFAFBEDF),
    onSecondary = Color(0xFF1A2640),
    secondaryContainer = Color(0xFF283654),
    onSecondaryContainer = Color(0xFFD8E1F6),
    tertiary = Palette.Amber,
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Palette.AmberContainerDark,
    onTertiaryContainer = Palette.AmberPale,
    error = Palette.Coral,
    onError = Color(0xFF5C0E06),
    errorContainer = Palette.CoralContainerDark,
    onErrorContainer = Color(0xFFFFD9D3),
    background = Palette.Ink900,
    onBackground = Palette.Ink100,
    surface = Palette.Ink900,
    onSurface = Palette.Ink100,
    surfaceVariant = Palette.Ink700,
    onSurfaceVariant = Palette.Ink200,
    surfaceTint = Palette.Mint,
    inverseSurface = Palette.Ink100,
    inverseOnSurface = Palette.Ink850,
    outline = Palette.Ink400,
    outlineVariant = Color(0xFF2B3856),
    scrim = Color(0xFF000000),
    surfaceBright = Palette.Ink650,
    surfaceDim = Palette.Ink900,
    surfaceContainerLowest = Palette.Ink950,
    surfaceContainerLow = Palette.Ink850,
    surfaceContainer = Palette.Ink800,
    surfaceContainerHigh = Palette.Ink750,
    surfaceContainerHighest = Palette.Ink700,
)

val LightColors: ColorScheme = lightColorScheme(
    primary = Palette.MintDeep,
    onPrimary = Color.White,
    primaryContainer = Palette.MintContainerLight,
    onPrimaryContainer = Color(0xFF00382B),
    inversePrimary = Palette.Mint,
    secondary = Color(0xFF4A5878),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3F4),
    onSecondaryContainer = Color(0xFF111B33),
    tertiary = Palette.AmberDeep,
    onTertiary = Color.White,
    tertiaryContainer = Palette.AmberContainerLight,
    onTertiaryContainer = Color(0xFF3A2600),
    error = Palette.CoralDeep,
    onError = Color.White,
    errorContainer = Palette.CoralContainerLight,
    onErrorContainer = Color(0xFF410001),
    background = Palette.Paper,
    onBackground = Color(0xFF141B2B),
    surface = Palette.Paper,
    onSurface = Color(0xFF141B2B),
    surfaceVariant = Palette.PaperHigh,
    onSurfaceVariant = Color(0xFF4A5368),
    surfaceTint = Palette.MintDeep,
    inverseSurface = Color(0xFF263045),
    inverseOnSurface = Palette.Ink050,
    outline = Color(0xFF7A8296),
    outlineVariant = Color(0xFFD3D4CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Palette.PaperHighest,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFBF8),
    surfaceContainer = Palette.PaperLow,
    surfaceContainerHigh = Palette.PaperMid,
    surfaceContainerHighest = Palette.PaperHigh,
)

/** Farben, die Material 3 nicht kennt: Live-Aufnahme, Meetings, Sprecher. */
@Immutable
data class O2bExtraColors(
    /** „Nimmt auf“ – nur für den Live-Zustand. */
    val live: Color,
    val onLive: Color,
    val liveContainer: Color,
    val onLiveContainer: Color,
    /** Meetings und Lesezeichen (Amber). */
    val meeting: Color,
    val meetingContainer: Color,
    val onMeetingContainer: Color,
    /** Erfolgreich/erledigt (Mint). */
    val success: Color,
    /** Sprecherfarben, je Thema in lesbarer Helligkeit (gleiche Reihenfolge wie [de.open2ndbrain.ui.speakers.SpeakerDirectory.PALETTE]). */
    val speakers: List<Color>,
    /** Weicher Verlauf hinter dem Aufnahme-Knopf. */
    val heroGlow: Color,
)

/** Sprecherfarben: harmonisch, ähnliche Helligkeit, im dunklen Thema heller, im hellen dunkler (Text-Kontrast ≥ 4,5:1). */
val SpeakerColorsDark = listOf(
    Color(0xFF8AB4F8), Color(0xFFF29BB8), Color(0xFFA5D98A), Color(0xFFF5AE7A),
    Color(0xFFC9A8F5), Color(0xFF79D3E0), Color(0xFFE6D27A), Color(0xFFD9B29A),
)
val SpeakerColorsLight = listOf(
    Color(0xFF2B62BE), Color(0xFFB0365F), Color(0xFF346E25), Color(0xFF9A4B16),
    Color(0xFF6E45B5), Color(0xFF0E6F7C), Color(0xFF7A6800), Color(0xFF85543C),
)

val DarkExtra = O2bExtraColors(
    live = Palette.CoralLive,
    onLive = Color(0xFF2B0602),
    liveContainer = Palette.CoralContainerDark,
    onLiveContainer = Color(0xFFFFD9D3),
    meeting = Palette.Amber,
    meetingContainer = Palette.AmberContainerDark,
    onMeetingContainer = Palette.AmberPale,
    success = Palette.Mint,
    speakers = SpeakerColorsDark,
    heroGlow = Palette.Mint,
)

val LightExtra = O2bExtraColors(
    live = Color(0xFFC8402F),
    onLive = Color.White,
    liveContainer = Palette.CoralContainerLight,
    onLiveContainer = Color(0xFF410001),
    meeting = Palette.AmberDeep,
    meetingContainer = Palette.AmberContainerLight,
    onMeetingContainer = Color(0xFF3A2600),
    success = Palette.MintDeep,
    speakers = SpeakerColorsLight,
    heroGlow = Palette.MintDeep,
)
