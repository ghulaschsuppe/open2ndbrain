package de.open2ndbrain.work

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.AsrEngine
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.ui.recordings.TranscriptFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Anzeige „Nachtqualität: 12 von 30 Aufnahmen verbessert“. */
data class NightQualityStatus(
    val enabled: Boolean = false,
    /** Modell der Nachtqualität (null = keins installiert). */
    val modelId: String? = null,
    /** Aufnahmen im Zeitraum, deren Transkript bereits mit dem Nachtmodell erstellt wurde. */
    val improved: Int = 0,
    /** Aufnahmen im Zeitraum mit Transkript. */
    val total: Int = 0,
    val running: Boolean = false,
    /** Das Nachtmodell ist dasselbe wie das normale – es gibt nichts zu verbessern. */
    val sameAsDaytimeModel: Boolean = false,
)

/** Reine Logik der Nachtqualität (unit-getestet). */
object NightQualityLogic {
    /** Aufnahme mit dem Modell, mit dem ihr aktuelles Transkript erstellt wurde (null = kein Transkript). */
    data class Entry(val recording: RecordingFile, val transcriptModelId: String?)

    /**
     * Nachtmodell: das ausdrücklich gewählte, wenn installiert; sonst das genaueste installierte Modell laut Benchmark
     * (Alltags-WER), ohne die experimentelle Systemerkennung.
     */
    fun pickModel(explicit: String?, installed: Set<String>): String? {
        if (explicit != null && explicit in installed) return explicit
        return ModelCatalog.asrModels
            .filter { it.id in installed && it.asrEngine != AsrEngine.AndroidSystem && it.benchmark != null }
            .minByOrNull { it.benchmark!!.werEverydayPct }?.id
    }

    /** Läuft nur am Ladegerät bei ausgeschaltetem Bildschirm – nachts (23–7 Uhr) oder wenn das Gerät ruht. */
    fun allowed(enabled: Boolean, charging: Boolean, interactive: Boolean, deviceIdle: Boolean, hour: Int): Boolean =
        enabled && charging && !interactive && (hour >= 23 || hour < 7 || deviceIdle)

    private fun inWindow(e: Entry, days: Int, now: Long) = e.recording.hourStartEpochMs >= now - days * DAY_MS

    /** Zu verbessernde Aufnahmen: Transkript mit anderem Modell, im Zeitraum, Audio vorhanden; neueste zuerst. */
    fun candidates(entries: List<Entry>, model: String, days: Int, now: Long, blocked: (RecordingFile) -> Boolean = { false }): List<RecordingFile> =
        entries.filter {
            it.transcriptModelId != null && it.transcriptModelId != model && !it.recording.inProgress &&
                inWindow(it, days, now) && !blocked(it.recording)
        }
            .sortedWith(compareByDescending<Entry> { it.recording.hourStartEpochMs }.thenByDescending { it.recording.audio.name })
            .map { it.recording }

    fun progress(entries: List<Entry>, model: String, days: Int, now: Long): Pair<Int, Int> {
        val window = entries.filter { it.transcriptModelId != null && inWindow(it, days, now) }
        return window.count { it.transcriptModelId == model } to window.size
    }

    const val DAY_MS = 24L * 3_600_000L
}

/**
 * Nachtqualität: verbessert vorhandene Transkripte mit einem genaueren (langsameren) Modell, ohne sie vorher zu
 * löschen – das neue Transkript ersetzt das alte erst nach erfolgreichem Schreiben (atomar).
 */
class NightQuality(private val context: Context, private val c: AppContainer) {
    private val _status = MutableStateFlow(NightQualityStatus())
    val status: StateFlow<NightQualityStatus> = _status.asStateFlow()

    /** Fehlversuche getrennt von der normalen Warteschlange. */
    private val failures = TranscriptionFailures(File(context.filesDir, "night-quality-failures.json"))

