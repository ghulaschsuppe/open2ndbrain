package de.open2ndbrain.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.R
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.EmptyState
import de.open2ndbrain.ui.components.IconBubble
import de.open2ndbrain.ui.components.Illustration
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.ScreenTitle
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.components.WidthAware
import de.open2ndbrain.ui.speakers.AvatarStack
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import java.time.LocalDate

@Composable
fun RecordingsScreen(modifier: Modifier = Modifier, onStartRecording: () -> Unit = {}, vm: RecordingsViewModel = viewModel()) {
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
        else -> RecordingsList(vm, modifier, onStartRecording)
    }
}

@Composable
private fun RecordingsList(vm: RecordingsViewModel, modifier: Modifier, onStartRecording: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val status by vm.transcriptionStatus.collectAsStateWithLifecycle()
    val speakers by vm.speakers.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<RecordingFile?>(null) }

    TimelineContent(
        data = data,
        player = player,
        status = status,
        speakers = speakers,
        today = LocalDate.now(),
        modifier = modifier,
        preview = { rec, hasTranscript -> rememberTranscriptPreview(rec.audio, hasTranscript) },
        actions = TimelineActions(
            onOpen = vm::open,
            onOpenMeeting = vm::openMeeting,
            onPlay = { vm.play(it.audio) },
            onDelete = { toDelete = it },
            onToggle = vm.player::toggle,
            onSeek = vm.player::seekTo,
            onClosePlayer = vm.player::close,
            onStartRecording = onStartRecording,
        ),
    )

    toDelete?.let { rec -> DeleteRecordingDialog(rec, onConfirm = { vm.delete(rec); toDelete = null }, onDismiss = { toDelete = null }) }
}

class TimelineActions(
    val onOpen: (RecordingFile) -> Unit = {},
    val onOpenMeeting: (Marker) -> Unit = {},
    val onPlay: (RecordingFile) -> Unit = {},
    val onDelete: (RecordingFile) -> Unit = {},
    val onToggle: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onClosePlayer: () -> Unit = {},
    val onStartRecording: () -> Unit = {},
)

/** Ein Eintrag der Zeitleiste eines Tages: Meeting oder Stundendatei, nach Uhrzeit sortiert (neueste oben). */
private sealed interface TimelineEntry {
    val time: Long
    val key: String

    data class Meeting(val marker: Marker) : TimelineEntry {
        override val time get() = marker.startEpochMs
        override val key get() = "m-${marker.id}"
    }

    data class Hour(val recording: RecordingFile) : TimelineEntry {
        override val time get() = recording.hourStartEpochMs
        override val key: String get() = recording.audio.path
    }
}

