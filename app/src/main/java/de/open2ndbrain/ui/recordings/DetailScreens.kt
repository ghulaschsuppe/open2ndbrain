package de.open2ndbrain.ui.recordings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Tune
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.DetailTopBar
import de.open2ndbrain.ui.components.EmptyState
import de.open2ndbrain.ui.components.Illustration
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.components.WidthAware
import de.open2ndbrain.ui.speakers.AvatarStack
import de.open2ndbrain.ui.speakers.SpeakerAvatar
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import de.open2ndbrain.ui.speakers.speakerColor
import de.open2ndbrain.ui.speakers.speakerName
import de.open2ndbrain.ui.theme.O2bTextStyles
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Detailansicht einer Aufnahmedatei: Transkript als Gespräch, Lesezeichen, Mini-Player, Neu transkribieren. */
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
    var pickModel by remember { mutableStateOf(false) }
    val installedAsr by vm.installedAsrModels.collectAsStateWithLifecycle()
    val retranscribeWith: ((String) -> Unit)? = { id -> vm.retranscribeWith(recording, id) }
    val audioExists = recording.audio.exists()
    val bookmarks = remember(markers, recording) { MarkerLogic.bookmarksIn(recording, markers) }
    val badge = data?.badges?.get(recording.audio.path) ?: TranscriptBadge.None

    val hint = when {
        transcript == null -> when (badge) {
            TranscriptBadge.Transcribing -> stringResource(R.string.badge_transcribing, Formatters.percent(status.progress))
            TranscriptBadge.Failed -> stringResource(R.string.detail_transcript_failed)
            TranscriptBadge.Waiting -> status.waitingReason ?: stringResource(R.string.badge_waiting)
            else -> stringResource(R.string.detail_no_transcript)
        }
        transcript?.utterances.isNullOrEmpty() -> stringResource(R.string.detail_transcript_empty)
        else -> null
    }

    RecordingDetailContent(
        recording = recording,
        transcript = transcript,
        hint = hint,
        audioExists = audioExists,
        speakers = speakers,
        bookmarks = bookmarks,
        player = player,
        onBack = vm::closeDetail,
        onPlayLine = { line -> vm.play(line.audio, line.utterance.fileOffsetMs) },
        onToggle = { if (player.file == recording.audio) vm.player.toggle() else vm.play(recording.audio) },
        onSeek = vm.player::seekTo,
        onBookmarkClick = { editBookmark = it },
        modifier = modifier,
    ) {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.retranscribe)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    menu = false
                    vm.retranscribe(recording)
                },
            )
            if (retranscribeWith != null && installedAsr.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.retranscribe_with)) },
                    leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    onClick = {
                        menu = false
                        pickModel = true
                    },
                )
            }
            if (audioExists) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_audio)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        confirmDelete = true
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteRecordingDialog(recording, onConfirm = {
            confirmDelete = false
            vm.delete(recording)
        }, onDismiss = { confirmDelete = false })
    }
    editBookmark?.let { b -> MarkerEditDialog(b, vm, onDismiss = { editBookmark = null }) }
    if (pickModel && retranscribeWith != null) {
        RetranscribeSheet(
            installed = installedAsr,
            currentModelId = transcript?.asrModelId,
            onPick = retranscribeWith,
            onOpenModels = null,
            onDismiss = { pickModel = false },
        )
    }
}

