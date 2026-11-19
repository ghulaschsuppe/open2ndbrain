package de.open2ndbrain.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.open2ndbrain.core.EngineConfig
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** Alle Einstellungen und der persistente Dienst-Zustand der App. */
data class AppSettings(
    /** Opus-Bitrate in kbit/s: 16 (Standard) oder 12 (Sparmodus). */
    val bitrateKbps: Int = DEFAULT_BITRATE,
    val discardSilence: Boolean = true,
    val micPreference: MicPreference = MicPreference.Auto,
    /** Aufbewahrung des Audios in Tagen; [RETENTION_UNLIMITED] = nie automatisch löschen. */
    val audioRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    /** Hat der Nutzer die Aufnahme eingeschaltet? Grundlage für die Wiederaufnahme nach Neustart. */
    val recordingDesired: Boolean = false,
    val onboardingDone: Boolean = false,
    /** 0 = nicht pausiert, [PAUSED_INDEFINITELY] = bis auf Weiteres, sonst Ende der Pause (epoch ms). */
    val pausedUntilEpochMs: Long = 0,
    /** Letzter Lauf der Aufbewahrungs-Löschung (epoch ms). */
    val lastRetentionRunEpochMs: Long = 0,
) {
    fun toEngineConfig(outputRoot: File): EngineConfig = EngineConfig(
        outputRoot = outputRoot,
        bitrateKbps = bitrateKbps,
        discardSilence = discardSilence,
        micPreference = micPreference,
    )

    /** Ist die Aufnahme zum Zeitpunkt [now] vom Nutzer pausiert? */
    fun isPausedAt(now: Long): Boolean = pausedUntilEpochMs != 0L && pausedUntilEpochMs > now

    companion object {
        const val DEFAULT_BITRATE = 16
        const val SAVER_BITRATE = 12
        val BITRATE_OPTIONS = listOf(DEFAULT_BITRATE, SAVER_BITRATE)

        const val RETENTION_UNLIMITED = 0
        const val DEFAULT_RETENTION_DAYS = 30
        val RETENTION_OPTIONS = listOf(7, 14, 30, 90, RETENTION_UNLIMITED)

        const val PAUSED_INDEFINITELY = Long.MAX_VALUE

        /** Würde der Wechsel der Aufbewahrung von [old] auf [new] Tage sofort mehr Audio löschen? */
        fun retentionShortens(old: Int, new: Int): Boolean =
            new != RETENTION_UNLIMITED && (old == RETENTION_UNLIMITED || new < old)
    }
}

/** Kodierung von [MicPreference] als String für DataStore. */
object MicPreferenceCodec {
    private const val AUTO = "auto"
    private const val BUILT_IN = "builtin"
    private const val SPECIFIC = "specific"
    private const val SEP = "|"

    fun encode(pref: MicPreference): String = when (pref) {
        MicPreference.Auto -> AUTO
        MicPreference.BuiltInOnly -> BUILT_IN
        is MicPreference.Specific -> listOf(SPECIFIC, pref.type.name, pref.productName).joinToString(SEP)
    }

    /** Unbekannte oder beschädigte Werte ergeben [MicPreference.Auto]. */
    fun decode(value: String?): MicPreference {
        if (value == null) return MicPreference.Auto
        if (value == BUILT_IN) return MicPreference.BuiltInOnly
        val parts = value.split(SEP, limit = 3)
        if (parts.size == 3 && parts[0] == SPECIFIC) {
            val type = InputType.entries.firstOrNull { it.name == parts[1] } ?: return MicPreference.Auto
            return MicPreference.Specific(productName = parts[2], type = type)
        }
        return MicPreference.Auto
    }
}

/** Schlüssel und reine Abbildung Preferences → [AppSettings] (separat, damit testbar). */
internal object SettingsKeys {
    val BITRATE = intPreferencesKey("bitrate_kbps")
    val DISCARD_SILENCE = booleanPreferencesKey("discard_silence")
    val MIC_PREFERENCE = stringPreferencesKey("mic_preference")
    val RETENTION_DAYS = intPreferencesKey("audio_retention_days")
    val RECORDING_DESIRED = booleanPreferencesKey("recording_desired")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val PAUSED_UNTIL = longPreferencesKey("paused_until")
    val LAST_RETENTION_RUN = longPreferencesKey("last_retention_run")

    fun toAppSettings(p: Preferences): AppSettings {
        val defaults = AppSettings()
        val bitrate = p[BITRATE]?.takeIf { it in AppSettings.BITRATE_OPTIONS } ?: defaults.bitrateKbps
        val retention = p[RETENTION_DAYS]?.takeIf { it >= 0 } ?: defaults.audioRetentionDays
        return AppSettings(
            bitrateKbps = bitrate,
            discardSilence = p[DISCARD_SILENCE] ?: defaults.discardSilence,
            micPreference = MicPreferenceCodec.decode(p[MIC_PREFERENCE]),
            audioRetentionDays = retention,
            recordingDesired = p[RECORDING_DESIRED] ?: defaults.recordingDesired,
            onboardingDone = p[ONBOARDING_DONE] ?: defaults.onboardingDone,
            pausedUntilEpochMs = p[PAUSED_UNTIL]?.coerceAtLeast(0) ?: 0,
            lastRetentionRunEpochMs = p[LAST_RETENTION_RUN] ?: 0,
        )
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Einstellungen auf DataStore-Basis. Eine Instanz pro Prozess (über [de.open2ndbrain.core.AppContainer]). */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(SettingsKeys::toAppSettings)
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /** Engine-Konfiguration; ändert sich nur, wenn sich aufnahmerelevante Einstellungen ändern. */
    fun engineConfig(outputRoot: File): Flow<EngineConfig> =
        settings.map { it.toEngineConfig(outputRoot) }.distinctUntilChanged()

    suspend fun setBitrate(kbps: Int) {
        require(kbps in AppSettings.BITRATE_OPTIONS) { "Ungültige Bitrate: $kbps" }
        store.edit { it[SettingsKeys.BITRATE] = kbps }
    }

    suspend fun setDiscardSilence(value: Boolean) {
        store.edit { it[SettingsKeys.DISCARD_SILENCE] = value }
    }

    suspend fun setMicPreference(pref: MicPreference) {
        store.edit { it[SettingsKeys.MIC_PREFERENCE] = MicPreferenceCodec.encode(pref) }
    }

    suspend fun setRetentionDays(days: Int) {
        require(days >= 0) { "Ungültige Aufbewahrung: $days" }
        store.edit { it[SettingsKeys.RETENTION_DAYS] = days }
    }

    suspend fun setOnboardingDone(value: Boolean) {
        store.edit { it[SettingsKeys.ONBOARDING_DONE] = value }
    }

    /** Aufnahme gewünscht/nicht gewünscht; setzt dabei eine Pause zurück. */
    suspend fun setRecordingDesired(value: Boolean) {
        store.edit {
            it[SettingsKeys.RECORDING_DESIRED] = value
            it[SettingsKeys.PAUSED_UNTIL] = 0L
        }
    }

    suspend fun setPausedUntil(epochMs: Long) {
        store.edit { it[SettingsKeys.PAUSED_UNTIL] = epochMs }
    }

    suspend fun setLastRetentionRun(epochMs: Long) {
        store.edit { it[SettingsKeys.LAST_RETENTION_RUN] = epochMs }
    }
}
