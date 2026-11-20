package de.open2ndbrain.ui.mictest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.service.RecorderService
import de.open2ndbrain.work.MicTestScoring
import de.open2ndbrain.work.MicTestState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import de.open2ndbrain.work.MicTestResult as WorkResult

/**
 * Mikrofon-Test: Situation wählen → Text vorlesen → alle installierten Modelle vergleichen → Empfehlung aktivieren.
 * Liefert den UI-Zustand [MicTestUi] des Design-Agenten; die Auswertung läuft prozessweit weiter.
 */
class MicTestViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as O2bApp).container
    private val test = c.micTest

    private val situation = MutableStateFlow(MicSituation.Table)

    /** Aktives Modell (für die Markierung „aktiv“ im Ergebnis). */
    private val activeModel: StateFlow<String> = combine(c.settings.settings, c.modelManager.states) { s, st ->
        AsrSelection.effective(s.asrModelId, AsrSelection.installedIds(st))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelCatalog.DEFAULT_ASR_ID)

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(200)
        }
    }

    val ui: StateFlow<MicTestUi> = combine(
        test.state, test.history, situation, c.recordingEngine.state, clock,
    ) { st, history, sit, engine, now ->
        val hist = history.map { h ->
            MicTestHistoryEntry(
                atEpochMs = h.atEpochMs,
                situation = situationOf(h.situation),
                bestModel = ModelCatalog.byId(h.recommendedModelId ?: "")?.displayName ?: "–",
                bestWerPct = h.results.minOfOrNull { it.werPct } ?: 100.0,
            )
        }
        when (st) {
            MicTestState.Idle, is MicTestState.Failed -> MicTestUi.Intro(sit, hist)
            is MicTestState.Recording -> MicTestUi.Reading(
                text = test.texts[st.textIndex],
                elapsedMs = now - st.startedAt,
                level = engine.level,
                speech = engine.speechActive,
            )
            is MicTestState.Processing -> MicTestUi.Evaluating(
                modelName = st.modelId?.let { ModelCatalog.byId(it)?.displayName } ?: "",
                index = st.step,
                count = st.totalSteps,
                progress = st.progress,
            )
            is MicTestState.Done -> toResult(st.result, sit, hist)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MicTestUi.Intro())

    /** Fehlermeldung des letzten Tests (null = keine). */
    val error: StateFlow<String?> = test.state.map { (it as? MicTestState.Failed)?.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** „Stille verwerfen“ hat im letzten Test vermutlich Teile abgeschnitten. */
    val silenceCutHint: StateFlow<Boolean> = test.state.map { (it as? MicTestState.Done)?.result?.silenceCutHint == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Anzahl installierter Spracherkennungsmodelle (Vergleich ab zwei sinnvoll). */
    val installedModelCount: StateFlow<Int> = c.modelManager.states
        .map { st -> ModelCatalog.asrModels.count { st[it.id] is ModelState.Installed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Aktionen für [MicTestContent]. [onStartFailed]: Aufnahme ließ sich nicht starten (Berechtigung anfragen). */
    fun actions(onStartFailed: () -> Unit) = MicTestActions(
        onSituation = { situation.value = it },
        onStart = { if (!start()) onStartFailed() },
        onFinish = { finish() },
        onCancel = { test.cancel() },
        onActivate = { activate(it) },
        onRepeat = { test.reset() },
    )

    /**
     * Test starten. Läuft keine Aufnahme (oder ist sie pausiert), wird sie gestartet – nur aus der sichtbaren App
     * erlaubt. Rückgabe false: Aufnahme konnte nicht gestartet werden (z. B. Mikrofon-Berechtigung fehlt).
     */
    fun start(): Boolean {
        val service = c.recorderStatus.status.value
        val recording = service.running && service.pausedUntil == null && !service.stopping
        if (!recording && !RecorderService.start(getApplication())) return false
        test.begin(test.nextTextIndex(), situation.value.name)
        return true
    }

    /** Vorlesen beendet → auswerten; das beste Modell zusätzlich mit Rauschfilter, falls dieser installiert ist. */
    fun finish() {
        val denoiser = c.modelManager.states.value[ModelCatalog.GTCRN_DENOISER.id] is ModelState.Installed
        test.finish(withDenoiseVariant = denoiser)
    }

    /** Empfohlenes Modell aktivieren. */
    fun activate(modelId: String) = viewModelScope.launch {
        c.settings.setAsrModel(modelId)
        c.transcriptionScheduler.schedule()
    }

    private fun toResult(r: WorkResult, sit: MicSituation, hist: List<MicTestHistoryEntry>): MicTestUi.Result {
        val active = activeModel.value
        val reference = test.texts.getOrElse(r.textIndex) { "" }
        // Varianten mit Rauschfilter nur zeigen, wenn sie besser sind als dasselbe Modell ohne
        val rows = r.results.filter { res ->
            !res.denoised || r.results.none { !it.denoised && it.modelId == res.modelId && it.werPct <= res.werPct }
        }
        return MicTestUi.Result(
            results = rows.mapNotNull { res ->
                val spec = ModelCatalog.byId(res.modelId) ?: return@mapNotNull null
                MicTestResult(
                    spec = spec,
                    werPct = res.werPct,
                    processingMs = res.processingMs,
                    audioMs = r.audioMs,
                    words = MicTestScoring.align(reference, res.hypothesis).map { (w, m) ->
                        WordMark(w, WordMark.Kind.valueOf(m.name))
                    },
                    active = res.modelId == active && !res.denoised,
                )
            },
            bestId = r.recommendedModelId,
            situation = if (r.situation != null) situationOf(r.situation) else sit,
            history = hist,
        )
    }

    private fun situationOf(name: String?): MicSituation = MicSituation.entries.firstOrNull { it.name == name } ?: MicSituation.Table
}
