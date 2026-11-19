package de.open2ndbrain.ui.speakers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.ui.theme.Manrope
import de.open2ndbrain.ui.theme.O2bTheme

/** Sprecherfarbe passend zum aktuellen Thema (hell/dunkel jeweils mit ausreichendem Kontrast). */
@Composable
fun speakerColor(profile: SpeakerProfile?): Color =
    profile?.let { O2bTheme.extra.speakers[SpeakerDirectory.colorIndex(it.number)] } ?: MaterialTheme.colorScheme.outline

/** Initialen aus dem Namen („Anna Berg“ → „AB“), sonst die Sprechernummer. */
fun speakerInitials(profile: SpeakerProfile?): String {
    if (profile == null) return "?"
    val name = profile.name?.trim().orEmpty()
    if (name.isEmpty()) return profile.number.toString()
    val parts = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return (if (parts.size >= 2) "${parts[0].first()}${parts[1].first()}" else parts[0].take(2)).uppercase()
}

/** Runder Avatar mit Initialen in der Sprecherfarbe auf getöntem Grund. */
@Composable
fun SpeakerAvatar(profile: SpeakerProfile?, modifier: Modifier = Modifier, size: Dp = 36.dp, ring: Color? = null) {
    val color = speakerColor(profile)
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(base)
            .background(color.copy(alpha = 0.22f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (profile == null) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
        } else {
            Text(
                speakerInitials(profile),
                color = color,
                style = TextStyle(
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (size.value * 0.38f).sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Überlappende Avatare (höchstens [max], danach „+n“). */
@Composable
fun AvatarStack(
    profiles: List<SpeakerProfile?>,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    max: Int = 4,
    ring: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val shown = profiles.take(max)
    val overlap = size * 0.3f
    // Überlappend anordnen, ohne Platz für die Überlappung zu reservieren.
    Layout(
        modifier = modifier.clearAndSetSemantics { },
        content = {
            shown.forEach { p -> SpeakerAvatar(p, size = size, ring = ring) }
            val more = profiles.size - shown.size
            if (more > 0) MoreBubble(more, size, ring)
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val step = (size - overlap).roundToPx()
        val width = if (placeables.isEmpty()) 0 else step * (placeables.size - 1) + placeables.last().width
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { i, pl -> pl.placeRelative(step * i, 0, zIndex = (placeables.size - i).toFloat()) }
        }
    }
}

@Composable
private fun MoreBubble(more: Int, size: Dp, ring: Color) {
    Box(
        Modifier
            .size(size)
            .border(2.dp, ring, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text("+$more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