/** Zeitleiste: Tage mit Sticky-Headern, vertikale Linie, Stunden- und Meeting-Karten, schwebender Mini-Player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineContent(
    data: RecordingsData?,
    player: PlayerState,
    status: TranscriptionStatus,
    speakers: SpeakerDirectory,
    today: LocalDate,
    actions: TimelineActions,
    preview: @Composable (RecordingFile, Boolean) -> TranscriptPreview?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when {
            data == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            data.days.isEmpty() -> Column(Modifier.fillMaxSize()) {
                ScreenTitle(stringResource(R.string.timeline_title))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Illustration.Notebook,
                        title = stringResource(R.string.timeline_empty_title),
                        text = stringResource(R.string.timeline_empty_text),
                        action = {
                            Button(onClick = actions.onStartRecording) {
                                Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs))
                                Text(stringResource(R.string.timeline_empty_action))
                            }
                        },
                    )
                }
            }
            else -> WidthAware(Modifier.fillMaxSize()) { side ->
                val totalMs = remember(data) { data.days.sumOf { it.totalDurationMs } }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = if (player.file != null) MiniPlayerClearance else Spacing.lg),
                ) {
                    item(key = "title") {
                        ScreenTitle(
                            stringResource(R.string.timeline_title),
                            subtitle = stringResource(R.string.timeline_subtitle, Formatters.duration(totalMs)),
                            modifier = Modifier.padding(horizontal = side),
                        )
                    }
                    data.days.forEach { day ->
                        stickyHeader(key = "day-${day.date}") { DayHeader(day, today, side) }
                        val entries = buildList<TimelineEntry> {
                            data.meetings[day.date].orEmpty().forEach { add(TimelineEntry.Meeting(it)) }
                            day.recordings.forEach { add(TimelineEntry.Hour(it)) }
                        }.sortedByDescending { it.time }
                        items(entries, key = { it.key }) { entry ->
                            Box(Modifier.padding(horizontal = side)) {
                                when (entry) {
                                    is TimelineEntry.Meeting -> TimelineRow(entry.time, accent = O2bTheme.extra.meeting) {
                                        MeetingEntryCard(entry.marker, onClick = { actions.onOpenMeeting(entry.marker) })
                                    }
                                    is TimelineEntry.Hour -> {
                                        val rec = entry.recording
                                        val badge = data.badges[rec.audio.path] ?: TranscriptBadge.None
                                        TimelineRow(
                                            entry.time,
                                            accent = if (rec.inProgress) O2bTheme.extra.live else MaterialTheme.colorScheme.primary,
                                        ) {
                                            HourCard(
                                                recording = rec,
                                                badge = badge,
                                                progress = if (status.currentFile == rec.audio.name) status.progress else null,
                                                audioDeleted = rec.audio.path in data.audioDeleted,
                                                bookmarks = data.bookmarks[rec.audio.path] ?: 0,
                                                preview = preview(rec, badge == TranscriptBadge.Done),
                                                speakers = speakers,
                                                selected = player.file == rec.audio,
                                                playing = player.file == rec.audio && player.playing,
                                                actions = actions,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = player.file != null,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = side + Spacing.sm, vertical = Spacing.sm),
                ) {
                    MiniPlayer(
                        title = playerTitle(player.file),
                        playing = player.playing,
                        enabled = player.prepared,
                        positionMs = player.positionMs,
                        durationMs = player.durationMs,
                        error = player.error,
                        onToggle = actions.onToggle,
                        onSeek = actions.onSeek,
                        onClose = actions.onClosePlayer,
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteRecordingDialog(rec: RecordingFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.recordings_delete_title)) },
        text = {
            Text(stringResource(R.string.recordings_delete_text, Formatters.hourRange(rec.hourStartEpochMs), Formatters.duration(rec.durationMs)))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DayHeader(day: DayGroup, today: LocalDate, side: androidx.compose.ui.unit.Dp) {
    val label = when (val l = RecordingGrouping.dayLabel(day.date, today)) {
        DayLabel.Today -> stringResource(R.string.day_today)
        DayLabel.Yesterday -> stringResource(R.string.day_yesterday)
        is DayLabel.Date -> l.text
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = side + Spacing.screen, end = side + Spacing.md, top = Spacing.md, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (day.totalDurationMs > 0) {
                Pill(
                    "${Formatters.duration(day.totalDurationMs)} · ${Formatters.bytes(day.totalSizeBytes)}",
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
        }
    }
}

/** Zeile mit Uhrzeit links, Knoten auf der durchgehenden Linie und Karte rechts. */
@Composable
private fun TimelineRow(time: Long, accent: Color, content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val gutter = 72.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(end = Spacing.md),
    ) {
        Box(
            Modifier
                .width(gutter)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width - 12.dp.toPx()
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    drawCircle(accent, 5.dp.toPx(), Offset(x, 27.dp.toPx()))
                    drawCircle(accent.copy(alpha = 0.25f), 9.dp.toPx(), Offset(x, 27.dp.toPx()))
                },
        ) {
            Text(
                Formatters.time(time),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(end = 26.dp, top = 19.dp),
            )
        }
        Box(Modifier.weight(1f).padding(start = Spacing.sm, top = Spacing.xxs, bottom = Spacing.xs)) { content() }
    }
}

