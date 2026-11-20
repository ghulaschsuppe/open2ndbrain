package de.open2ndbrain.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.ui.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.open2ndbrain.work.NightQuality
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as O2bApp).container

    /** null = wird geladen. */
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _batteryIgnored = MutableStateFlow(SystemSettings.isIgnoringBatteryOptimizations(application))
    val batteryIgnored: StateFlow<Boolean> = _batteryIgnored.asStateFlow()

    /** Nach Rückkehr aus den System-Einstellungen aufrufen. */
    fun refreshBattery() {
        _batteryIgnored.value = SystemSettings.isIgnoringBatteryOptimizations(getApplication())
    }

    fun setBitrate(kbps: Int) = launch { container.settings.setBitrate(kbps) }
    fun setDiscardSilence(value: Boolean) = launch { container.settings.setDiscardSilence(value) }
    fun setMicPreference(pref: MicPreference) = launch { container.settings.setMicPreference(pref) }

    /** Speichert die Aufbewahrung und löscht sofort, was nach der neuen Regel zu alt ist (nach Bestätigung in der UI). */
    fun setRetention(days: Int) = launch {
        container.settings.setRetentionDays(days)
        if (days != AppSettings.RETENTION_UNLIMITED) {
            launch(Dispatchers.IO) { container.recordings.deleteOlderThan(days) }
        }
    }

    // ---------------------------------------------------------------- Transkription & Modelle

    private val models = container.modelManager
    val modelStates = models.states
    val presentBytes = models.presentBytes
    val waitingForNetwork = models.waitingForNetwork
    val importState = models.importState

    fun setTranscriptionMode(mode: TranscriptionMode) = launch {
        container.settings.setTranscriptionMode(mode)
    }

    fun setSpeakerRecognition(value: Boolean) = launch {
        container.settings.setSpeakerRecognition(value)
        container.transcriptionScheduler.schedule()
    }

    fun setThreads(n: Int) = launch { container.settings.setTranscriptionThreads(n) }
    fun setInterval(minutes: Int) = launch { container.settings.setTranscriptionInterval(minutes) }

    // ---------------------------------------------------------------- Rauschfilter & Nachtqualität

    /** Rauschfilter vor der Erkennung (Standard aus; im Mikrofon-Test prüfen). Lädt das kleine Modell bei Bedarf. */
    fun setDenoise(enabled: Boolean) = launch {
        container.settings.setDenoiseBeforeAsr(enabled)
        if (enabled) container.modelManager.download(de.open2ndbrain.core.ModelCatalog.GTCRN_DENOISER.id, unmeteredOnly = false)
    }

    /** „Nachtqualität: 12 von 30 Aufnahmen verbessert“, läuft gerade, Modell, gleiches Modell wie tagsüber. */
    val nightQuality: StateFlow<de.open2ndbrain.work.NightQualityStatus> = container.nightQuality.status

    /** Installierte Modelle, die als Nachtmodell infrage kommen (genaueste zuerst). */
    val nightModelOptions: StateFlow<List<de.open2ndbrain.core.ModelSpec>> = container.modelManager.states
        .map { st ->
            de.open2ndbrain.core.ModelCatalog.asrModels
                .filter { st[it.id] is de.open2ndbrain.core.ModelState.Installed && it.benchmark != null }
                .sortedBy { it.benchmark!!.werEverydayPct }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setNightQuality(enabled: Boolean) = launch { container.settings.setNightQuality(enabled) }

    /** null = automatisch (genauestes installiertes Modell). */
    fun setNightModel(id: String?) = launch { container.settings.setNightQualityModel(id) }

    fun setNightDays(days: Int) = launch { container.settings.setNightQualityDays(days) }

    fun refreshNightQuality() = launch { container.nightQuality.refresh() }

    /** Zustand für [NightQualitySetting] (Design): an/aus, Modell, Auswahl, Fortschritt. */
    val nightUi: StateFlow<NightQualityUi> = combine(container.settings.settings, nightModelOptions, nightQuality) { s, options, st ->
        NightQualityUi(
            enabled = s.nightQuality,
            modelId = st.modelId ?: NightQuality.model(s, options.mapTo(HashSet()) { it.id }),
            candidates = options,
            progress = nightProgress(st),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NightQualityUi(false, null, emptyList()))

    val denoiserInstalled: StateFlow<Boolean> = container.modelManager.states
        .map { it[de.open2ndbrain.core.ModelCatalog.GTCRN_DENOISER.id] is de.open2ndbrain.core.ModelState.Installed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun download(id: String, unmeteredOnly: Boolean) = models.download(id, unmeteredOnly)
    fun downloadAll(unmeteredOnly: Boolean) = models.downloadAll(unmeteredOnly)
    fun cancel(id: String) = models.cancel(id)
    fun deleteModel(id: String) = launch { models.delete(id) }
    fun import(uris: List<Uri>) = models.import(uris)
    fun clearImport() = models.clearImportState()

    private fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch(block = block)
    }
}
