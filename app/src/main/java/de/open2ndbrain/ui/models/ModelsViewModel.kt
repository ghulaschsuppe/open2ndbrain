package de.open2ndbrain.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.models.ImportState
import de.open2ndbrain.work.Retranscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Ein Spracherkennungsmodell in der Auswahlliste. */
data class AsrModelItem(
    val spec: ModelSpec,
    val state: ModelState,
    /** Bereits geladene Bytes (auch bei abgebrochenem Download). */
    val presentBytes: Long,
    val waitingForNetwork: Boolean,
    /** Vom Nutzer gewählt (oder Standard, wenn nie gewählt). */
    val chosen: Boolean,
    /** Wird gerade tatsächlich zum Transkribieren verwendet. */
    val active: Boolean,
) {
    val installed: Boolean get() = state is ModelState.Installed
    /** Das aktive Modell ist nur nach einem Wechsel löschbar. */
    val deletable: Boolean get() = installed && !active
    /** 0..1 während des Downloads, sonst null. */
    val downloadProgress: Float? get() = (state as? ModelState.Downloading)?.let { it.bytesDone.toFloat() / it.bytesTotal.coerceAtLeast(1) }
}

/**
 * ViewModel der Modellauswahl („Liste mit Modellen“): Zustand je ASR-Modell, laden/abbrechen/löschen/aktivieren,
 * Speicherbedarf, Vorschlag für Bestandsinstallationen und Neu-Transkribieren (einzeln zum Vergleich oder in Masse).
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as O2bApp).container
    private val manager = c.modelManager
    private val retranscription = Retranscription(c)

    val asrModels: StateFlow<List<AsrModelItem>> = combine(
        manager.states, manager.presentBytes, manager.waitingForNetwork, c.settings.settings,
    ) { states, present, waiting, s ->
        val installed = AsrSelection.installedIds(states)
        val active = AsrSelection.effective(s.asrModelId, installed).takeIf { it in installed }
        val chosen = s.asrModelId ?: ModelCatalog.DEFAULT_ASR_ID
        ModelCatalog.asrModels.map { spec ->
            AsrModelItem(
                spec = spec,
                state = states[spec.id] ?: ModelState.NotInstalled,
                presentBytes = present[spec.id] ?: 0L,
                waitingForNetwork = spec.id in waiting,
                chosen = spec.id == chosen,
                active = spec.id == active,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Weitere Modelle (VAD, Sprechererkennung) mit Zustand – für den Bereich „Zusätzliche Modelle“. */
    val otherModels: StateFlow<List<Pair<ModelSpec, ModelState>>> = manager.states
        .map { st -> ModelCatalog.all.filter { it !in ModelCatalog.asrModels }.map { it to (st[it.id] ?: ModelState.NotInstalled) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Speicherbedarf aller installierten Modelle. */
    val installedBytes: StateFlow<Long> = manager.states
        .map { st -> ModelCatalog.all.filter { st[it.id] is ModelState.Installed }.sumOf { it.sizeBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** „Neues, besseres Deutsch-Modell verfügbar“ (einmalig für Bestandsinstallationen), sonst null. */
    val upgradeSuggestion: StateFlow<ModelSpec?> = combine(c.settings.settings, manager.states) { s, st ->
        AsrSelection.upgradeSuggestion(s.asrModelId, s.asrSuggestionDismissed, AsrSelection.installedIds(st))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Nach einem Wechsel: Angebot „Transkripte der letzten Tage neu erstellen?“ anzeigen. */
    private val _offerRetranscribe = MutableStateFlow(false)
    val offerRetranscribe: StateFlow<Boolean> = _offerRetranscribe.asStateFlow()

    val importState: StateFlow<ImportState?> = manager.importState

    /** Zustand der experimentellen Systemerkennung (für die SystemAsrCard). */
    val systemState: StateFlow<de.open2ndbrain.ui.settings.SystemAsrState> = manager.systemStatus
        .map {
            when (it) {
                de.open2ndbrain.models.SystemAsrStatus.Unknown -> de.open2ndbrain.ui.settings.SystemAsrState.Checking
                de.open2ndbrain.models.SystemAsrStatus.Unavailable -> de.open2ndbrain.ui.settings.SystemAsrState.Unavailable
                de.open2ndbrain.models.SystemAsrStatus.LanguagePackMissing -> de.open2ndbrain.ui.settings.SystemAsrState.LanguagePackMissing
                de.open2ndbrain.models.SystemAsrStatus.Ready -> de.open2ndbrain.ui.settings.SystemAsrState.Ready
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), de.open2ndbrain.ui.settings.SystemAsrState.Checking)

    init {
        manager.refreshSystemAsr()
    }

    /** Sprachpaket der Systemerkennung anfordern (öffnet ggf. die Systemeinstellung) und danach neu prüfen. */
    fun requestSystemLanguagePack() {
        manager.download(ModelCatalog.ANDROID_SYSTEM.id, unmeteredOnly = false)
        manager.refreshSystemAsr()
    }

    /** Beim Zurückkehren auf die Seite (z. B. aus den Systemeinstellungen) neu prüfen. */
    fun refreshSystemAsr() = manager.refreshSystemAsr()

    fun download(id: String, unmeteredOnly: Boolean = true) = manager.download(id, unmeteredOnly)
    fun cancel(id: String) = manager.cancel(id)
    fun import(uris: List<Uri>) = manager.import(uris)

    /** Löscht ein Modell; das aktive Modell nicht (Rückgabe false). */
    fun delete(id: String): Boolean {
        if (asrModels.value.any { it.spec.id == id && it.active }) return false
        viewModelScope.launch { manager.delete(id) }
        return true
    }

    /** Aktiviert ein installiertes Modell (Rückgabe false, wenn nicht installiert). Der Runner lädt es beim nächsten Lauf. */
    fun activate(id: String): Boolean {
        val item = asrModels.value.firstOrNull { it.spec.id == id } ?: return false
        if (!item.installed) return false
        val changed = !item.active
        viewModelScope.launch {
            c.settings.setAsrModel(id)
            if (changed) _offerRetranscribe.value = true
            c.transcriptionScheduler.schedule()
        }
        return true
    }

    /** Vorschlag annehmen: Standardmodell laden und wählen (bis es da ist, transkribiert das bisherige weiter). */
    fun acceptUpgrade(unmeteredOnly: Boolean = true) {
        viewModelScope.launch {
            manager.download(ModelCatalog.DEFAULT_ASR_ID, unmeteredOnly)
            c.settings.setAsrModel(ModelCatalog.DEFAULT_ASR_ID)
        }
    }

    fun dismissUpgrade() = viewModelScope.launch { c.settings.dismissAsrSuggestion() }

    fun dismissRetranscribeOffer() {
        _offerRetranscribe.value = false
    }

    /** Zeitschätzung für „Alle Transkripte der letzten [days] Tage neu“ (aus der Laufstatistik bzw. dem Benchmark). */
    suspend fun estimateRecent(days: Int): Retranscription.Estimate = retranscription.estimateRecent(days)

    fun retranscribeRecent(days: Int) = viewModelScope.launch {
        retranscription.recent(days)
        _offerRetranscribe.value = false
    }

    /** „Mit diesem Modell neu transkribieren“ für eine Aufnahme (Vergleich; wird vorgezogen). */
    fun retranscribeWith(recording: RecordingFile, modelId: String) = viewModelScope.launch { retranscription.one(recording, modelId) }

    /** … für alle Dateien eines Meetings. */
    fun retranscribeMeetingWith(meeting: Marker, modelId: String) = viewModelScope.launch { retranscription.meeting(meeting, modelId) }
}
