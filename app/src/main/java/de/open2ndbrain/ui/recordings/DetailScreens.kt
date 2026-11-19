package de.open2ndbrain.ui.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.R
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Utterance
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import de.open2ndbrain.ui.speakers.SpeakerDot
import de.open2ndbrain.ui.speakers.speakerName
import java.io.File

/** Detailansicht einer Aufnahmedatei: Player, Transkript (Antippen springt), Lesezeichen, Neu transkribieren. */
@Composable
fun RecordingDetail(recording: RecordingFile, vm: RecordingsViewModel, modifier: Modifier) {
    val transcript by vm.selectedTranscript.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val markers by vm.markers.collectAsStateWithLifecycle()
    val status by vm.transcriptionStatus.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editBookmark by remember { mutableStateOf<Marker?>(null) }
    val audioExists = recording.audio.exists()
    val bookmarks = remember(markers, recording) { MarkerLogic.bookmarksIn(recording, markers) }
    val badge = data?.badges?.get(recording.audio.path) ?: TranscriptBadge.None
    val isCurrentFile = player.file == recording.audio

    Column(modifier.fillMaxSize()) {
        DetailHeader(
            title = Formatters.hourRange(recording.hourStartEpochMs),
            subtitle = Formatters.dateTime(recording.hourStartEpochMs).substringBefore(","),
            onBack = vm::closeDetail,
        ) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.retranscribe)) },
                    onClick = {
                        menu = false
                        vm.retranscribe(recording)
                    },
                )
                if (audioExists) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_audio)) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }

        if (audioExists) {
            if (isCurrentFile) {
                PlayerBar(player, onToggle = vm.player::toggle, onSeek = vm.player::seekTo, onClose = null)
            } else {
                OutlinedButton(onClick = { vm.play(recording.audio) }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.player_play))
                }
            }
        } else {
            Hint(stringResource(R.string.detail_audio_deleted))
        }

        if (bookmarks.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                bookmarks.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editBookmark = b }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(Formatters.time(b.endEpochMs ?: b.startEpochMs), b.note).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        val t = transcript
        if (t == null) {
            Hint(
                when (badge) {
                    TranscriptBadge.Transcribing -> stringResource(R.string.badge_transcribing, Formatters.percent(status.progress))
                    TranscriptBadge.Failed -> stringResource(R.string.detail_transcript_failed)
                    TranscriptBadge.Waiting -> status.waitingReason ?: stringResource(R.string.badge_waiting)
                    else -> stringResource(R.string.detail_no_transcript)
                },
            )
        } else if (t.utterances.isEmpty()) {
            Hint(stringResource(R.string.detail_transcript_empty))
        } else {
            UtteranceList(
                lines = t.utterances.map { TranscriptLine(recording.audio, it) },
                speakers = speakers,
                bookmarks = bookmarks,
                currentAudio = if (isCurrentFile && player.playing) player.file else null,
                positionMs = player.positionMs,
                onTap = { line -> vm.play(line.audio, line.utterance.fileOffsetMs) },
            )
        }
    }

    if (confirmDelete) {
        DeleteRecordingDialog(recording, onConfirm = {
            confirmDelete = false
            vm.delete(recording)
        }, onDismiss = { confirmDelete = false })
    }
    editBookmark?.let { b -> MarkerEditDialog(b, vm, onDismiss = { editBookmark = null }) }
}

