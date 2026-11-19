package de.open2ndbrain.audio

import de.open2ndbrain.audio.vad.EnergyVad
import de.open2ndbrain.audio.vad.Signals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeechGateTest {
    private data class Out(val index: Int, val segmentStart: Boolean)

    private val out = ArrayList<Out>()

    private fun gate(discard: Boolean = true) = SpeechGate(
        frameSamples = 4, preRollFrames = 50, postRollFrames = 75, discardSilence = discard,
    ) { pcm, _, ts, seg ->
        assertEquals("Frame-Inhalt", (ts / 20).toInt(), pcm[0].toInt())
        out += Out((ts / 20).toInt(), seg)
    }

    private fun SpeechGate.feed(range: IntRange, speech: (Int) -> Boolean) {
        for (i in range) process(shortArrayOf(i.toShort(), 0, 0, 0), 4, i * 20L, speech(i))
    }

    private fun segments() = out.count { it.segmentStart }

    @Test
    fun passthroughWritesEverything() {
        val g = gate(discard = false)
        g.feed(0 until 300) { false }
        assertEquals((0 until 300).toList(), out.map { it.index })
        assertEquals(1, segments())
        assertTrue(out.first().segmentStart)
    }

    @Test
    fun silenceIsDiscarded() {
        gate().feed(0 until 1000) { false }
        assertTrue(out.isEmpty())
    }

    @Test
    fun speechGetsPreAndPostRoll() {
        gate().feed(0 until 400) { it in 100..130 }
        // Vorlauf 50..99, Sprache 100..130, Nachlauf 131..205
        assertEquals((50..205).toList(), out.map { it.index })
        assertEquals(1, segments())
    }

    @Test
    fun preRollAtStreamStartIsShorter() {
        gate().feed(0 until 100) { it in 10..20 }
        assertEquals(0, out.first().index)
        assertTrue(out.first().segmentStart)
    }

    @Test
    fun closeBurstsMergeIntoOneSegment() {
        gate().feed(0 until 600) { it in 100..110 || it in 200..210 }
        // Nachlauf endet bei 185; 186..199 im Ring (keine Verluste) → lückenlos fortgesetzt
        assertEquals((50..285).toList(), out.map { it.index })
        assertEquals(1, segments())
    }

    @Test
    fun distantBurstsBecomeSeparateSegmentsWithFullPreRoll() {
        gate().feed(0 until 800) { it in 100..110 || it in 500..510 }
        val second = out.indexOfFirst { it.index == 450 }
        assertTrue(second > 0)
        assertTrue(out[second].segmentStart)
        assertFalse(out.any { it.index in 186..449 })
        assertEquals(2, segments())
        assertEquals(585, out.last().index)
    }

    @Test
    fun discontinuityStartsNewSegmentAndDropsPreRoll() {
        val g = gate()
        g.feed(0 until 150) { it in 100..149 }
        g.discontinuity()
        g.feed(150 until 160) { false } // gehen in den (geleerten) Ring
        g.feed(160 until 170) { true }
        val idx = out.indexOfFirst { it.index == 150 }
        assertTrue(out[idx].segmentStart)
        assertEquals(2, segments())
    }

    @Test
    fun boundaryStartsSegmentWithoutDroppingPreRoll() {
        val g = gate()
        g.feed(0 until 100) { false }
        g.process(shortArrayOf(100, 0, 0, 0), 4, 100 * 20L, speech = false, boundary = true) // Uhrsprung im Vorlauf
        g.feed(101 until 120) { true }
        // Vorlauf bleibt vollständig (51..100), das Segment beginnt erneut an Frame 100
        assertEquals((51 until 120).toList(), out.map { it.index })
        assertEquals(listOf(51, 100), out.filter { it.segmentStart }.map { it.index })
    }

    @Test
    fun switchingToPassthroughFlushesPreRoll() {
        val g = gate()
        g.feed(0 until 100) { false }
        g.discardSilence = false
        g.feed(100 until 110) { false }
        assertEquals((50 until 110).toList(), out.map { it.index })
    }

    @Test
    fun endToEndWithEnergyVad() {
        val sig = Signals.concat(
            Signals.noise(5.0, -55.0, Random(11)),
            Signals.speechLike(1.0, -25.0, -55.0),
            Signals.noise(10.0, -55.0, Random(12)),
        )
        val vad = EnergyVad()
        var emitted = 0
        var firstTs = -1L
        val g = SpeechGate(320, 50, 75, true) { _, len, ts, _ ->
            emitted += len
            if (firstTs < 0) firstTs = ts
        }
        var f = 0
        while ((f + 1) * 320 <= sig.size) {
            g.process(sig.copyOfRange(f * 320, (f + 1) * 320), 320, f * 20L, vad.accept(sig, f * 320, 320))
            f++
        }
        val seconds = emitted / 16_000.0
        // ~1 s Sprache + ~1 s Vorlauf + ~1,5 s Nachlauf (+ VAD-Ausklang)
        assertTrue("gespeichert $seconds s", seconds in 3.0..4.5)
        assertTrue("Beginn $firstTs", firstTs in 3_900L..4_100L)
    }
}