@Composable
private fun MeetingEntryCard(m: Marker, onClick: () -> Unit) {
    val extra = O2bTheme.extra
    val end = m.endEpochMs
    O2bCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        color = extra.meetingContainer,
        contentColor = extra.onMeetingContainer,
        border = BorderStroke(1.dp, extra.meeting.copy(alpha = 0.4f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Filled.Groups, tint = extra.meeting, size = 36.dp)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title ?: stringResource(R.string.meeting_untitled),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (end == null) {
                        StatusDot(extra.meeting, pulsing = true)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (end == null) {
                            stringResource(R.string.meeting_running_since, Formatters.time(m.startEpochMs))
                        } else {
                            "${Formatters.timeRange(m.startEpochMs, end)} · ${Formatters.duration(end - m.startEpochMs)}"
                        },
                        style = MaterialTheme.typography.bodySmall.tabular(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HourCard(
    recording: RecordingFile,
    badge: TranscriptBadge,
    progress: Float?,
    audioDeleted: Boolean,
    bookmarks: Int,
    preview: TranscriptPreview?,
    speakers: SpeakerDirectory,
    selected: Boolean,
    playing: Boolean,
    actions: TimelineActions,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    val part = RecordingGrouping.partNumber(recording.audio.name)
    var menu by remember { mutableStateOf(false) }
    val border = when {
        recording.inProgress -> BorderStroke(1.dp, extra.live.copy(alpha = 0.5f))
        selected -> BorderStroke(1.5.dp, scheme.primary)
        else -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f))
    }
    O2bCard(
        Modifier.fillMaxWidth(),
        onClick = if (recording.inProgress) null else ({ actions.onOpen(recording) }),
        border = border,
        contentPadding = PaddingValues(start = Spacing.md, end = Spacing.xxs, top = Spacing.sm, bottom = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Formatters.hourRange(recording.hourStartEpochMs), style = MaterialTheme.typography.titleMedium.tabular())
                    if (part > 1) {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.recordings_part, part),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (bookmarks > 0) {
                        Spacer(Modifier.width(Spacing.xs))
                        Icon(
                            Icons.Filled.Flag,
                            contentDescription = stringResource(R.string.bookmark_count, bookmarks),
                            tint = extra.meeting,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xxs))
                StatusBadge(recording, badge, progress)
            }
            when {
                recording.inProgress -> Unit
                audioDeleted -> Icon(
                    Icons.AutoMirrored.Filled.Notes,
                    contentDescription = stringResource(R.string.recordings_audio_deleted),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.sm),
                )
                else -> FilledTonalIconButton(onClick = { actions.onPlay(recording) }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play),
                    )
                }
            }
            if (!recording.inProgress && !audioDeleted) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                actions.onDelete(recording)
                            },
                        )
                    }
                }
            } else {
                Spacer(Modifier.width(Spacing.sm))
            }
        }
        if (preview != null && preview.text.isNotBlank()) {
            Text(
                preview.text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface.copy(alpha = 0.86f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs, end = Spacing.sm),
            )
        }
        val profiles: List<SpeakerProfile?> = remember(preview, speakers) {
            preview?.speakerIds.orEmpty().mapNotNull { speakers.resolve(it) }.distinctBy { it.id }
        }
        val meta = buildList {
            add(Formatters.duration(recording.durationMs))
            if (audioDeleted) add(stringResource(R.string.recordings_audio_deleted)) else add(Formatters.bytes(recording.sizeBytes))
            addAll(RecordingGrouping.inputNames(recording))
        }.joinToString(" · ")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs, end = Spacing.sm)) {
            if (profiles.isNotEmpty()) {
                val speakersText = pluralStringResource(R.plurals.speakers_count, profiles.size, profiles.size)
                AvatarStack(profiles, modifier = Modifier.semantics { contentDescription = speakersText })
                Spacer(Modifier.width(Spacing.xxs))
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Status-Plakette: läuft (Live), Transkript, wartet, wird transkribiert (x %), fehlgeschlagen. */
@Composable
private fun StatusBadge(recording: RecordingFile, badge: TranscriptBadge, progress: Float?) {
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    if (recording.inProgress) {
        Pill(
            stringResource(R.string.recordings_running),
            container = extra.liveContainer,
            content = extra.onLiveContainer,
            leading = { StatusDot(extra.live, pulsing = true) },
        )
        return
    }
    val text = badgeText(badge, progress) ?: return
    val (container, content) = when (badge) {
        TranscriptBadge.Done -> scheme.primaryContainer to scheme.onPrimaryContainer
        TranscriptBadge.Transcribing -> scheme.secondaryContainer to scheme.onSecondaryContainer
        TranscriptBadge.Failed -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Pill(text, container = container, content = content)
}

@Composable
private fun badgeText(badge: TranscriptBadge, progress: Float?): String? = when (badge) {
    TranscriptBadge.None -> null
    TranscriptBadge.Waiting -> stringResource(R.string.badge_waiting)
    TranscriptBadge.Transcribing -> stringResource(R.string.badge_transcribing, Formatters.percent(progress ?: 0f))
    TranscriptBadge.Done -> stringResource(R.string.badge_done)
    TranscriptBadge.Failed -> stringResource(R.string.badge_failed)
}
