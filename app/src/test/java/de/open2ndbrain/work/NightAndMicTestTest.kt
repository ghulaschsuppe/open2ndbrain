package de.open2ndbrain.work

import androidx.datastore.preferences.core.mutablePreferencesOf
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.settings.SettingsKeys
import de.open2ndbrain.work.NightQualityLogic.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NightAndMicTestTest {
    private val day = NightQualityLogic.DAY_MS
    private val canary = ModelCatalog.CANARY_180M_DE.id
    private val fast = ModelCatalog.DE_FASTCONFORMER.id
    private val parakeet = ModelCatalog.PARAKEET_V3.id

    private fun rec(name: String, hour: Long, inProgress: Boolean = false) = RecordingFile(File("/a/$name"), null, hour, 1000, 1, inProgress)

    @Test
    fun nightModelPicksMostAccurateInstalled() {
        assertEquals(canary, NightQualityLogic.pickModel(null, setOf(fast, canary, parakeet)))
        assertEquals(fast, NightQualityLogic.pickModel(null, setOf(fast, parakeet)))
        assertEquals(parakeet, NightQualityLogic.pickModel(parakeet, setOf(fast, canary, parakeet)))
        // Ausdrücklich gewähltes, aber nicht installiertes Modell → automatisch
        assertEquals(fast, NightQualityLogic.pickModel(canary, setOf(fast)))
        // Systemerkennung nie automatisch
        assertNull(NightQualityLogic.pickModel(null, setOf(ModelCatalog.ANDROID_SYSTEM.id)))
        assertNull(NightQualityLogic.pickModel(null, emptySet()))
    }

    @Test
    fun nightAllowed() {
        assertTrue(NightQualityLogic.allowed(true, charging = true, interactive = false, deviceIdle = false, hour = 23))
        assertTrue(NightQualityLogic.allowed(true, charging = true, interactive = false, deviceIdle = false, hour = 3))
        assertTrue(NightQualityLogic.allowed(true, charging = true, interactive = false, deviceIdle = true, hour = 14))
        assertFalse(NightQualityLogic.allowed(true, charging = true, interactive = false, deviceIdle = false, hour = 14))
        assertFalse(NightQualityLogic.allowed(true, charging = true, interactive = true, deviceIdle = true, hour = 2))
        assertFalse(NightQualityLogic.allowed(true, charging = false, interactive = false, deviceIdle = true, hour = 2))
        assertFalse(NightQualityLogic.allowed(false, charging = true, interactive = false, deviceIdle = true, hour = 2))
    }

    @Test
    fun nightCandidatesNewestFirstAndProgress() {
        val now = 10 * day
        val a = Entry(rec("a.ogg", now - 2 * day), fast)
        val b = Entry(rec("b.ogg", now - 1 * day), fast)
        val done = Entry(rec("c.ogg", now - day), canary)
        val noTranscript = Entry(rec("d.ogg", now - day), null)
        val tooOld = Entry(rec("e.ogg", now - 9 * day), fast)
        val running = Entry(rec("f.ogg", now, inProgress = true), fast)
        val all = listOf(a, b, done, noTranscript, tooOld, running)
        assertEquals(listOf(b.recording, a.recording), NightQualityLogic.candidates(all, canary, days = 7, now = now))
        assertEquals(listOf(b.recording), NightQualityLogic.candidates(all, canary, 7, now) { it == a.recording })
        // Verbessert: 1 (c) von 4 mit Transkript im Zeitraum (a, b, c, f)
        assertEquals(1 to 4, NightQualityLogic.progress(all, canary, 7, now))
    }

    @Test
    fun werAndRecommendation() {
        assertEquals(0.0, MicTestScoring.werPct("Guten Morgen, Herr Özdemir!", "guten morgen herr özdemir"), 1e-9)
        assertEquals(25.0, MicTestScoring.werPct("eins zwei drei vier", "eins zwei drei"), 1e-9)
        assertEquals(50.0, MicTestScoring.werPct("eins zwei drei vier", "eins zwo drei fünf"), 1e-9)
        assertEquals(100.0, MicTestScoring.werPct("a b", "a b c d"), 1e-9)
        assertEquals(listOf("lindenstraße", "27", "e", "mail"), MicTestScoring.normalizeWords("Lindenstraße 27, E-Mail."))

        val slowBest = MicTestModelResult(canary, 10.0, 9_000, hypothesis = "")
        val fastAlmost = MicTestModelResult(fast, 10.8, 1_000, hypothesis = "")
        val bad = MicTestModelResult(parakeet, 30.0, 500, hypothesis = "")
        // Innerhalb von 1 Prozentpunkt gewinnt das schnellere
        assertEquals(fast, MicTestScoring.recommend(listOf(slowBest, fastAlmost, bad))?.modelId)
        assertEquals(canary, MicTestScoring.recommend(listOf(slowBest, fastAlmost.copy(werPct = 12.0), bad))?.modelId)
        assertNull(MicTestScoring.recommend(emptyList()))
    }

    @Test
    fun silenceCutHint() {
        assertTrue(MicTestScoring.silenceCut(true, storedMs = 30_000, wallMs = 60_000))
        assertFalse(MicTestScoring.silenceCut(true, storedMs = 55_000, wallMs = 60_000))
        assertFalse(MicTestScoring.silenceCut(false, storedMs = 30_000, wallMs = 60_000))
        assertFalse(MicTestScoring.silenceCut(true, storedMs = 1_000, wallMs = 5_000)) // zu kurz für eine Aussage
    }

    @Test
    fun bitrateMigration() {
        // Nie gewählt → neuer Standard 32
        assertEquals(32, SettingsKeys.toAppSettings(mutablePreferencesOf()).bitrateKbps)
        // Aktiv gewählt bleibt, auch der alte Sparmodus 12
        assertEquals(16, SettingsKeys.toAppSettings(mutablePreferencesOf(SettingsKeys.BITRATE to 16)).bitrateKbps)
        assertEquals(12, SettingsKeys.toAppSettings(mutablePreferencesOf(SettingsKeys.BITRATE to 12)).bitrateKbps)
        assertEquals(listOf(32, 24, 16), AppSettings.bitrateOptionsFor(32))
        assertEquals(listOf(32, 24, 16, 12), AppSettings.bitrateOptionsFor(12))
        assertEquals(13.6, AppSettings.megabytesPerHour(32), 1e-9)
        assertEquals(10.2, AppSettings.megabytesPerHour(24), 1e-9)
        // Neue Einstellungen: Standardwerte
        val s = SettingsKeys.toAppSettings(mutablePreferencesOf())
        assertFalse(s.denoiseBeforeAsr)
        assertFalse(s.nightQuality)
        assertNull(s.nightQualityModelId)
        assertEquals(7, s.nightQualityDays)
    }
}
