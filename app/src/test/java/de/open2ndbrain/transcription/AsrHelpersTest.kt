package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingSidecar.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AsrHelpersTest {
    private fun s(sec: Double) = Math.round(sec * 16_000)
    private fun r(a: Double, b: Double) = SampleRange(s(a), s(b))

    // ---------------------------------------------------------------- UnitPlanner

    @Test
    fun unitsMergeSameSpeakerWithinGapAndLength() {
        val segs = listOf(r(1.0, 3.0), r(3.5, 6.0), r(6.5, 9.0), r(9.5, 12.0), r(12.5, 22.0), r(30.0, 31.0))
        val ids = listOf("A", "A", "B", null, "B", "B")
        val u = UnitPlanner.plan(segs, ids, total = s(40.0))
        // A: 1–6; B: 6,5–12 (unbekannt passt dazu); bis 22 wären es 15,5 s → eigene Einheit; 30–31 (Lücke 8 s)
        assertEquals(listOf(r(1.0, 6.0), r(6.5, 12.0), r(12.5, 22.0), r(30.0, 31.0)), u.map { it.speech })
        assertEquals(listOf("A", "B", "B", "B"), u.map { it.speakerId })
        assertEquals(r(0.8, 6.2), u[0].padded)
        assertEquals(s(6.3), u[1].padded.start)
        // enge Lücke: Rand nur bis zur Mitte
        assertEquals(s(3.1), UnitPlanner.plan(listOf(r(1.0, 3.0), r(3.2, 6.0)), listOf("A", "B"), s(10.0))[1].padded.start)
    }

    @Test
    fun wordTokensSpreadProportionally() {
        val t = UnitPlanner.wordTokens("Hallo du Welt.", s(10.0), s(12.0))
        assertEquals(listOf(" Hallo", " du", " Welt."), t.map { it.text })
        assertEquals(10_000L, t[0].startMs)
        assertTrue(t[1].startMs > t[0].startMs && t[2].startMs > t[1].startMs)
        assertTrue(t.last().startMs + t.last().durationMs <= 12_000)
        assertTrue(UnitPlanner.wordTokens("   ", 0, 100).isEmpty())
        // durch UtteranceBuilder: Satzaufteilung funktioniert auch mit geschätzten Zeiten
        val u = UtteranceBuilder.build(UnitPlanner.wordTokens("Erst Viertel auf zehn. Dann los!", s(0.0), s(4.0)), FileTimeMapper(listOf(Segment(0, 1_000, 10_000)), 0))
        assertEquals(listOf("Erst Viertel auf zehn.", "Dann los!"), u.map { it.text })
        assertTrue(u[1].fileOffsetMs > 2_000)
    }

    // ---------------------------------------------------------------- Loudness

    private fun sine(amp: Double, n: Int = 16_000) = FloatArray(n) { (amp * sin(2 * PI * 300 * it / 16_000)).toFloat() }
    private fun dbfs(x: FloatArray) = 20 * log10(sqrt(x.sumOf { it.toDouble() * it } / x.size))

    @Test
    fun quietSpeechIsRaisedToTarget() {
        val quiet = sine(0.01) // ≈ −43 dBFS
        val out = Loudness.normalize(quiet)
        assertEquals(Loudness.TARGET_DBFS, dbfs(out), 0.5)
        assertEquals(0.01f, quiet.maxOf { abs(it) }, 1e-4f) // Original unverändert
    }

    @Test
    fun gainIsLimitedAndNeverAttenuates() {
        assertEquals(Loudness.MAX_GAIN, Loudness.gainFor(sine(0.0005)), 1e-9) // sehr leise: max. 30×
        assertEquals(1.0, Loudness.gainFor(sine(0.5)), 0.0) // laut: nie leiser
        assertEquals(1.0, Loudness.gainFor(FloatArray(100)), 0.0) // Stille: unverändert
        // ein lauter Klick in leiser Sprache: Verstärkung so begrenzt, dass höchstens ~0,1 % übersteuern
        val x = sine(0.01).also { for (i in 0 until 8) it[i * 100] = 0.6f }
        val out = Loudness.normalize(x)
        assertTrue(out.count { abs(it) >= 0.999f } <= 16)
        assertTrue(dbfs(out) > dbfs(x) + 6)
    }

    // ---------------------------------------------------------------- TextCleanup

    @Test
    fun cleanupFixesSpacingAndKeepsApostrophes() {
        assertEquals("Er wies darauf hin, dass es geht.", TextCleanup.clean("Er wies darauf hin , dass es geht ."))
        assertEquals("Was? Ja! (so) „gut“", TextCleanup.clean("Was ? Ja  ! ( so ) „ gut “"))
        assertEquals("Wie geht's dir?", TextCleanup.clean("Wie geht's dir ?"))
    }

    @Test
    fun whisperHallucinationsAreDetected() {
        listOf(
            "Untertitel im Auftrag des ZDF, 2020",
            "Untertitel im Auftrag des ZDF für funk, 2017",
            "Untertitel der Amara.org-Community",
            "Vielen Dank fürs Zuschauen!",
            "Vielen Dank für's Zuschauen und bis zum nächsten Mal.",
            "Bis zum nächsten Mal.",
            "Copyright WDR 2021",
        ).forEach { assertTrue(it, TextCleanup.isHallucination(it)) }
        listOf(
            "Vielen Dank für die Einladung.",
            "Bis zum nächsten Mal treffen wir uns im Büro.",
            "Die Untertitel sind zu klein.",
        ).forEach { assertFalse(it, TextCleanup.isHallucination(it)) }
        val u = UtteranceBuilder.build(UnitPlanner.wordTokens("Vielen Dank fürs Zuschauen!", 0, s(2.0)), FileTimeMapper(emptyList(), 0))
        assertTrue(u.isEmpty())
    }
}
