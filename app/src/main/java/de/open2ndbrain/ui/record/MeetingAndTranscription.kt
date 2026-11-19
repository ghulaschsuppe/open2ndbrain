package de.open2ndbrain.ui.record

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.ui.Formatters

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
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var editTitle by remember { mutableStateOf(false) }
    val active = meeting != null
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (meeting != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Groups, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        meeting.title ?: stringResource(R.string.meeting_running),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editTitle = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.meeting_title_edit))
                    }
                }
                Text(
                    stringResource(R.string.meeting_since, Formatters.time(meeting.startEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (meeting != null) {
                    Button(
                        onClick = onEnd,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.meeting_end_running, Formatters.stopwatch(now - meeting.startEpochMs)))
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Groups, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_meeting_start))
                    }
                }
                OutlinedButton(
                    onClick = {
                        onBookmark()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, R.string.bookmark_saved, Toast.LENGTH_SHORT).show()
                    },
                    enabled = bookmarkEnabled,
                ) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.bookmark_action))
                }
            }
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

/** Dialog zum (Um-)Benennen von Meetings und Lesezeichen. */
@Composable
fun TitleDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit, label: Int = R.string.meeting_title_label) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(label)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Zeile mit dem Transkriptions-Status: läuft x %, n in Warteschlange, Echtzeitfaktor, Wartegrund. */
@Composable
fun TranscriptionCard(status: TranscriptionStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.transcription_card_title), style = MaterialTheme.typography.titleSmall)
            when {
                status.running -> {
                    Text(stringResource(R.string.transcription_running, Formatters.percent(status.progress)))
                    LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                }
                status.waitingReason != null -> Text(status.waitingReason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status.pendingCount == 0 -> Text(stringResource(R.string.transcription_idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(stringResource(R.string.transcription_scheduled), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val details = buildList {
                if (status.pendingCount > 0) add(pluralStringResource(R.plurals.transcription_queue, status.pendingCount, status.pendingCount))
                status.lastRealTimeFactor?.let { add(stringResource(R.string.transcription_rtf, Formatters.realTimeFactor(it))) }
            }
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