/** Unauffällige Herkunftsangabe: „Transkribiert mit Deutsch – schnell · 19.11.2025, 15:02“. */
@Composable
private fun ModelProvenance(t: Transcript) {
    val name = ModelCatalog.byId(t.asrModelId)?.displayName ?: t.asrModelId
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Memory,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.transcript_made_with, name, Formatters.dateTime(t.createdAtEpochMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** „Nachts verbessert mit …“ – dezente, aber erkennbare Kennzeichnung. */
@Composable
private fun ImprovedBadge(modelName: String) {
    Pill(
        stringResource(R.string.night_improved_with, modelName),
        icon = Icons.Filled.Bedtime,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 2,
    )
}

private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)

private fun longDate(epochMs: Long): String = LONG_DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Zustandslose Detailansicht einer Aufnahme (auch für Previews/Screenshots). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingDetailContent(
    recording: RecordingFile,
    transcript: Transcript?,
    hint: String?,
    audioExists: Boolean,
    speakers: SpeakerDirectory,
    bookmarks: List<Marker>,
    player: PlayerState,
    onBack: () -> Unit,
    onPlayLine: (TranscriptLine) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onBookmarkClick: (Marker) -> Unit,
    modifier: Modifier = Modifier,
    /** Nachtqualität: Name des Modells, mit dem dieses Transkript nachts verbessert wurde (null = nicht verbessert). */
    improvedWith: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val isCurrentFile = player.file == recording.audio
    val lines = remember(transcript, recording) { transcript?.utterances.orEmpty().map { TranscriptLine(recording.audio, it) } }
    val participants = remember(lines, speakers) { participantsOf(lines, speakers) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(
                title = Formatters.hourRange(recording.hourStartEpochMs),
                subtitle = longDate(recording.hourStartEpochMs),
                onBack = onBack,
                actions = actions,
            )
            TranscriptList(
                lines = lines,
                speakers = speakers,
                bookmarks = bookmarks,
                currentAudio = if (isCurrentFile && player.playing) player.file else null,
                positionMs = player.positionMs,
                onTap = onPlayLine,
                bottomClearance = if (audioExists) MiniPlayerClearance else Spacing.lg,
                header = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Pill(Formatters.duration(recording.durationMs), icon = Icons.Filled.Timer)
                            if (audioExists) {
                                Pill(Formatters.bytes(recording.sizeBytes), icon = Icons.Filled.Mic)
                            } else {
                                Pill(stringResource(R.string.recordings_audio_deleted), icon = Icons.AutoMirrored.Filled.Notes)
                            }
                            RecordingGrouping.inputNames(recording).forEach { Pill(it) }
                        }
                        if (participants.isNotEmpty()) ParticipantsRow(participants)
                        if (bookmarks.isNotEmpty()) BookmarkChips(bookmarks, onBookmarkClick)
                        if (improvedWith != null) ImprovedBadge(improvedWith) else transcript?.let { ModelProvenance(it) }
                        if (!audioExists) InfoNote(stringResource(R.string.detail_audio_deleted))
                    }
                },
                emptyContent = if (hint != null) ({ TranscriptHint(hint) }) else null,
            )
        }
        if (audioExists) {
            MiniPlayer(
                title = stringResource(R.string.player_this_recording),
                playing = isCurrentFile && player.playing,
                enabled = isCurrentFile && player.prepared,
                positionMs = if (isCurrentFile) player.positionMs else 0,
                durationMs = if (isCurrentFile && player.durationMs > 0) player.durationMs else recording.durationMs,
                error = isCurrentFile && player.error,
                onToggle = onToggle,
                onSeek = onSeek,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.sm)
                    .widthInMax(),
            )
        }
    }
}

private fun Modifier.widthInMax() = widthIn(max = Spacing.maxContentWidth).fillMaxWidth()

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
    var pickModel by remember { mutableStateOf(false) }
    val installedAsr by vm.installedAsrModels.collectAsStateWithLifecycle()
    val pending = data?.let { vm.meetingPendingFiles(meeting, it) } ?: 0
    val bookmarks = remember(markers, meeting) {
        markers.filter { it.type == MarkerType.Bookmark && MarkerLogic.overlaps(MarkerLogic.markerRange(meeting, System.currentTimeMillis()), MarkerLogic.markerRange(it, it.endEpochMs ?: it.startEpochMs)) }
    }

    MeetingDetailContent(
        meeting = meeting,
        lines = lines,
        speakers = speakers,
        bookmarks = bookmarks,
        player = player,
        pendingFiles = pending,
        onTranscribeNow = if (chargingOnly) vm::transcribeNow else null,
        onBack = vm::closeMeeting,
        onEdit = { edit = true },
        onPlayLine = { line -> vm.play(line.audio, line.utterance.fileOffsetMs) },
        onToggle = vm.player::toggle,
        onSeek = vm.player::seekTo,
        onClosePlayer = vm.player::close,
        modifier = modifier,
        onRetranscribeWith = if (installedAsr.isNotEmpty() && meeting.endEpochMs != null) ({ pickModel = true }) else null,
    )

    if (edit) MarkerEditDialog(meeting, vm, onDismiss = { edit = false })
    if (pickModel) {
        RetranscribeSheet(
            installed = installedAsr,
            currentModelId = null,
            onPick = { vm.retranscribeMeetingWith(meeting, it) },
            onOpenModels = null,
            onDismiss = { pickModel = false },
        )
    }
}

