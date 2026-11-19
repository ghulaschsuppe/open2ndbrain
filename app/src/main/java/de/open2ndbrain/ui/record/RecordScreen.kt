package de.open2ndbrain.ui.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.R
import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.EngineStatus
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.O2bApp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Star
import de.open2ndbrain.work.TranscriptionRunStat
import de.open2ndbrain.service.DisplayStatus
import de.open2ndbrain.service.RecorderNotifications
import de.open2ndbrain.service.isStorageFull
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.SystemSettings
import de.open2ndbrain.ui.components.ChipRow
import de.open2ndbrain.ui.components.ContentWidth
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.ScreenTitle
import de.open2ndbrain.ui.components.StatTile
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RecordScreen(modifier: Modifier = Modifier, onOpenModels: () -> Unit = {}, vm: RecordViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    val transcription by vm.transcription.collectAsStateWithLifecycle()
    val runs by vm.transcriptionStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val modelStates by remember { (context.applicationContext as O2bApp).container.modelManager.states }.collectAsStateWithLifecycle()
    var hintDismissed by rememberSaveable { mutableStateOf(false) }
    val modelHint = if (hintDismissed) null else betterModelHint(modelStates)
    var problem by remember { mutableStateOf<Int?>(null) }
    var showMicDialog by remember { mutableStateOf(false) }

    fun tryStart() {
        problem = if (vm.start()) null else R.string.record_start_failed
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) tryStart() else problem = R.string.record_mic_denied
    }

    fun startWithPermission() {
        if (SystemSettings.hasMic(context)) tryStart() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    RecordContent(
        state = state,
        meeting = meeting,
        transcription = transcription,
        problem = problem,
        modelHint = modelHint,
        modifier = modifier,
        runs = runs,
        actions = RecordActions(
            onToggle = { if (state.serviceRunning) vm.stop() else startWithPermission() },
            onPause = vm::pause,
            onPauseShort = vm::pauseShort,
            onResume = vm::resume,
            onChooseMic = { showMicDialog = true },
            onStartMeeting = {
                vm.startMeeting()
                // Läuft keine Aufnahme oder ist sie pausiert: (fort)setzen – Start aus der sichtbaren App ist erlaubt.
                if (!state.serviceRunning || state.status == DisplayStatus.Paused) startWithPermission()
            },
            onEndMeeting = { vm.endMeeting() },
            onRenameMeeting = { vm.setMeetingTitle(it) },
            onBookmark = { vm.bookmark() },
            onOpenAppSettings = { SystemSettings.openAppDetails(context) },
            onTranscribeNow = vm::transcribeNow,
            onOpenModels = onOpenModels,
            onDismissModelHint = { hintDismissed = true },
        ),
    )

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

/** Alle Aktionen des „Jetzt“-Tabs (zustandslos, für Previews und Screenshots). */
class RecordActions(
    val onToggle: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onPauseShort: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onChooseMic: () -> Unit = {},
    val onStartMeeting: () -> Unit = {},
    val onEndMeeting: () -> Unit = {},
    val onRenameMeeting: (String) -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onTranscribeNow: (() -> Unit)? = null,
    val onOpenModels: () -> Unit = {},
    val onDismissModelHint: () -> Unit = {},
)

/** „Jetzt“: Aufnahme-Knopf als Hero, Status- und Mikrofon-Chip, Kennzahlen, Meeting/Merken, Transkription. */
@Composable
fun RecordContent(
    state: RecordUiState,
    meeting: Marker?,
    transcription: TranscriptionStatus,
    problem: Int?,
    actions: RecordActions,
    modifier: Modifier = Modifier,
    runs: List<TranscriptionRunStat> = emptyList(),
    modelHint: ModelSpec? = null,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ContentWidth {
            Column {
                ScreenTitle(
                    title = stringResource(R.string.now_title),
                    subtitle = remember(state.now / 60_000) { todayLabel() },
                )
                Column(
                    Modifier.padding(horizontal = Spacing.screen).padding(bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    HeroSection(state, actions)

                    if (modelHint != null) ModelHintCard(modelHint, actions.onOpenModels, actions.onDismissModelHint)

                    AnimatedVisibility(visible = problem != null) {
                        problem?.let { ProblemCard(it, actions.onOpenAppSettings) }
                    }

                    MeetingCard(
                        meeting = meeting,
                        now = state.now,
                        onStart = actions.onStartMeeting,
                        onEnd = actions.onEndMeeting,
                        onRename = actions.onRenameMeeting,
                        onBookmark = actions.onBookmark,
                        bookmarkEnabled = state.serviceRunning && state.status != DisplayStatus.Paused,
                    )

                    StatsRow(state)

                    TranscriptionCard(transcription, onTranscribeNow = actions.onTranscribeNow, runs = runs)
                }
            }
        }
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private fun todayLabel(): String = DAY_FORMAT.format(LocalDate.now())

@Composable
private fun HeroSection(state: RecordUiState, actions: RecordActions) {
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RecordOrb(
            status = state.status,
            running = state.serviceRunning,
            level = state.engine.level,
            speech = state.engine.speechActive,
            onClick = actions.onToggle,
        )
        Spacer(Modifier.height(Spacing.xs))
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "statusTitle",
        ) { t ->
            Text(
                t,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = if (state.status == DisplayStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xxs, start = Spacing.md, end = Spacing.md),
            )
        }
        Spacer(Modifier.height(Spacing.md))
        ChipRow(Modifier.fillMaxWidth()) {
            SpeechPill(state)
            MicPill(state.engine.currentInput, state.micPreference, actions.onChooseMic)
        }
        if (state.serviceRunning) {
            Spacer(Modifier.height(Spacing.md))
            PauseControls(state, actions)
        }
    }
}

/** Chip „Sprache erkannt / Still“ während der Aufnahme, sonst der Zustand in einem Wort. */
@Composable
private fun SpeechPill(state: RecordUiState) {
    val extra = O2bTheme.extra
    val recording = state.status == DisplayStatus.Recording
    val speech = recording && state.engine.speechActive
    val (text, dot) = when {
        speech -> stringResource(R.string.record_speech_detected) to MaterialTheme.colorScheme.primary
        recording -> stringResource(R.string.record_listening) to extra.live
        state.status == DisplayStatus.Paused -> stringResource(R.string.status_paused) to MaterialTheme.colorScheme.secondary
        state.status == DisplayStatus.MicBusy -> stringResource(R.string.tile_mic_busy) to extra.meeting
        state.status == DisplayStatus.Error -> stringResource(R.string.status_error) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.record_idle_chip) to MaterialTheme.colorScheme.outline
    }
    Pill(
        text = text,
        leading = { StatusDot(dot, pulsing = recording) },
    )
}

