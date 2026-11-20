package de.open2ndbrain.ui.mictest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.service.RecorderService
import de.open2ndbrain.work.MicTestResult
import de.open2ndbrain.work.MicTestState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Mikrofon-Test: Text vorlesen → alle installierten Modelle vergleichen → Empfehlung aktivieren.
 * Die Auswertung läuft prozessweit weiter, auch wenn die Ansicht geschlossen wird.
 */
class MicTestViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as O2bApp).container
    private val test = c.micTest

    /** Die drei Vorlesetexte (~60 s). */
    val texts: List<String> = test.texts

    val state: StateFlow<MicTestState> = test.state
    val history: StateFlow<List<MicTestResult>> = test.history

    /** Anzahl installierter Spracherkennungsmodelle (Test braucht mindestens eins; Vergleich ab zwei sinnvoll). */
    val installedModelCount: StateFlow<Int> = c.modelManager.states
        .map { st -> ModelCatalog.asrModels.count { st[it.id] is ModelState.Installed } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Ist der Rauschfilter installiert (dann ist die Variante „mit Rauschfilter“ möglich)? */
    val denoiserInstalled: StateFlow<Boolean> = c.modelManager.states
        .map { it[ModelCatalog.GTCRN_DENOISER.id] is ModelState.Installed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Test starten. Läuft keine Aufnahme (oder ist sie pausiert), wird sie gestartet – nur aus der sichtbaren App
     * erlaubt. Rückgabe false: Aufnahme konnte nicht gestartet werden (z. B. Mikrofon-Berechtigung fehlt).
     */
    fun start(textIndex: Int): Boolean {
        val service = c.recorderStatus.status.value
        val recording = service.running && service.pausedUntil == null && !service.stopping
        if (!recording && !RecorderService.start(getApplication())) return false
        test.begin(textIndex)
        return true
    }

    /** Vorlesen beendet → auswerten. [withDenoiseVariant]: bestes Modell zusätzlich mit Rauschfilter. */
    fun finish(withDenoiseVariant: Boolean = false) = test.finish(withDenoiseVariant)

    fun cancel() = test.cancel()

    /** Ergebnis gesehen → zurück zum Start. */
    fun reset() = test.reset()

    /** Empfohlenes Modell aktivieren. */
    fun activate(modelId: String) = viewModelScope.launch {
        c.settings.setAsrModel(modelId)
        c.transcriptionScheduler.schedule()
    }

    /** Rauschfilter vor der Erkennung einschalten (falls der Test das empfiehlt). */
    fun setDenoise(enabled: Boolean) = viewModelScope.launch { c.settings.setDenoiseBeforeAsr(enabled) }
}
