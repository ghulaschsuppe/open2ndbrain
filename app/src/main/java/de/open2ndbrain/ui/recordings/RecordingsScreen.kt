package de.open2ndbrain.ui.recordings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.R
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.ui.Formatters
import java.time.LocalDate

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsScreen(modifier: Modifier = Modifier, vm: RecordingsViewModel = viewModel()) {
    val days by vm.days.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<RecordingFile?>(null) }
    val today = LocalDate.now()

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val list = days
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    stringResource(R.string.recordings_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    list.forEach { day ->
                        stickyHeader(key = "day-${day.date}") { DayHeader(day, today) }
                        items(day.recordings, key = { it.audio.path }) { rec ->
                            RecordingRow(
                                recording = rec,
                                selected = player.file == rec.audio,
                                playing = player.file == rec.audio && player.playing,
                                onClick = { vm.play(rec) },
                                onDelete = { toDelete = rec },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (player.file != null) {
            PlayerBar(
                state = player,
                onToggle = vm.player::toggle,
                onSeek = vm.player::seekTo,
                onClose = vm.player::close,
            )
        }
    }

    toDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.recordings_delete_text,
                        Formatters.hourRange(rec.hourStartEpochMs),
                        Formatters.duration(rec.durationMs),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(rec)
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DayHeader(day: DayGroup, today: LocalDate) {
    val label = when (val l = RecordingGrouping.dayLabel(day.date, today)) {
        DayLabel.Today -> stringResource(R.string.day_today)
        DayLabel.Yesterday -> stringResource(R.string.day_yesterday)
        is DayLabel.Date -> l.text
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${Formatters.duration(day.totalDurationMs)} · ${Formatters.bytes(day.totalSizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingRow(
    recording: RecordingFile,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val part = RecordingGrouping.partNumber(recording.audio.name)
    val title = buildString {
        append(Formatters.hourRange(recording.hourStartEpochMs))
        if (part > 1) append(" · ").append(stringResource(R.string.recordings_part, part))
    }
    val details = buildList {
        add(Formatters.duration(recording.durationMs))
        add(Formatters.bytes(recording.sizeBytes))
        addAll(RecordingGrouping.inputNames(recording))
    }.joinToString(" · ")

    ListItem(
        modifier = Modifier.clickable(enabled = !recording.inProgress, onClick = onClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            Icon(
                when {
                    recording.inProgress -> Icons.Filled.FiberManualRecord
                    playing -> Icons.Filled.GraphicEq
                    else -> Icons.Filled.PlayArrow
                },
                contentDescription = null,
                tint = if (recording.inProgress) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(details) },
        trailingContent = {
            if (recording.inProgress) {
                Text(
                    stringResource(R.string.recordings_running),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
            }
        },
    )
}

@Composable
private fun PlayerBar(state: PlayerState, onToggle: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit) {
    // Während des Ziehens die Position lokal halten, erst beim Loslassen springen.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle, enabled = state.prepared) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                    )
                }
                Text(
                    state.file?.name.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }
            if (state.error) {
                Text(stringResource(R.string.player_error), color = MaterialTheme.colorScheme.error)
            } else {
                Slider(
                    value = position,
                    enabled = state.prepared,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        onSeek((dragValue * duration).toLong())
                        dragging = false
                    },
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(Formatters.clock((position * duration).toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(Formatters.clock(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