/** Meeting-Ansicht: alle Äußerungen im Zeitraum, dateiübergreifend; Antippen spielt die richtige Datei ab. */
@Composable
fun MeetingDetail(meeting: Marker, vm: RecordingsViewModel, modifier: Modifier) {
    val lines by vm.meetingLines.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val markers by vm.markers.collectAsStateWithLifecycle()
    val chargingOnly by vm.chargingOnly.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf(false) }
    val end = meeting.endEpochMs
    val pending = data?.let { vm.meetingPendingFiles(meeting, it) } ?: 0
    val bookmarks = remember(markers, meeting) {
        markers.filter { it.type == MarkerType.Bookmark && MarkerLogic.overlaps(MarkerLogic.markerRange(meeting, System.currentTimeMillis()), MarkerLogic.markerRange(it, it.endEpochMs ?: it.startEpochMs)) }
    }

    Column(modifier.fillMaxSize()) {
        DetailHeader(
            title = meeting.title ?: stringResource(R.string.meeting_untitled),
            subtitle = if (end == null) {
                stringResource(R.string.meeting_running_since, Formatters.time(meeting.startEpochMs))
            } else {
                "${Formatters.timeRange(meeting.startEpochMs, end)} · ${Formatters.duration(end - meeting.startEpochMs)}"
            },
            onBack = vm::closeMeeting,
        ) {
            IconButton(onClick = { edit = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.meeting_title_edit)) }
        }

        if (player.file != null) PlayerBar(player, onToggle = vm.player::toggle, onSeek = vm.player::seekTo, onClose = vm.player::close)

        if (end != null && pending > 0) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pluralStringResource(R.plurals.meeting_pending, pending, pending), style = MaterialTheme.typography.bodyMedium)
                if (chargingOnly) {
                    Text(stringResource(R.string.meeting_charging_hint), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::transcribeNow) { Text(stringResource(R.string.meeting_transcribe_now)) }
                }
            }
        }

        val l = lines
        when {
            l == null -> Unit
            l.isEmpty() -> Hint(stringResource(if (end == null) R.string.meeting_no_lines_running else R.string.meeting_no_lines))
            else -> UtteranceList(
                lines = l,
                speakers = speakers,
                bookmarks = bookmarks,
                currentAudio = if (player.playing) player.file else null,
                positionMs = player.positionMs,
                onTap = { line -> vm.play(line.audio, line.utterance.fileOffsetMs) },
            )
        }
    }

    if (edit) MarkerEditDialog(meeting, vm, onDismiss = { edit = false })
}

/** Umbenennen oder Löschen einer Markierung (Meeting/Lesezeichen). */
@Composable
private fun MarkerEditDialog(marker: Marker, vm: RecordingsViewModel, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.marker_delete_title)) },
            text = { Text(stringResource(R.string.marker_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMarker(marker)
                    onDismiss()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    val isMeeting = marker.type == MarkerType.Meeting
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isMeeting) R.string.meeting_title_label else R.string.bookmark_note_label)) },
        text = {
            Column {
                var text by remember { mutableStateOf((if (isMeeting) marker.title else marker.note).orEmpty()) }
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                Row {
                    TextButton(onClick = {
                        vm.renameMarker(marker, text)
                        onDismiss()
                    }) { Text(stringResource(R.string.save)) }
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, null)
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

/** Index der gerade abgespielten Äußerung, -1 wenn keine. */
fun currentLineIndex(lines: List<TranscriptLine>, audio: File?, positionMs: Long): Int {
    if (audio == null) return -1
    return lines.indexOfFirst { l ->
        l.audio == audio && positionMs >= l.utterance.fileOffsetMs && positionMs < l.utterance.fileOffsetMs + maxOf(l.utterance.durationMs, 1)
    }
}

@Composable
private fun UtteranceList(
    lines: List<TranscriptLine>,
    speakers: SpeakerDirectory,
    bookmarks: List<Marker>,
    currentAudio: File?,
    positionMs: Long,
    onTap: (TranscriptLine) -> Unit,
    state: LazyListState = rememberLazyListState(),
) {
    val current = currentLineIndex(lines, currentAudio, positionMs)
    // Während der Wiedergabe der aktuellen Äußerung folgen.
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(current) }
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        itemsIndexed(lines, key = { i, l -> "${l.audio.name}-${l.utterance.fileOffsetMs}-$i" }) { i, line ->
            UtteranceRow(
                u = line.utterance,
                speakers = speakers,
                highlighted = i == current,
                flagged = bookmarks.any { MarkerLogic.bookmarkHits(line.utterance, it) },
                onClick = { onTap(line) },
            )
        }
    }
}

@Composable
private fun UtteranceRow(u: Utterance, speakers: SpeakerDirectory, highlighted: Boolean, flagged: Boolean, onClick: () -> Unit) {
    val profile = speakers.resolve(u.speakerId)
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Formatters.timeWithSeconds(u.startEpochMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (u.speakerId != null) {
                Spacer(Modifier.width(8.dp))
                SpeakerDot(profile)
                Spacer(Modifier.width(4.dp))
                Text(
                    speakerName(profile),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SpeakerDirectory.color(profile),
                )
            }
            if (flagged) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Flag, stringResource(R.string.bookmark_action), tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
            }
        }
        Text(u.text, style = MaterialTheme.typography.bodyLarge)
    }
}
