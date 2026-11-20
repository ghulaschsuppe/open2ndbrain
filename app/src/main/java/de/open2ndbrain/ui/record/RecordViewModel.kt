package de.open2ndbrain.ui.record

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.service.MeetingController
import de.open2ndbrain.work.TranscriptionRunStat
import de.open2ndbrain.service.DisplayStatus
import de.open2ndbrain.service.RecorderService
import de.open2ndbrain.service.displayStatus
import de.open2ndbrain.ui.recordings.RecordingGrouping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class StorageInfo(val recordingsBytes: Long = 0, val freeBytes: Long = 0) {
    /** Konzept 4.2a: unter 2 GB frei → warnen. */
    val low: Boolean get() = freeBytes in 1 until LOW_STORAGE_BYTES

    companion object {
        const val LOW_STORAGE_BYTES = 2L * 1024 * 1024 * 1024
    }
}

data class RecordUiState(
    val status: DisplayStatus = DisplayStatus.Off,
    val engine: EngineState = EngineState(),
    val pausedUntil: Long? = null,
    val micPreference: MicPreference = MicPreference.Auto,
    val storedTodayMs: Long = 0,
    val storage: StorageInfo = StorageInfo(),
    val now: Long = System.currentTimeMillis(),
    /** Dienst läuft im Vordergrund (Pause/Fortsetzen möglich); false auch bei gespeicherter Pause ohne Dienst. */
    val serviceRunning: Boolean = false,
)

class RecordViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as O2bApp).container

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    /** Einmal geteiltes Einlesen der Aufnahmen für Tagesdauer und Speicherbedarf. */
    private val recordings = container.recordings.observeRecordings()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val storedToday = recordings.map { list ->
        val zone = ZoneId.systemDefault()
        RecordingGrouping.storedMsOn(list, LocalDate.now(zone), zone)
    }

    private val storage = combine(
        recordings.map { list -> list.sumOf { it.sizeBytes } }.distinctUntilChanged(),
        flow {
            while (true) {
                emit(Unit)
                delay(60_000)
            }
        },
    ) { recordingsBytes, _ ->
        StorageInfo(
            recordingsBytes = recordingsBytes,
            freeBytes = runCatching { StatFs(container.audioRoot.path).availableBytes }.getOrDefault(0),
        )
    }.flowOn(Dispatchers.IO)

    private val base = combine(
        container.recordingEngine.state,
        container.recorderStatus.status,
        container.settings.settings,
    ) { engine, service, settings -> Triple(engine, service, settings) }

    val state: StateFlow<RecordUiState> = combine(base, storedToday, storage, clock) { (engine, service, settings), today, storage, now ->
        // Nach einem Neustart (Android 14+) läuft der Dienst evtl. nicht, die Nutzer-Pause gilt aber weiter.
        val pausedBySettings = !service.running && settings.recordingDesired && settings.isPausedAt(now)
        RecordUiState(
            status = displayStatus(engine, service, pausedBySettings),
            engine = engine,
            pausedUntil = service.pausedUntil ?: settings.pausedUntilEpochMs.takeIf { pausedBySettings },
            micPreference = settings.micPreference,
            // Die Engine zählt live mit; die Dateien liefern den Wert, wenn sie nicht läuft.
            storedTodayMs = maxOf(engine.storedMsToday, today),
            storage = storage,
            now = now,
            serviceRunning = service.running && !service.stopping,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    /** Startet aus der sichtbaren Activity. false = Berechtigung fehlt oder Android verweigert den Start. */
    fun start(): Boolean = RecorderService.start(getApplication())

    fun stop() = send(RecorderService.ACTION_STOP)
    fun pause() = send(RecorderService.ACTION_PAUSE)
    fun pauseShort() = send(RecorderService.ACTION_PAUSE_15)
    fun resume() = send(RecorderService.ACTION_RESUME)

    fun setMicPreference(pref: MicPreference) {
        viewModelScope.launch { container.settings.setMicPreference(pref) }
    }

    // ---------------------------------------------------------------- Meetings, Lesezeichen, Transkription

    val meeting: StateFlow<Marker?> = container.markers.activeMeeting
    val transcription: StateFlow<TranscriptionStatus> = container.transcriptionStatus.status

    fun startMeeting() = viewModelScope.launch { MeetingController.start(getApplication()) }
    fun endMeeting() = viewModelScope.launch { MeetingController.end(getApplication()) }

    fun setMeetingTitle(title: String) = viewModelScope.launch {
        val m = container.markers.activeMeeting.value ?: return@launch
        container.markers.update(m.copy(title = title.trim().ifEmpty { null }))
    }

    fun bookmark() = viewModelScope.launch { container.markers.addBookmark() }

    /** „Jetzt transkribieren“: aktuelle Datei abschließen und sofort verarbeiten („Nur beim Laden“ einmalig ignoriert). */
    fun transcribeNow() = container.transcriptionScheduler.transcribeNow()

    /** Zeile „Nachtqualität: 12 von 30 verbessert“ in der Transkriptions-Karte (null = ausblenden). */
    val night: StateFlow<de.open2ndbrain.ui.settings.NightProgress?> = container.nightQuality.status
        .map { de.open2ndbrain.ui.settings.nightProgress(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Karte „Welches Modell passt zu dir?“: mindestens ein Modell installiert, noch kein Test, nicht ausgeblendet. */
    val showMicTestCard: StateFlow<Boolean> = combine(container.settings.settings, container.modelManager.states, container.micTest.history) { s, st, h ->
        !s.micTestCardDismissed && h.isEmpty() && de.open2ndbrain.core.ModelCatalog.asrModels.any { st[it.id] is de.open2ndbrain.core.ModelState.Installed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissMicTestCard() = viewModelScope.launch { container.settings.dismissMicTestCard() }

    /** Diagnose: letzte Transkriptionsläufe (Echtzeitfaktor, Ladezeit, Threads, Gerät). */
    val transcriptionStats: StateFlow<List<TranscriptionRunStat>> = container.transcriptionStats.runs

    private fun send(action: String) {
        RecorderService.send(getApplication(), action)
    }
}