/** Zustandslose Meeting-Ansicht (auch für Previews/Screenshots). */
@Composable
fun MeetingDetailContent(
    meeting: Marker,
    lines: List<TranscriptLine>?,
    speakers: SpeakerDirectory,
    bookmarks: List<Marker>,
    player: PlayerState,
    pendingFiles: Int,
    onTranscribeNow: (() -> Unit)?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onPlayLine: (TranscriptLine) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier,
    onRetranscribeWith: (() -> Unit)? = null,
) {
    val extra = O2bTheme.extra
    val end = meeting.endEpochMs
    val participants = remember(lines, speakers) { participantsOf(lines.orEmpty(), speakers) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(
                title = meeting.title ?: stringResource(R.string.meeting_untitled),
                subtitle = longDate(meeting.startEpochMs),
                onBack = onBack,
            ) {
                if (onRetranscribeWith != null) {
                    IconButton(onClick = onRetranscribeWith) { Icon(Icons.Filled.Tune, stringResource(R.string.retranscribe_with)) }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.meeting_title_edit)) }
            }
            TranscriptList(
                lines = lines.orEmpty(),
                speakers = speakers,
                bookmarks = bookmarks,
                currentAudio = if (player.playing) player.file else null,
                positionMs = player.positionMs,
                onTap = onPlayLine,
                bottomClearance = if (player.file != null) MiniPlayerClearance else Spacing.lg,
                header = {
                    O2bCard(
                        Modifier.fillMaxWidth(),
                        color = extra.meetingContainer,
                        contentColor = extra.onMeetingContainer,
                        border = BorderStroke(1.dp, extra.meeting.copy(alpha = 0.4f)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (end == null) {
                                StatusDot(extra.meeting, pulsing = true)
                                Spacer(Modifier.width(Spacing.xs))
                            } else {
                                Icon(Icons.Filled.Groups, contentDescription = null, tint = extra.meeting, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                            }
                            SectionLabel(
                                stringResource(if (end == null) R.string.meeting_running else R.string.meeting_untitled),
                                color = extra.meeting,
                            )
                        }
                        Spacer(Modifier.size(Spacing.xxs))
                        Text(
                            if (end == null) {
                                stringResource(R.string.meeting_running_since, Formatters.time(meeting.startEpochMs))
                            } else {
                                Formatters.timeRange(meeting.startEpochMs, end)
                            },
                            style = MaterialTheme.typography.headlineSmall.tabular(),
                        )
                        if (end != null) {
                            Text(Formatters.duration(end - meeting.startEpochMs), style = MaterialTheme.typography.bodyMedium.tabular())
                        }
                        if (participants.isNotEmpty()) {
                            Spacer(Modifier.size(Spacing.sm))
                            ParticipantsRow(participants, ringColor = extra.meetingContainer)
                        }
                        if (end != null && pendingFiles > 0) {
                            Spacer(Modifier.size(Spacing.sm))
                            Text(
                                pluralStringResource(R.plurals.meeting_pending, pendingFiles, pendingFiles),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (onTranscribeNow != null) {
                                Text(stringResource(R.string.meeting_charging_hint), style = MaterialTheme.typography.bodySmall)
                                FilledTonalButton(
                                    onClick = onTranscribeNow,
                                    modifier = Modifier.padding(top = Spacing.xs).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.xs))
                                    Text(stringResource(R.string.meeting_transcribe_now))
                                }
                            }
                        }
                    }
                    if (bookmarks.isNotEmpty()) {
                        Spacer(Modifier.size(Spacing.sm))
                        BookmarkChips(bookmarks, onClick = {})
                    }
                },
                emptyContent = when {
                    lines == null -> null
                    lines.isEmpty() -> ({
                        EmptyState(
                            Illustration.Transcript,
                            title = stringResource(R.string.transcript_empty_title),
                            text = stringResource(if (end == null) R.string.meeting_no_lines_running else R.string.meeting_no_lines),
                        )
                    })
                    else -> null
                },
            )
        }
        if (player.file != null) {
            MiniPlayer(
                title = playerTitle(player.file),
                playing = player.playing,
                enabled = player.prepared,
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                error = player.error,
                onToggle = onToggle,
                onSeek = onSeek,
                onClose = onClosePlayer,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.sm)
                    .widthInMax(),
            )
        }
    }
}

