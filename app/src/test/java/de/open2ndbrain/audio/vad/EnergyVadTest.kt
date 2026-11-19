package de.open2ndbrain.audio.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Synthetische Signale: 16 kHz, Frames à 20 ms. */
internal object Signals {
    const val SR = 16_000
    const val FRAME = 320

    fun amp(dbfs: Double) = 32768.0 * 10.0.pow(dbfs / 20.0)

    /** Gleichverteiltes weißes Rauschen mit RMS ≈ [dbfs]. */
    fun noise(seconds: Double, dbfs: Double, rnd: Random = Random(42)): ShortArray {
        val a = amp(dbfs) * kotlin.math.sqrt(3.0) // RMS der Gleichverteilung = A/√3
        return ShortArray((seconds * SR).toInt()) { (rnd.nextDouble(-a, a)).toInt().toShort() }
    }

    /**
     * Sprachähnlich: Grundton 140 Hz + Obertöne, Silbenhüllkurve 4 Hz (mit Pausen zwischen Silben),
     * Spitzenpegel ≈ [dbfs], plus Hintergrundrauschen.
     */
    fun speechLike(seconds: Double, dbfs: Double, noiseDb: Double, rnd: Random = Random(7)): ShortArray {
        val n = (seconds * SR).toInt()
        val bg = noise(seconds, noiseDb, rnd)
        val a = amp(dbfs)
        return ShortArray(n) { i ->
            val t = i.toDouble() / SR
            val env = sin(PI * 4.0 * t).let { abs(it) }.pow(1.5)
            val v = a * env * (0.6 * sin(2 * PI * 140 * t) + 0.3 * sin(2 * PI * 280 * t) + 0.1 * sin(2 * PI * 420 * t))
            (v + bg[i]).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    fun tone(seconds: Double, dbfs: Double, freq: Double = 1000.0): ShortArray {
        val a = amp(dbfs) * kotlin.math.sqrt(2.0)
        return ShortArray((seconds * SR).toInt()) { i -> (a * sin(2 * PI * freq * i / SR)).toInt().toShort() }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    /** Entscheidung je Frame. */
    fun decisions(vad: VoiceActivityDetector, signal: ShortArray, chunk: Int = FRAME): BooleanArray {
        val res = BooleanArray(signal.size / chunk)
        for (f in res.indices) res[f] = vad.accept(signal, f * chunk, chunk)
        return res
    }
}

class EnergyVadTest {
    private fun frames(seconds: Double) = (seconds * 50).toInt()

    @Test
    fun digitalSilenceIsNeverSpeech() {
        val d = Signals.decisions(EnergyVad(), ShortArray(Signals.SR * 5))
        assertTrue(d.none { it })
    }

    @Test
    fun stationaryNoiseIsNotSpeech() {
        for (db in listOf(-70.0, -55.0, -40.0)) {
            val d = Signals.decisions(EnergyVad(), Signals.noise(10.0, db))
            assertEquals("Rauschen $db dBFS", 0, d.count { it })
        }
    }

    @Test
    fun speechBurstsInNoiseAreDetected() {
        val sig = Signals.concat(
            Signals.noise(3.0, -55.0, Random(1)),
            Signals.speechLike(2.0, -25.0, -55.0),
            Signals.noise(3.0, -55.0, Random(2)),
        )
        val d = Signals.decisions(EnergyVad(), sig)
        val before = d.sliceArray(0 until frames(3.0))
        val during = d.sliceArray(frames(3.0) until frames(5.0))
        val after = d.sliceArray(frames(5.6) until frames(8.0))
        assertTrue(before.none { it })
        assertTrue("Sprachanteil ${during.count { it }}/${during.size}", during.count { it } > during.size * 0.7)
        assertTrue(after.none { it })
    }

    @Test
    fun longContinuousSpeechStaysDetected() {
        // Minimum-Statistik: Rauschboden darf bei 30 s Sprache nicht auf Sprachpegel steigen.
        val sig = Signals.concat(Signals.noise(1.0, -60.0), Signals.speechLike(30.0, -30.0, -60.0))
        val d = Signals.decisions(EnergyVad(), sig)
        val speech = d.sliceArray(frames(1.0) until d.size)
        assertTrue("Sprachanteil ${speech.count { it }}/${speech.size}", speech.count { it } > speech.size * 0.8)
        assertTrue(speech.sliceArray(speech.size - frames(5.0) until speech.size).count { it } > frames(5.0) * 0.8)
    }

    @Test
    fun shortClickIsIgnored() {
        val sig = Signals.concat(
            Signals.noise(2.0, -60.0, Random(3)),
            Signals.tone(0.04, -10.0),
            Signals.noise(2.0, -60.0, Random(4)),
        )
        assertTrue(Signals.decisions(EnergyVad(), sig).none { it })
    }

    @Test
    fun toneBurstLongerThanMinimumIsDetected() {
        val sig = Signals.concat(Signals.noise(2.0, -60.0), Signals.tone(0.5, -30.0), Signals.noise(2.0, -60.0))
        val d = Signals.decisions(EnergyVad(), sig)
        assertTrue(d.sliceArray(frames(2.0) until frames(2.5)).any { it })
    }

    @Test
    fun newConstantNoiseBecomesFloorAfterFewSeconds() {
        // Lüfter geht an: anfangs evtl. „Sprache“ (lieber speichern), nach dem Fenster aber nicht mehr.
        val sig = Signals.concat(Signals.noise(2.0, -65.0, Random(5)), Signals.noise(10.0, -35.0, Random(6)))
        val d = Signals.decisions(EnergyVad(), sig)
        assertTrue(d.sliceArray(frames(8.0) until d.size).none { it })
    }

    @Test
    fun veryQuietSignalIsNotSpeech() {
        val sig = Signals.concat(ShortArray(Signals.SR), Signals.speechLike(2.0, -70.0, -100.0))
        assertTrue(Signals.decisions(EnergyVad(), sig).none { it })
    }

    @Test
    fun arbitraryChunkSizesGiveSameResult() {
        val sig = Signals.concat(Signals.noise(2.0, -55.0), Signals.speechLike(2.0, -25.0, -55.0))
        val a = Signals.decisions(EnergyVad(), sig, 320)
        val vad = EnergyVad()
        var i = 0
        val b = ArrayList<Boolean>()
        // Stücke à 100 Samples; Entscheidung jeweils am Ende eines 20-ms-Fensters vergleichen
        while (i + 100 <= sig.size) {
            vad.accept(sig, i, 100)
            i += 100
            if (i % 1600 == 0) b += vad.isSpeech
        }
        for (k in b.indices) assertEquals(a[(k + 1) * 5 - 1], b[k])
    }

    @Test
    fun resetForgetsState() {
        val vad = EnergyVad()
        Signals.decisions(vad, Signals.concat(Signals.noise(1.0, -60.0), Signals.speechLike(1.0, -20.0, -60.0)))
        vad.reset()
        assertFalse(vad.isSpeech)
    }
}
