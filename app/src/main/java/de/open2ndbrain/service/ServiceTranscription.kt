package de.open2ndbrain.service

import android.util.Log
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.work.RunDecision
import de.open2ndbrain.work.TranscriptionPlanner
import de.open2ndbrain.work.TranscriptionRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Transkription direkt im Aufnahmedienst: Solange der Mikrofon-Vordergrunddienst läuft, ist der Prozess ohnehin
 * „im Vordergrund“ – die Warteschlange wird sofort abgearbeitet, sobald eine Datei abgeschlossen ist (ohne auf
 * WorkManager zu warten). Außerdem schließt der Dienst die laufende Datei im eingestellten Takt (`splitNow`), damit
 * Transkripte spätestens nach einem Abschnitt (Standard 15 min) + Rechenzeit vorliegen.
 */
class ServiceTranscription(private val c: AppContainer) {
    private val runner get() = c.transcriptionRunner
    private var jobs: List<Job> = emptyList()

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        runner.serviceActive = true
        jobs = listOf(
            scope.launch(Dispatchers.Default) { processLoop() },
            scope.launch(Dispatchers.Default) { safetyTicker() },
            scope.launch(Dispatchers.Default) { splitLoop() },
        )
        runner.trigger()
    }

    /** Dienst endet: laufende Transkription wird abgebrochen (über den Scope), WorkManager übernimmt wieder. */
    fun stop() {
        if (!runner.serviceActive) return
        runner.serviceActive = false
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        c.transcriptionScheduler.schedule()
    }

    private suspend fun decide(): RunDecision {
        val s = c.settings.current()
        val b = c.transcriptionScheduler.battery.value
        val states = c.modelManager.states.value
        val ready = AsrSelection.ready(states)
        return TranscriptionPlanner.runDecision(s.transcriptionMode, ready, b.charging, b.percent, runner.forceRequested)
    }

    private suspend fun processLoop() = kotlinx.coroutines.coroutineScope {
        for (ignored in runner.triggers) {
            if (decide() !is RunDecision.Run) continue
            val job = launch { runner.run(TranscriptionRunner.Origin.Service, canContinue = { decide() is RunDecision.Run }) }
            // Bedingungen während einer Datei weggefallen (Ladegerät ab bei „Nur beim Laden“, Akku < 20 %): abbrechen.
            val watcher = launch {
                while (job.isActive) {
                    delay(WATCH_INTERVAL_MS)
                    if (decide() !is RunDecision.Run) {
                        Log.i(TAG, "Bedingungen nicht mehr erfüllt – Transkription pausiert")
                        job.cancel()
                    }
                }
            }
            job.join()
            watcher.cancel()
            c.transcriptionScheduler.refreshStatus()
        }
    }

    /** Sicherheitsnetz: regelmäßig nachsehen, ob etwas offen ist (z. B. nach Ende eines Fehler-Backoffs). */
    private suspend fun safetyTicker() {
        while (true) {
            delay(SAFETY_INTERVAL_MS)
            runner.trigger()
        }
    }

    /** Laufende Datei zu den Takt-Grenzen (an der Uhr ausgerichtet) abschließen; volle Stunden macht die Engine selbst. */
    private suspend fun splitLoop() {
        c.settings.settings.map { it.transcriptionIntervalMin }.distinctUntilChanged().collectLatest { interval ->
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val zone = ZoneId.systemDefault()
                val now = System.currentTimeMillis()
                val next = TranscriptionPlanner.nextBoundary(now, interval, zone)
                delay(next - now)
                // Verspätetes Aufwachen (CPU schlief, z. B. während einer Pause): nur splitten, wenn noch nah an der Grenze.
                val late = System.currentTimeMillis() - next
                if (!TranscriptionPlanner.isFullHour(next, zone) && late < MAX_LATE_MS) {
                    c.recordingEngine.splitNow("interval")
                }
            }
        }
    }

    private companion object {
        const val TAG = "ServiceTranscription"
        const val WATCH_INTERVAL_MS = 5_000L
        const val SAFETY_INTERVAL_MS = 10 * 60_000L
        const val MAX_LATE_MS = 60_000L
    }
}
