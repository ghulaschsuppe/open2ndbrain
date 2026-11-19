package de.open2ndbrain.ui.recordings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.open2ndbrain.R
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import java.io.File
import java.time.ZoneId

/** Sprungweite der ±-Knöpfe im Mini-Player. */
const val SKIP_MS = 15_000L

/** Lesbarer Titel einer Aufnahmedatei: „14:00–15:00“. */
fun playerTitle(file: File?): String {
    if (file == null) return ""
    val hour = TranscriptFiles.hourStartFromBase(TranscriptFiles.baseName(file.name), ZoneId.systemDefault())
    return hour?.let { Formatters.hourRange(it) } ?: file.name
}

/**
 * Schwebender Mini-Player: Abspielen/Anhalten, ±15 s, Fortschritt (ziehbar), Zeit.
 * [onClose] = null blendet das Schließen aus (z. B. in der Detailansicht).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniPlayer(
    title: String,
    playing: Boolean,
    enabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    error: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    // Während des Ziehens die Position lokal halten, erst beim Loslassen springen.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragValue else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownPos = (fraction * duration).toLong()

    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(
                    onClick = onToggle,
                    enabled = enabled || !playing,
                    modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (error) stringResource(R.string.player_error) else "${Formatters.clock(shownPos)} / ${Formatters.clock(durationMs)}",
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                SkipButton(back = true, enabled = enabled) { onSeek((positionMs - SKIP_MS).coerceAtLeast(0)) }
                SkipButton(back = false, enabled = enabled) { onSeek((positionMs + SKIP_MS).coerceAtMost(duration)) }
                if (onClose != null) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
                }
            }
            Slider(
                value = fraction,
                enabled = enabled && !error,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    onSeek((dragValue * duration).toLong())
                    dragging = false
                },
                colors = colors,
                interactionSource = interaction,
                thumb = { SliderDefaults.Thumb(interaction, colors = colors, enabled = enabled, thumbSize = DpSize(4.dp, 22.dp)) },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier.height(6.dp),
                        enabled = enabled && !error,
                        colors = colors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 3.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = Spacing.xxs),
            )
        }
    }
}

@Composable
private fun SkipButton(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(if (back) R.string.player_back_15 else R.string.player_forward_15)
    IconButton(onClick = onClick, enabled = enabled) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (back) Icons.AutoMirrored.Filled.Undo else Icons.AutoMirrored.Filled.Redo,
                contentDescription = description,
                modifier = Modifier.size(22.dp).offset(y = (-5).dp),
            )
            Text(
                "15",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp).tabular(),
                modifier = Modifier.offset(y = 9.dp),
            )
        }
    }
}

/** Abstand, den Listen unten freilassen, damit der Mini-Player nichts verdeckt. */
val MiniPlayerClearance = 148.dp

