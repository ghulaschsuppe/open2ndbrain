package de.open2ndbrain.ui.record

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.work.TranscriptionRunStat
import de.open2ndbrain.ui.settings.NightProgress
import de.open2ndbrain.ui.settings.NightProgressLine
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.IconBubble
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.theme.O2bTextStyles
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular

/** Meeting starten/beenden (mit laufender Dauer und Titel) und „Merken“ (Lesezeichen, 30 s Rückblick). */
@Composable
fun MeetingCard(
    meeting: Marker?,
    now: Long,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onRename: (String) -> Unit,
    onBookmark: () -> Unit,
    bookmarkEnabled: Boolean,
) {
    val extra = O2bTheme.extra
    val haptic = LocalHapticFeedback.current
    var editTitle by remember { mutableStateOf(false) }
    val active = meeting != null

    O2bCard(
        Modifier.fillMaxWidth().animateContentSize(),
        color = if (active) extra.meetingContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (active) extra.onMeetingContainer else MaterialTheme.colorScheme.onSurface,
        border = if (active) BorderStroke(1.dp, extra.meeting.copy(alpha = 0.45f)) else null,
    ) {
        AnimatedContent(targetState = meeting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "meeting") { m ->
            if (m != null) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(extra.meeting, pulsing = true)
                        Spacer(Modifier.width(Spacing.xs))
                        SectionLabel(stringResource(R.string.meeting_running), color = extra.meeting, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editTitle = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.meeting_title_edit))
                        }
                    }
                    Text(
                        m.title ?: stringResource(R.string.meeting_untitled),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = Spacing.xxs)) {
                        Text(
                            Formatters.stopwatch(now - m.startEpochMs),
                            style = MaterialTheme.typography.headlineLarge.tabular(),
                            color = extra.meeting,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.meeting_since, Formatters.time(m.startEpochMs)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.Filled.Groups, tint = extra.meeting)
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.meeting_card_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.meeting_card_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (meeting != null) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEnd()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = extra.meeting, contentColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.meeting_end_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = extra.meetingContainer,
                        contentColor = extra.onMeetingContainer,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.action_meeting_start), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            BookmarkButton(onBookmark, bookmarkEnabled, onAmber = active)
        }
    }
    if (editTitle && meeting != null) {
        TitleDialog(
            initial = meeting.title.orEmpty(),
            onSave = {
                onRename(it)
                editTitle = false
            },
            onDismiss = { editTitle = false },
        )
    }
}

@Composable
private fun BookmarkButton(onBookmark: () -> Unit, enabled: Boolean, onAmber: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val extra = O2bTheme.extra
    OutlinedButton(
        onClick = {
            onBookmark()
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            Toast.makeText(context, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
        },
        enabled = enabled,
        border = BorderStroke(1.dp, if (enabled) extra.meeting.copy(alpha = if (onAmber) 0.8f else 0.6f) else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (onAmber) extra.onMeetingContainer else extra.meeting),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.bookmark_action))
    }
}

/** Dialog zum (Um-)Benennen von Meetings und Lesezeichen. */
@Composable
fun TitleDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, label: Int = R.string.meeting_title_label) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(label)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, shape = MaterialTheme.shapes.medium) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Transkriptions-Karte: Fortschrittsring, Status, Warteschlange, Echtzeitfaktor, Wartegrund.
 * [onTranscribeNow] (optional) zeigt „Jetzt transkribieren“, wenn Dateien warten.
 */
@Composable
fun TranscriptionCard(
    status: TranscriptionStatus,
    onTranscribeNow: (() -> Unit)? = null,
    runs: List<TranscriptionRunStat> = emptyList(),
    night: NightProgress? = null,
) {
    val scheme = MaterialTheme.colorScheme
    O2bCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(status)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                SectionLabel(stringResource(R.string.transcription_card_title))
                val headline = when {
                    status.running -> stringResource(R.string.transcription_running, Formatters.percent(status.progress))
                    status.waitingReason != null -> status.waitingReason
                    status.pendingCount == 0 -> stringResource(R.string.transcription_idle)
                    else -> stringResource(R.string.transcription_scheduled)
                }
                Text(headline, style = MaterialTheme.typography.titleMedium.tabular())
                val details = buildList {
                    if (status.pendingCount > 0) add(pluralStringResource(R.plurals.transcription_queue, status.pendingCount, status.pendingCount))
                    // Mit Laufstatistik steht der Echtzeitfaktor unten in der Diagnosezeile.
                    if (runs.isEmpty()) status.lastRealTimeFactor?.let { add(stringResource(R.string.transcription_rtf, Formatters.realTimeFactor(it))) }
                }
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        status.lastError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        night?.let { NightProgressLine(it, Modifier.padding(top = Spacing.sm)) }
        if (runs.isNotEmpty()) RunStats(runs)
        if (onTranscribeNow != null && !status.running) {
            FilledTonalButton(
                onClick = onTranscribeNow,
                modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.meeting_transcribe_now))
            }
        }
    }
}

/** Dezente Diagnose: letzter Lauf in einer Zeile, aufklappbar zu den letzten Läufen mit Gerät. */
@Composable
private fun RunStats(runs: List<TranscriptionRunStat>) {
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val last = runs.first()
    Column(
        Modifier
            .padding(top = Spacing.sm)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerHigh)
            .clickable(role = Role.Button) { open = !open }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
            Icon(Icons.Filled.Speed, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(
                stringResource(R.string.transcription_last_run, runSummary(last)),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (open) R.string.collapse else R.string.expand),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            runs.take(6).forEach { r ->
                Row(Modifier.padding(top = Spacing.xxs)) {
                    Text(
                        Formatters.time(r.atEpochMs),
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        "${Formatters.duration(r.audioMs)} → ${Formatters.duration(r.processingMs)} · ${runSummary(r)}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = scheme.onSurface,
                    )
                }
            }
            Text(
                stringResource(R.string.transcription_device, last.device),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun runSummary(r: TranscriptionRunStat): String = buildList {
    r.realTimeFactor?.let { add(stringResource(R.string.transcription_rtf, Formatters.realTimeFactor(it))) }
    if (r.loadMs > 0) add(stringResource(R.string.transcription_load, Formatters.seconds(r.loadMs)))
    add(pluralStringResource(R.plurals.settings_threads_value, r.threads, r.threads))
}.joinToString(" · ")

@Composable
private fun ProgressRing(status: TranscriptionStatus) {
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (status.running) status.progress.coerceIn(0f, 1f) else 0f, label = "ring")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        when {
            status.running -> {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 5.dp,
                    trackColor = scheme.surfaceContainerHighest,
                )
                Text(
                    "${(progress * 100).toInt()}",
                    style = O2bTextStyles.metric.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize),
                )
            }
            else -> {
                val done = status.pendingCount == 0 && status.waitingReason == null
                IconBubble(
                    when {
                        done -> Icons.Filled.CheckCircle
                        status.waitingReason != null -> Icons.Filled.HourglassTop
                        else -> Icons.AutoMirrored.Filled.Notes
                    },
                    tint = if (done) O2bTheme.extra.success else scheme.secondary,
                    size = 56.dp,
                )
            }
        }
    }
}