@Composable
private fun MicPill(current: AudioInputInfo?, preference: MicPreference, onClick: () -> Unit) {
    val label = current?.name ?: preferenceLabel(preference)
    val description = stringResource(R.string.record_mic_chip_desc, label)
    Pill(
        text = label,
        icon = current?.type?.let(::iconFor) ?: Icons.Filled.Mic,
        onClick = onClick,
        modifier = Modifier.semantics { this.contentDescription = description },
    )
}

@Composable
private fun PauseControls(state: RecordUiState, actions: RecordActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        if (state.status == DisplayStatus.Paused) {
            Button(onClick = actions.onResume, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.action_resume))
            }
        } else {
            FilledTonalButton(onClick = actions.onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.action_pause))
            }
            OutlinedButton(onClick = actions.onPauseShort, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Timer, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.action_pause_15))
            }
        }
    }
}

/**
 * Empfohlenes Spracherkennungsmodell, wenn es noch nicht geladen ist (und gerade nicht lädt).
 * Sobald das ModelsViewModel das aktive Modell liefert, kann hier zusätzlich „aktiv ≠ empfohlen“ geprüft werden.
 */
fun betterModelHint(states: Map<String, ModelState>): ModelSpec? =
    ModelCatalog.asrModels.firstOrNull { it.recommended }?.takeIf { states[it.id] == null || states[it.id] == ModelState.NotInstalled }

@Composable
private fun ModelHintCard(spec: ModelSpec, onOpen: () -> Unit, onDismiss: () -> Unit) {
    O2bCard(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        border = null,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = O2bTheme.extra.meeting)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.model_hint_title, spec.displayName), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.model_hint_text, Formatters.bytes(spec.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_hint_later)) }
            Spacer(Modifier.width(Spacing.xs))
            FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.model_hint_open)) }
        }
    }
}