    /** Modell-ID je Transkriptdatei (Pfad → (Änderungszeit, Modell)), damit nicht jedes Mal alles gelesen wird. */
    private val modelCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    suspend fun pending(): List<RecordingFile> {
        val s = c.settings.current()
        val model = model(s, installed()) ?: return emptyList()
        val entries = entries(s.nightQualityDays)
        return NightQualityLogic.candidates(entries, model, s.nightQualityDays, System.currentTimeMillis()) { failures.isBlocked(it.audio) }
    }

    fun onImproved(rec: RecordingFile) {
        failures.clear(rec.audio)
        modelCache.remove(TranscriptFiles.transcriptFileFor(rec.audio).path)
    }

    fun onFailed(rec: RecordingFile) {
        if (rec.audio.exists()) failures.increment(rec.audio)
    }

    fun setRunning(running: Boolean) = _status.update { it.copy(running = running) }

    /** Status neu berechnen (Einstellung geändert, nach einem Lauf, beim Öffnen der Anzeige). */
    suspend fun refresh() {
        val s = c.settings.current()
        val installed = installed()
        val model = model(s, installed)
        val active = de.open2ndbrain.models.AsrSelection.effective(s.asrModelId, installed)
        val (improved, total) = if (model != null) {
            NightQualityLogic.progress(entries(s.nightQualityDays), model, s.nightQualityDays, System.currentTimeMillis())
        } else {
            0 to 0
        }
        _status.update {
            it.copy(enabled = s.nightQuality, modelId = model, improved = improved, total = total, sameAsDaytimeModel = model == active)
        }
    }

    /** Darf jetzt (Gerätezustand) nachts verbessert werden? */
    suspend fun allowedNow(): Boolean {
        val s = c.settings.current()
        if (!s.nightQuality || model(s, installed()) == null) return false
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        val hour = Instant.now().atZone(ZoneId.systemDefault()).hour
        return NightQualityLogic.allowed(true, c.transcriptionScheduler.battery.value.charging, pm.isInteractive, pm.isDeviceIdleMode, hour)
    }

    /** Hintergrundauftrag (wenn der Aufnahmedienst nicht läuft): stündlich, nur am Ladegerät und im Ruhezustand. */
    fun ensureScheduled(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<NightQualityWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun installed() = de.open2ndbrain.models.AsrSelection.installedIds(c.modelManager.states.value)

    private suspend fun entries(days: Int): List<NightQualityLogic.Entry> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * NightQualityLogic.DAY_MS
        c.recordings.observeRecordings().first()
            .filter { !it.inProgress && it.hourStartEpochMs >= cutoff && it.audio.isFile }
            .map { NightQualityLogic.Entry(it, transcriptModel(it)) }
    }

    private fun transcriptModel(rec: RecordingFile): String? {
        val f = TranscriptFiles.transcriptFileFor(rec.audio)
        if (!f.isFile) return null
        val mod = f.lastModified()
        modelCache[f.path]?.let { (m, id) -> if (m == mod) return id }
        val id = TranscriptFiles.decode(f)?.asrModelId
        modelCache[f.path] = mod to id
        return id
    }

    companion object {
        const val UNIQUE_NAME = "night-quality"

        fun model(s: AppSettings, installed: Set<String>): String? = NightQualityLogic.pickModel(s.nightQualityModelId, installed)
    }
}

/** WorkManager-Rückfall der Nachtqualität (Dienst läuft nicht). */
class NightQualityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as O2bApp).container
        val night = c.nightQuality
        if (c.transcriptionRunner.serviceActive || !night.allowedNow()) return Result.success()
        night.setRunning(true)
        try {
            c.transcriptionRunner.run(
                TranscriptionRunner.Origin.Worker,
                canContinue = { !isStopped && night.allowedNow() && !c.transcriptionRunner.serviceActive },
                mode = TranscriptionRunner.Mode.Night,
            )
        } finally {
            night.setRunning(false)
            night.refresh()
        }
        return Result.success()
    }
}
