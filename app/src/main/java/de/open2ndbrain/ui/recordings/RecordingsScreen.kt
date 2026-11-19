package de.open2ndbrain.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Subject
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
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.ui.Formatters
import java.time.LocalDate

@Composable
fun RecordingsScreen(modifier: Modifier = Modifier, vm: RecordingsViewModel = viewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val meeting by vm.selectedMeeting.collectAsStateWithLifecycle()
    when {
        meeting != null -> {
            BackHandler { vm.closeMeeting() }
            MeetingDetail(meeting!!, vm, modifier)
        }
        selected != null -> {
            BackHandler { vm.closeDetail() }
            RecordingDetail(selected!!, vm, modifier)
        }
        else -> RecordingsList(vm, modifier)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingsList(vm: RecordingsViewModel, modifier: Modifier) {
    val data by vm.data.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val status by vm.transcriptionStatus.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<RecordingFile?>(null) }
    val today = LocalDate.now()

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            val d = data
            when {
                d == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                d.days.isEmpty() -> Text(
                    stringResource(R.string.recordings_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    d.days.forEach { day ->
                        stickyHeader(key = "day-${day.date}") { DayHeader(day, today) }
                        items(d.meetings[day.date].orEmpty(), key = { "m-${it.id}" }) { m ->
                            MeetingRow(m, onClick = { vm.openMeeting(m) })
                            HorizontalDivider()
                        }
                        items(day.recordings, key = { it.audio.path }) { rec ->
                            RecordingRow(
                                recording = rec,
                                badge = d.badges[rec.audio.path] ?: TranscriptBadge.None,
                                progress = if (status.currentFile == rec.audio.name) status.progress else null,
                                audioDeleted = rec.audio.path in d.audioDeleted,
                                bookmarks = d.bookmarks[rec.audio.path] ?: 0,
                                selected = player.file == rec.audio,
                                playing = player.file == rec.audio && player.playing,
                                onClick = { vm.open(rec) },
                                onPlay = { vm.play(rec.audio) },
                                onDelete = { toDelete = rec },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (player.file != null) {
            PlayerBar(player, onToggle = vm.player::toggle, onSeek = vm.player::seekTo, onClose = vm.player::close)
        }
    }

    toDelete?.let { rec -> DeleteRecordingDialog(rec, onConfirm = { vm.delete(rec); toDelete = null }, onDismiss = { toDelete = null }) }
}

@Composable
fun DeleteRecordingDialog(rec: RecordingFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recordings_delete_title)) },
        text = {
            Text(stringResource(R.string.recordings_delete_text, Formatters.hourRange(rec.hourStartEpochMs), Formatters.duration(rec.durationMs)))
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
private fun MeetingRow(m: Marker, onClick: () -> Unit) {
    val end = m.endEpochMs
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        leadingContent = { Icon(Icons.Filled.Groups, contentDescription = null) },
        headlineContent = { Text(m.title ?: stringResource(R.string.meeting_untitled)) },
        supportingContent = {
            Text(
                if (end == null) {
                    stringResource(R.string.meeting_running_since, Formatters.time(m.startEpochMs))
                } else {
                    "${Formatters.timeRange(m.startEpochMs, end)} · ${Formatters.duration(end - m.startEpochMs)}"
                },
            )
        },
    )
}

@Composable
private fun RecordingRow(
    recording: RecordingFile,
    badge: TranscriptBadge,
    progress: Float?,
    audioDeleted: Boolean,
    bookmarks: Int,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val part = RecordingGrouping.partNumber(recording.audio.name)
    val title = buildString {
        append(Formatters.hourRange(recording.hourStartEpochMs))
        if (part > 1) append(" · ").append(stringResource(R.string.recordings_part, part))
    }
    val details = buildList {
        add(Formatters.duration(recording.durationMs))
        if (audioDeleted) add(stringResource(R.string.recordings_audio_deleted)) else add(Formatters.bytes(recording.sizeBytes))
        addAll(RecordingGrouping.inputNames(recording))
        badgeText(badge, progress)?.let(::add)
    }.joinToString(" · ")

    ListItem(
        modifier = Modifier.clickable(enabled = !recording.inProgress, onClick = onClick),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        leadingContent = {
            when {
                recording.inProgress -> Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                audioDeleted -> Icon(Icons.AutoMirrored.Filled.Subject, null, tint = MaterialTheme.colorScheme.primary)
                else -> IconButton(onClick = onPlay) {
                    Icon(
                        if (playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.player_play),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                if (bookmarks > 0) {
                    Icon(
                        Icons.Filled.Flag,
                        contentDescription = stringResource(R.string.bookmark_action),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp),
                    )
                }
            }
        },
        supportingContent = { Text(details) },
        trailingContent = {
            when {
                recording.inProgress -> Text(
                    stringResource(R.string.recordings_running),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
                !audioDeleted -> IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
            }
        },
    )
}

@Composable
private fun badgeText(badge: TranscriptBadge, progress: Float?): String? = when (badge) {
    TranscriptBadge.None -> null
    TranscriptBadge.Waiting -> stringResource(R.string.badge_waiting)
    TranscriptBadge.Transcribing -> stringResource(R.string.badge_transcribing, Formatters.percent(progress ?: 0f))
    TranscriptBadge.Done -> stringResource(R.string.badge_done)
    TranscriptBadge.Failed -> stringResource(R.string.badge_failed)
}

@Composable
fun PlayerBar(state: PlayerState, onToggle: () -> Unit, onSeek: (Long) -> Unit, onClose: (() -> Unit)?) {
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
                Text(state.file?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                if (onClose != null) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
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