@Composable
private fun ProblemCard(msg: Int, onOpenSettings: () -> Unit) {
    O2bCard(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = null,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(stringResource(msg), style = MaterialTheme.typography.bodyMedium)
                if (msg == R.string.record_mic_denied) {
                    TextButton(onClick = onOpenSettings, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                        Text(stringResource(R.string.open_app_settings), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsRow(state: RecordUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        StatTile(
            label = stringResource(R.string.stat_spoken_today),
            value = Formatters.duration(state.storedTodayMs),
            icon = Icons.Outlined.RecordVoiceOver,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(R.string.stat_recordings),
            value = Formatters.bytes(state.storage.recordingsBytes),
            icon = Icons.Outlined.Folder,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(if (state.storage.low) R.string.stat_free_low else R.string.stat_free),
            value = Formatters.bytes(state.storage.freeBytes),
            icon = Icons.Filled.SdStorage,
            tint = O2bTheme.extra.meeting,
            warning = state.storage.low,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
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
        icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
        title = { Text(stringResource(R.string.record_mic_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(selected = option == preference, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = Spacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == preference, onClick = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Icon(
                            when (option) {
                                MicPreference.Auto -> Icons.Filled.Mic
                                MicPreference.BuiltInOnly -> Icons.Filled.Smartphone
                                is MicPreference.Specific -> iconFor(option.type)
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(preferenceLabel(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (inputs.isEmpty()) {
                    Text(
                        stringResource(R.string.record_mic_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                Text(
                    stringResource(R.string.record_mic_sco_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
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

internal fun iconFor(type: InputType): ImageVector = when (type) {
    InputType.BuiltIn -> Icons.Filled.Smartphone
    InputType.BluetoothSco, InputType.BluetoothLe -> Icons.Filled.Bluetooth
    InputType.Wired -> Icons.Filled.Headset
    InputType.Usb -> Icons.Filled.Usb
    InputType.Other -> Icons.Filled.Mic
}

// ---------------------------------------------------------------- Beispieldaten für Previews und Screenshots

object RecordSamples {
    private const val NOW = 1_763_560_800_000L // 19.11.2025, 15:00 MEZ

    val recording = RecordUiState(
        status = DisplayStatus.Recording,
        engine = EngineState(
            status = EngineStatus.Recording,
            currentInput = AudioInputInfo(7, "Pixel Buds Pro", InputType.BluetoothSco),
            level = 0.55f,
            speechActive = true,
            sessionStartedAt = NOW - (3 * 60 + 12) * 60_000L,
        ),
        storedTodayMs = (2 * 60 + 47) * 60_000L,
        storage = StorageInfo(recordingsBytes = 1_310_000_000, freeBytes = 41_800_000_000),
        now = NOW,
        serviceRunning = true,
    )

    val off = RecordUiState(storage = StorageInfo(recordingsBytes = 1_310_000_000, freeBytes = 41_800_000_000), now = NOW)

    val meeting = Marker(
        id = "m1",
        type = MarkerType.Meeting,
        startEpochMs = NOW - 23 * 60_000L - 10_000,
        title = "Wochenplanung Team",
        createdAtEpochMs = NOW,
    )

    val transcribing = TranscriptionStatus(
        running = true,
        currentFile = "2025-11-19_13-00.ogg",
        progress = 0.62f,
        pendingCount = 3,
        lastRealTimeFactor = 0.14f,
    )
}

@Preview(name = "Jetzt – dunkel", showBackground = true, backgroundColor = 0xFF0B1220, heightDp = 1100)
@Composable
private fun RecordPreviewDark() = de.open2ndbrain.ui.theme.O2bTheme(darkTheme = true) {
    androidx.compose.material3.Surface {
        RecordContent(RecordSamples.recording, RecordSamples.meeting, RecordSamples.transcribing, null, RecordActions())
    }
}

@Preview(name = "Jetzt – hell, aus", showBackground = true, heightDp = 1000)
@Composable
private fun RecordPreviewLight() = de.open2ndbrain.ui.theme.O2bTheme(darkTheme = false) {
    androidx.compose.material3.Surface {
        RecordContent(RecordSamples.off, null, TranscriptionStatus(), null, RecordActions())
    }
}
