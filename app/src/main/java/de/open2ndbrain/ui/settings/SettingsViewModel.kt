package de.open2ndbrain.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.ui.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        viewModelScope.launch(block = block)
    }
}