/** Sprecher eines Transkripts nach Redeanteil. */
private fun participantsOf(lines: List<TranscriptLine>, speakers: SpeakerDirectory): List<SpeakerProfile> =
    lines.mapNotNull { l -> speakers.resolve(l.utterance.speakerId)?.let { it to l.utterance.durationMs } }
        .groupBy({ it.first.id }, { it })
        .map { (_, list) -> list.first().first to list.sumOf { it.second } }
        .sortedByDescending { it.second }
        .map { it.first }

@Composable
private fun ParticipantsRow(participants: List<SpeakerProfile>, ringColor: Color = MaterialTheme.colorScheme.surface) {
    val names = participants.take(4).map { speakerName(it) }
    val more = participants.size - names.size
    val text = names.joinToString(", ") + if (more > 0) " +$more" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarStack(participants, size = 28.dp, ring = ringColor)
        Spacer(Modifier.width(Spacing.xxs))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkChips(bookmarks: List<Marker>, onClick: (Marker) -> Unit) {
    val extra = O2bTheme.extra
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        bookmarks.forEach { b ->
            Pill(
                listOfNotNull(Formatters.time(b.endEpochMs ?: b.startEpochMs), b.note).joinToString(" · "),
                icon = Icons.Filled.Flag,
                container = extra.meetingContainer,
                content = extra.onMeetingContainer,
                onClick = { onClick(b) },
            )
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TranscriptHint(text: String) {
    EmptyState(Illustration.Transcript, title = stringResource(R.string.transcript_empty_title), text = text)
}

/** Umbenennen oder Löschen einer Markierung (Meeting/Lesezeichen). */
@Composable
private fun MarkerEditDialog(marker: Marker, vm: RecordingsViewModel, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.marker_delete_title)) },
            text = { Text(stringResource(R.string.marker_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMarker(marker)
                    onDismiss()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }
    val isMeeting = marker.type == MarkerType.Meeting
    var text by remember { mutableStateOf((if (isMeeting) marker.title else marker.note).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (isMeeting) Icons.Filled.Groups else Icons.Filled.Flag, contentDescription = null) },
        title = { Text(stringResource(if (isMeeting) R.string.meeting_title_label else R.string.bookmark_note_label)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, shape = MaterialTheme.shapes.medium)
        },
        confirmButton = {
            TextButton(onClick = {
                vm.renameMarker(marker, text)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Index der gerade abgespielten Äußerung, -1 wenn keine. */
fun currentLineIndex(lines: List<TranscriptLine>, audio: File?, positionMs: Long): Int {
    if (audio == null) return -1
    return lines.indexOfFirst { l ->
        l.audio == audio && positionMs >= l.utterance.fileOffsetMs && positionMs < l.utterance.fileOffsetMs + maxOf(l.utterance.durationMs, 1)
    }
}

/** Pause, ab der eine neue Gruppe beginnt, auch wenn derselbe Sprecher weiterredet. */
private const val GROUP_GAP_MS = 90_000L

/** Beginnt bei [i] eine neue Gruppe (anderer Sprecher oder längere Pause)? */
fun startsGroup(lines: List<TranscriptLine>, i: Int): Boolean {
    if (i == 0) return true
    val prev = lines[i - 1].utterance
    val cur = lines[i].utterance
    return prev.speakerId != cur.speakerId || cur.startEpochMs - (prev.startEpochMs + prev.durationMs) > GROUP_GAP_MS
}

/**
 * Transkript als Gespräch: Avatar, Name und Uhrzeit am Anfang jeder Gruppe, danach nur Text.
 * Die gerade abgespielte Äußerung ist hervorgehoben; die Liste folgt ihr.
 */
@Composable
private fun TranscriptList(
    lines: List<TranscriptLine>,
    speakers: SpeakerDirectory,
    bookmarks: List<Marker>,
    currentAudio: File?,
    positionMs: Long,
    onTap: (TranscriptLine) -> Unit,
    bottomClearance: androidx.compose.ui.unit.Dp,
    header: @Composable () -> Unit,
    emptyContent: (@Composable () -> Unit)?,
    state: LazyListState = rememberLazyListState(),
) {
    val current = currentLineIndex(lines, currentAudio, positionMs)
    // Während der Wiedergabe der aktuellen Äußerung folgen (+1 wegen der Kopfzeile).
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem(current + 1, scrollOffset = -120) }
    val withSpeakers = remember(lines) { lines.any { it.utterance.speakerId != null } }
    WidthAware(Modifier.fillMaxSize()) { side ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = side + Spacing.md, end = side + Spacing.md, bottom = bottomClearance),
        ) {
            item(key = "header") {
                Column(Modifier.padding(start = Spacing.xxs, end = Spacing.xxs, bottom = Spacing.sm)) { header() }
            }
            if (emptyContent != null) {
                item(key = "empty") { emptyContent() }
            }
            itemsIndexed(lines, key = { i, l -> "${l.audio.name}-${l.utterance.fileOffsetMs}-$i" }) { i, line ->
                UtteranceItem(
                    line = line,
                    first = startsGroup(lines, i),
                    withSpeakers = withSpeakers,
                    speakers = speakers,
                    highlighted = i == current,
                    flagged = bookmarks.any { MarkerLogic.bookmarkHits(line.utterance, it) },
                    onClick = { onTap(line) },
                )
            }
        }
    }
}

@Composable
private fun UtteranceItem(
    line: TranscriptLine,
    first: Boolean,
    withSpeakers: Boolean,
    speakers: SpeakerDirectory,
    highlighted: Boolean,
    flagged: Boolean,
    onClick: () -> Unit,
) {
    val u = line.utterance
    val profile = speakers.resolve(u.speakerId)
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (highlighted) scheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent, label = "lineBg")
    val avatar = 36.dp
    val playingText = stringResource(R.string.transcript_playing)
    val name = if (withSpeakers) speakerName(profile) else null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) Spacing.md else 0.dp)
            .clip12()
            .background(bg)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (highlighted) stateDescription = playingText
                if (!first && name != null) contentDescription = "$name: ${u.text}"
            }
            .padding(horizontal = Spacing.xxs, vertical = 6.dp),
    ) {
        if (withSpeakers) {
            Box(Modifier.width(avatar + Spacing.sm)) {
                if (first) SpeakerAvatar(profile, size = avatar)
            }
        }
        Column(Modifier.weight(1f)) {
            if (first) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                    if (name != null) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            color = speakerColor(profile),
                        )
                        if (profile?.isMe == true) {
                            Spacer(Modifier.width(6.dp))
                            MeBadge()
                        }
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Text(
                        Formatters.time(u.startEpochMs),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(u.text, style = O2bTextStyles.transcript, color = scheme.onSurface, modifier = Modifier.weight(1f))
                if (flagged) {
                    Icon(
                        Icons.Filled.Flag,
                        stringResource(R.string.bookmark_action),
                        tint = O2bTheme.extra.meeting,
                        modifier = Modifier.padding(start = Spacing.xs, top = 6.dp).size(16.dp),
                    )
                }
            }
        }
    }
}

private fun Modifier.clip12() = clip(RoundedCornerShape(12.dp))

/** Kleines „Ich“-Abzeichen. */
@Composable
fun MeBadge() {
    Text(
        stringResource(R.string.speaker_me),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
