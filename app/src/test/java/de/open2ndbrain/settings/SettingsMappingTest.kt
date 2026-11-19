package de.open2ndbrain.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import de.open2ndbrain.core.EngineConfig
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsMappingTest {
    private val root = File("/data/audio")

    @Test
    fun defaultsMatchConcept() {
        val s = SettingsKeys.toAppSettings(mutablePreferencesOf())
        assertEquals(16, s.bitrateKbps)
        assertTrue(s.discardSilence)
        assertEquals(MicPreference.Auto, s.micPreference)
        assertEquals(30, s.audioRetentionDays)
        assertFalse(s.recordingDesired)
        assertFalse(s.onboardingDone)
        assertEquals(EngineConfig(outputRoot = root), s.toEngineConfig(root))
    }

    @Test
    fun mapsAllFieldsToEngineConfig() {
        val prefs = mutablePreferencesOf(
            SettingsKeys.BITRATE to 12,
            SettingsKeys.DISCARD_SILENCE to false,
            SettingsKeys.MIC_PREFERENCE to "specific|BluetoothSco|Pixel Buds",
            SettingsKeys.RETENTION_DAYS to 0,
            SettingsKeys.RECORDING_DESIRED to true,
        )
        val s = SettingsKeys.toAppSettings(prefs)
        assertEquals(
            EngineConfig(
                outputRoot = root,
                bitrateKbps = 12,
                discardSilence = false,
                micPreference = MicPreference.Specific("Pixel Buds", InputType.BluetoothSco),
            ),
            s.toEngineConfig(root),
        )
        assertEquals(AppSettings.RETENTION_UNLIMITED, s.audioRetentionDays)
        assertTrue(s.recordingDesired)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val s = SettingsKeys.toAppSettings(
            mutablePreferencesOf(SettingsKeys.BITRATE to 64, SettingsKeys.RETENTION_DAYS to -3, SettingsKeys.MIC_PREFERENCE to "kaputt"),
        )
        assertEquals(16, s.bitrateKbps)
        assertEquals(30, s.audioRetentionDays)
        assertEquals(MicPreference.Auto, s.micPreference)
    }

    @Test
    fun micPreferenceRoundTrip() {
        val prefs = listOf(
            MicPreference.Auto,
            MicPreference.BuiltInOnly,
            MicPreference.Specific("Jabra | Evolve", InputType.BluetoothLe),
            MicPreference.Specific("", InputType.Usb),
        )
        prefs.forEach { assertEquals(it, MicPreferenceCodec.decode(MicPreferenceCodec.encode(it))) }
        assertEquals(MicPreference.Auto, MicPreferenceCodec.decode(null))
        assertEquals(MicPreference.Auto, MicPreferenceCodec.decode("specific|Unbekannt|X"))
    }

    @Test
    fun pauseState() {
        val now = 1_000_000L
        assertFalse(AppSettings(pausedUntilEpochMs = 0).isPausedAt(now))
        assertTrue(AppSettings(pausedUntilEpochMs = now + 1).isPausedAt(now))
        assertFalse(AppSettings(pausedUntilEpochMs = now - 1).isPausedAt(now))
        assertTrue(AppSettings(pausedUntilEpochMs = AppSettings.PAUSED_INDEFINITELY).isPausedAt(now))
    }

    @Test
    fun retentionShortens() {
        assertTrue(AppSettings.retentionShortens(30, 7))
        assertTrue(AppSettings.retentionShortens(AppSettings.RETENTION_UNLIMITED, 90))
        assertFalse(AppSettings.retentionShortens(7, 30))
        assertFalse(AppSettings.retentionShortens(30, AppSettings.RETENTION_UNLIMITED))
    }
}
