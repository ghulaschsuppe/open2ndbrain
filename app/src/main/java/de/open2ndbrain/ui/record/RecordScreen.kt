package de.open2ndbrain.ui.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.R
import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.service.DisplayStatus
import de.open2ndbrain.service.RecorderNotifications
import de.open2ndbrain.service.isStorageFull
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.SystemSettings

@Composable
fun RecordScreen(modifier: Modifier = Modifier, vm: RecordViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    val transcription by vm.transcription.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var problem by remember { mutableStateOf<Int?>(null) }
    var showMicDialog by remember { mutableStateOf(false) }

    fun tryStart() {
        problem = if (vm.start()) null else R.string.record_start_failed
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) tryStart() else problem = R.string.record_mic_denied
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHeader(state)

        BigRecordButton(
            running = state.serviceRunning,
            onClick = {
                if (state.serviceRunning) {
                    vm.stop()
                } else if (SystemSettings.hasMic(context)) {
                    tryStart()
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )

        if (state.serviceRunning) {
            PauseControls(state, onPause = vm::pause, onPauseShort = vm::pauseShort, onResume = vm::resume)
        }

        MeetingCard(
            meeting = meeting,
            now = state.now,
            onStart = {
                vm.startMeeting()
                // Läuft keine Aufnahme oder ist sie pausiert: (fort)setzen – Start aus der sichtbaren App ist erlaubt.
                if (!state.serviceRunning || state.status == DisplayStatus.Paused) {
                    if (SystemSettings.hasMic(context)) tryStart() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onEnd = vm::endMeeting,
            onRename = vm::setMeetingTitle,
            onBookmark = vm::bookmark,
            bookmarkEnabled = state.serviceRunning && state.status != DisplayStatus.Paused,
        )

        problem?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(msg), color = MaterialTheme.colorScheme.onErrorContainer)
                    if (msg == R.string.record_mic_denied) {
                        TextButton(onClick = { SystemSettings.openAppDetails(context) }) {
                            Text(stringResource(R.string.open_app_settings))
                        }
                    }
                }
            }
        }

        LevelCard(level = state.engine.level, speech = state.engine.speechActive, active = state.status == DisplayStatus.Recording)

        MicCard(
            current = state.engine.currentInput,
            preference = state.micPreference,
            onChoose = { showMicDialog = true },
        )

        StatsCard(state)

        TranscriptionCard(transcription, onTranscribeNow = vm::transcribeNow)
    }

    if (showMicDialog) {
        MicDialog(
            inputs = state.engine.availableInputs,
            preference = state.micPreference,
            onSelect = {
                vm.setMicPreference(it)
                showMicDialog = false
            },
            onDismiss = { showMicDialog = false },
        )
    }
}

@Composable
private fun StatusHeader(state: RecordUiState) {
    val context = LocalContext.current
    val title = when (state.status) {
        DisplayStatus.Off -> stringResource(R.string.status_off)
        DisplayStatus.Starting -> stringResource(R.string.status_starting)
        DisplayStatus.Recording -> stringResource(R.string.status_recording)
        DisplayStatus.Paused -> RecorderNotifications.pausedTitle(context, state.pausedUntil)
        DisplayStatus.MicBusy -> stringResource(R.string.status_mic_busy)
        DisplayStatus.Error -> stringResource(
            if (isStorageFull(state.engine.errorMessage)) R.string.status_storage_full else R.string.status_error,
        )
        DisplayStatus.Stopping -> stringResource(R.string.status_stopping)
    }
    val subtitle = when (state.status) {
        DisplayStatus.Recording, DisplayStatus.MicBusy -> state.engine.sessionStartedAt?.let {
            stringResource(R.string.record_running_since, Formatters.duration(state.now - it))
        }
        DisplayStatus.Error -> if (isStorageFull(state.engine.errorMessage)) {
            stringResource(R.string.storage_full_hint)
        } else {
            state.engine.errorMessage
        }
        DisplayStatus.Paused -> if (!state.serviceRunning) stringResource(R.string.record_paused_off_hint) else null
        DisplayStatus.Off -> stringResource(R.string.record_off_hint)
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BigRecordButton(running: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "recordButtonColor",
    )
    val content = if (running) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val label = stringResource(if (running) R.string.record_stop else R.string.record_start)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(176.dp)
                .semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PauseControls(state: RecordUiState, onPause: () -> Unit, onPauseShort: () -> Unit, onResume: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.status == DisplayStatus.Paused) {
            Button(onClick = onResume) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_resume))
            }
        } else {
            OutlinedButton(onClick = onPause) {
                Icon(Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_pause))
            }
            OutlinedButton(onClick = onPauseShort) {
                Icon(Icons.Filled.Timer, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_pause_15))
            }
        }
    }
}

@Composable
private fun LevelCard(level: Float, speech: Boolean, active: Boolean) {
    val shown by animateFloatAsState(if (active) level.coerceIn(0f, 1f) else 0f, tween(120), label = "level")
    val speechColor by animateColorAsState(
        if (speech && active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "speech",
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.record_level), style = MaterialTheme.typography.titleSmall)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shown)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(speechColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (speech && active) R.string.record_speech_detected else R.string.record_no_speech),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MicCard(current: AudioInputInfo?, preference: MicPreference, onChoose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(current?.type?.let(::iconFor) ?: Icons.Filled.Mic, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.name ?: stringResource(R.string.record_mic_none), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.record_mic_preference, preferenceLabel(preference)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChoose) { Text(stringResource(R.string.record_mic_choose)) }
        }
    }
}

@Composable
private fun StatsCard(state: RecordUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.record_stored_today, Formatters.duration(state.storedTodayMs)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.record_storage,
                    Formatters.bytes(state.storage.recordingsBytes),
                    Formatters.bytes(state.storage.freeBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.storage.low) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.record_storage_low), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun MicDialog(
    inputs: List<AudioInputInfo>,
    preference: MicPreference,
    onSelect: (MicPreference) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList {
        add(MicPreference.Auto)
        add(MicPreference.BuiltInOnly)
        inputs.filter { it.type != InputType.BuiltIn }
            .map { MicPreference.Specific(it.name, it.type) }
            .distinct()
            .forEach(::add)
        if (preference is MicPreference.Specific && preference !in this) add(preference)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_mic_dialog_title)) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == preference, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == preference, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(preferenceLabel(option))
                    }
                }
                if (inputs.isEmpty()) {
                    Text(
                        stringResource(R.string.record_mic_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.record_mic_sco_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun preferenceLabel(pref: MicPreference): String = when (pref) {
    MicPreference.Auto -> stringResource(R.string.mic_pref_auto)
    MicPreference.BuiltInOnly -> stringResource(R.string.mic_pref_builtin)
    is MicPreference.Specific -> pref.productName
}

private fun iconFor(type: InputType): ImageVector = when (type) {
    InputType.BuiltIn -> Icons.Filled.Smartphone
    InputType.BluetoothSco, InputType.BluetoothLe -> Icons.Filled.Bluetooth
    InputType.Wired -> Icons.Filled.Headset
    InputType.Usb -> Icons.Filled.Usb
    InputType.Other -> Icons.Filled.Mic
}
