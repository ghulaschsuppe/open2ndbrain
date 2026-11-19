package de.open2ndbrain.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import de.open2ndbrain.ui.theme.O2bTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Motive der Spot-Illustrationen (Onboarding, leere Zustände). */
enum class Illustration(val icon: ImageVector?) {
    /** Notizbuchseite mit Schallwelle und Lesezeichen – das Markenmotiv. */
    Notebook(null),
    Privacy(Icons.Filled.Shield),
    Microphone(Icons.Filled.Mic),
    Notifications(Icons.Filled.NotificationsActive),
    Bluetooth(Icons.Filled.Bluetooth),
    Models(Icons.Filled.Download),
    Battery(Icons.Filled.BatteryChargingFull),
    Speakers(Icons.Filled.Groups),
    Transcript(Icons.AutoMirrored.Filled.Notes),
}

/**
 * Ruhige, themenfähige Vektor-Illustration: weicher Lichthof, zwei Umlaufbahnen mit Akzentpunkten
 * (Mint, Amber, Blau) und eine Kachel mit Motiv. Rein dekorativ (für TalkBack ausgeblendet).
 */
@Composable
fun SpotIllustration(kind: Illustration, modifier: Modifier = Modifier.size(168.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    val amber = O2bTheme.extra.meeting
    val blue = MaterialTheme.colorScheme.secondary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val tile = MaterialTheme.colorScheme.surfaceContainerHighest
    val page = MaterialTheme.colorScheme.surfaceContainerHigh
    val lines = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2
            drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.20f), Color.Transparent), center, r), r)
            drawCircle(ring, r * 0.86f, style = Stroke(1.5.dp.toPx()))
            drawCircle(ring.copy(alpha = 0.6f), r * 0.66f, style = Stroke(1.dp.toPx()))
            orbitDot(r * 0.86f, -40f, amber, 5.dp.toPx())
            orbitDot(r * 0.86f, 150f, primary, 4.dp.toPx())
            orbitDot(r * 0.66f, 75f, blue, 3.5.dp.toPx())
            orbitDot(r * 0.66f, 235f, amber.copy(alpha = 0.7f), 3.dp.toPx())
            if (kind == Illustration.Notebook) {
                drawNotebook(r, page, lines, primary, amber)
            } else {
                val s = r * 0.78f
                drawRoundRect(tile, Offset(center.x - s / 2, center.y - s / 2), Size(s, s), CornerRadius(s * 0.3f))
            }
        }
        kind.icon?.let { Icon(it, contentDescription = null, tint = primary, modifier = Modifier.size(52.dp)) }
    }
}

private fun DrawScope.orbitDot(radius: Float, degrees: Float, color: Color, dotRadius: Float) {
    val a = Math.toRadians(degrees.toDouble())
    drawCircle(color, dotRadius, Offset(center.x + radius * cos(a).toFloat(), center.y + radius * sin(a).toFloat()))
}

private fun DrawScope.drawNotebook(r: Float, page: Color, lines: Color, wave: Color, ribbon: Color) {
    val w = r * 0.95f
    val h = r * 1.15f
    val left = center.x - w / 2
    val top = center.y - h / 2
    drawRoundRect(page, Offset(left, top), Size(w, h), CornerRadius(r * 0.12f))
    // Lesezeichen-Band
    val bx = left + w * 0.72f
    val bw = w * 0.12f
    drawRect(ribbon, Offset(bx, top), Size(bw, h * 0.22f))
    // Linien
    val stroke = 2.dp.toPx()
    for (i in 0 until 4) {
        val y = top + h * (0.34f + i * 0.14f)
        val end = if (i == 3) left + w * 0.55f else left + w * 0.84f
        drawLine(lines, Offset(left + w * 0.16f, y), Offset(end, y), stroke, StrokeCap.Round)
    }
    // Schallwelle über der Seite
    val heights = floatArrayOf(0.18f, 0.34f, 0.56f, 0.40f, 0.70f, 0.46f, 0.28f, 0.16f)
    val gap = w * 0.085f
    val startX = center.x - gap * (heights.size - 1) / 2
    val cy = top + h * 0.58f
    heights.forEachIndexed { i, f ->
        val x = startX + gap * i
        val half = h * f / 2.6f
        drawLine(wave, Offset(x, cy - half), Offset(x, cy + half), 5.dp.toPx(), StrokeCap.Round)
    }
}
