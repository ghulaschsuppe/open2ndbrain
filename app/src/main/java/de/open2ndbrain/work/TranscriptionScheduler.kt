package de.open2ndbrain.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.EngineStatus
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.settings.TranscriptionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Ladezustand des Geräts (aus dem ACTION_BATTERY_CHANGED-Sticky-Broadcast). */
data class BatteryInfo(val charging: Boolean = false, val low: Boolean = false, val percent: Int = 100) {
    /** Unsere eigene Grenze (< 20 %, nicht ladend) – strenger als das System-„Akku schwach“. */
    val lowForTranscription: Boolean get() = low || TranscriptionPlanner.batteryLow(percent, charging)
}

/**
 * Plant die Transkription: beim App-Start, wenn eine Aufnahmedatei abgeschlossen wird, nach Modell-Download,
 * nach Meeting-Ende und stündlich als Sicherheitsnetz. Bedingungen: Akku nicht schwach; „Nur beim Laden“ → Ladegerät.
 * Veröffentlicht außerdem den Wartegrund im [TranscriptionStatusStore].
 */
class TranscriptionScheduler(context: Context, private val container: AppContainer) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workManager by lazy { WorkManager.getInstance(appContext) }
    private val _battery = MutableStateFlow(BatteryInfo())
    val battery: StateFlow<BatteryInfo> = _battery.asStateFlow()
    private val pendingCount = MutableStateFlow(0)
    private var started = false

    /** Einmal beim App-Start (Application.onCreate). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        registerBattery()
        registerScreen()
        // Nachtqualität: Hintergrundauftrag passend zur Einstellung, Status aktuell halten
        scope.launch {
            container.settings.settings.map { Triple(it.nightQuality, it.nightQualityModelId, it.nightQualityDays) }
                .distinctUntilChanged().collect { (enabled, _, _) ->
                    container.nightQuality.ensureScheduled(enabled)
                    runCatching { container.nightQuality.refresh() }
                }
        }
        scope.launch {
            val s = container.settings.current()
            ensurePeriodic(s.transcriptionMode)
            schedule()
        }
        // Moduswechsel → Bedingungen neu setzen.
        scope.launch {
            container.settings.settings.map { it.transcriptionMode }.distinctUntilChanged().drop(1).collect { mode ->
                ensurePeriodic(mode)
                schedule(constraintsChanged = true)
            }
        }
        // Eine Aufnahmedatei wurde abgeschlossen (aktuelle Datei wechselt) → anstoßen.
        // Läuft der Dienst, startet sein Runner sofort; sonst (nach kurzer Entprellung) WorkManager.
        scope.launch {
            container.recordingEngine.state.map { it.currentFile }.distinctUntilChanged().drop(1)
                .debounce(FILE_DEBOUNCE_MS)
                .collect { schedule() }
        }
        // Ladegerät/Akku geändert → Dienst-Runner prüft neu.
        scope.launch { _battery.drop(1).collect { if (container.transcriptionRunner.serviceActive) container.transcriptionRunner.trigger() } }
        // Modelle fertig installiert → anstoßen.
        scope.launch {
            container.modelManager.states.map { modelsReady(it) }.distinctUntilChanged().drop(1).collect { ready ->
                if (ready) schedule()
                refreshStatus()
            }
        }
        // Wartegrund aktuell halten.
        scope.launch {
            val recording = container.recordingEngine.state.map { it.status == EngineStatus.Recording || it.status == EngineStatus.MicBusy }
                .distinctUntilChanged()
            val minuteTicker = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(30_000)
                }
            }
            val inputs = combine(container.settings.settings, container.modelManager.states, _battery, pendingCount, container.transcriptionStatus.status) {
                    s, models, battery, pending, status ->
                Triple(s, modelsReady(models) to battery, maxOf(pending, status.pendingCount) to status.running)
            }
            combine(inputs, recording, minuteTicker) { (s, mb, pr), rec, now ->
                val (ready, battery) = mb
                val (pending, running) = pr
                val reason = TranscriptionPlanner.waitingReason(
                    s.transcriptionMode, ready, pending, running, battery.charging, battery.lowForTranscription, segmentRecording = rec,
                )
                reason?.let { reasonText(it, TranscriptionPlanner.nextBoundary(now, s.transcriptionIntervalMin, ZoneId.systemDefault())) }
            }.distinctUntilChanged().collect { text ->
                container.transcriptionStatus.update { if (it.waitingReason == text) it else it.copy(waitingReason = text) }
            }
        }
        scope.launch {
            while (true) {
                refreshPendingCount()
                delay(PENDING_REFRESH_MS)
            }
        }
    }

    /**
     * Lauf anstoßen. Läuft der Aufnahmedienst, übernimmt sein Runner sofort; sonst WorkManager – expedited ohne
     * Lade-/Akku-Bedingungen, wenn die Bedingungen gerade erfüllt sind, sonst als normaler Auftrag mit Bedingungen
     * ([constrained]). [ignoreCharging] („Jetzt transkribieren“) ignoriert „Nur beim Laden“ einmalig.
     */
    fun schedule(constraintsChanged: Boolean = false, ignoreCharging: Boolean = false, constrained: Boolean = false) {
        val runner = container.transcriptionRunner
        if (runner.serviceActive && !constrained) {
            if (ignoreCharging) runner.requestForce() else runner.trigger()
            refreshStatus()
            return
        }
        scope.launch {
            try {
                val s = container.settings.current()
                if (s.transcriptionMode == TranscriptionMode.Off && !ignoreCharging) return@launch
                val states = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first().map { it.state }
                val policy = TranscriptionPlanner.policy(states, constraintsChanged || ignoreCharging || constrained)
                val b = _battery.value
                val canRunNow = TranscriptionPlanner.runDecision(
                    s.transcriptionMode, modelsReady = true, charging = b.charging, batteryPercent = b.percent, force = ignoreCharging,
                ) is RunDecision.Run
                val builder = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                    .setInputData(workDataOf(TranscriptionWorker.KEY_FORCE to ignoreCharging))
                    .addTag(TAG)
                if (canRunNow && !constrained) {
                    // Expedited erlaubt keine Lade-/Akku-Bedingungen; der Worker prüft sie beim Start selbst.
                    builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                } else {
                    builder.setConstraints(constraints(s, ignoreCharging))
                }
                workManager.enqueueUniqueWork(UNIQUE_NAME, policy, builder.build())
                refreshPendingCount()
            } catch (e: Exception) {
                Log.w(TAG, "Planung fehlgeschlagen", e)
            }
        }
    }

    /**
     * „Jetzt transkribieren“: die laufende Datei sofort abschließen (`splitNow`) und die Warteschlange gleich
     * verarbeiten, „Nur beim Laden“ einmalig ignorieren.
     */
    fun transcribeNow() {
        container.recordingEngine.splitNow("manual")
        schedule(ignoreCharging = true)
    }

    /** Laufende Transkription anhalten (z. B. vom Nutzer). */
    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_NAME)
    }

    /** Warteschlangenlänge neu bestimmen (z. B. nach Ende eines Laufs). */
    fun refreshStatus() {
        scope.launch { refreshPendingCount() }
    }

    private suspend fun refreshPendingCount() {
        runCatching {
            container.transcripts.pending().count { !container.transcriptionFailures.isBlocked(it.audio) }
        }.onSuccess { n ->
            pendingCount.value = n
            container.transcriptionStatus.update { if (it.running) it else it.copy(pendingCount = n) }
        }
    }

    private fun ensurePeriodic(mode: TranscriptionMode) {
        if (mode == TranscriptionMode.Off) {
            workManager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TranscriptionKickWorker>(1, TimeUnit.HOURS).addTag(TAG).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun constraints(s: AppSettings, ignoreCharging: Boolean): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(s.transcriptionMode == TranscriptionMode.ChargingOnly && !ignoreCharging)
        .build()

    private fun modelsReady(states: Map<String, ModelState>): Boolean = AsrSelection.ready(states)

    private fun reasonText(r: WaitingReason, nextSegmentEnd: Long): String = if (r == WaitingReason.WaitingForSegment) {
        appContext.getString(R.string.waiting_segment, Formatters.time(nextSegmentEnd))
    } else appContext.getString(
        when (r) {
            WaitingReason.Off -> R.string.waiting_off
            WaitingReason.ModelMissing -> R.string.waiting_model_missing
            WaitingReason.WaitingForCharger -> R.string.waiting_charger
            WaitingReason.BatteryLow -> R.string.waiting_battery_low
            WaitingReason.WaitingForSegment -> R.string.waiting_off
        },
    )

    /** Bildschirm aus/an: Nachtqualität im Dienst sofort starten bzw. abbrechen. */
    private fun registerScreen() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (container.transcriptionRunner.serviceActive) container.transcriptionRunner.trigger()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = updateBattery(intent)
        }
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::updateBattery)
    }

    private fun updateBattery(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val low = intent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val info = BatteryInfo(plugged, low, percent)
        if (_battery.value != info) _battery.value = info
    }

    companion object {
        const val UNIQUE_NAME = "transcription"
        private const val PERIODIC_NAME = "transcription-periodic"
        private const val TAG = "TranscriptionScheduler"
        private const val FILE_DEBOUNCE_MS = 5_000L
        private const val PENDING_REFRESH_MS = 15 * 60_000L
    }
}

/** Stündliches Sicherheitsnetz: stößt nur die eigentliche (eindeutige) Transkription an. */
class TranscriptionKickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as O2bApp).container.transcriptionScheduler.schedule()
        return Result.success()
    }
}
