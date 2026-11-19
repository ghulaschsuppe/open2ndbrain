package de.open2ndbrain.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(freq: Double, rate: Int, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun run(r: Resampler, input: FloatArray, chunk: Int): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + n), n) { b, k -> for (j in 0 until k) out += b[j] }
            i += n
        }
        r.flush { b, k -> for (j in 0 until k) out += b[j] }
        return out.toFloatArray()
    }

    @Test
    fun downsample48kTo16kKeepsSpeechBand() {
        val out = run(Resampler(48_000, 16_000), sine(440.0, 48_000, 1.0), 960)
        assertTrue("Länge ${out.size}", abs(out.size - 16_000) <= 20)
        var maxErr = 0.0
        for (i in 200 until 15_800) maxErr = maxOf(maxErr, abs(out[i] - 0.5 * sin(2 * PI * 440 * i / 16_000)))
        assertTrue("Fehler $maxErr", maxErr < 0.01)
    }

    @Test
    fun frequenciesAboveNyquistAreSuppressed() {
        val out = run(Resampler(48_000, 16_000), sine(10_000.0, 48_000, 1.0), 4096)
        var sum = 0.0
        for (i in 200 until 15_800) sum += out[i] * out[i]
        val rms = sqrt(sum / 15_600)
        assertTrue("Alias-RMS $rms", rms < 0.005) // Eingang RMS 0,35
    }

    @Test
    fun chunkSizeDoesNotMatter() {
        val input = sine(1_000.0, 44_100, 0.5)
        val a = run(Resampler(44_100, 16_000), input, 100)
        val b = run(Resampler(44_100, 16_000), input, 7_000)
        assertEquals(a.size, b.size)
        assertArrayEquals(a, b, 1e-6f)
    }

    @Test
    fun sameRateIsPassthrough() {
        val input = sine(300.0, 16_000, 0.1)
        assertArrayEquals(input, run(Resampler(16_000, 16_000), input, 333), 0f)
    }
}
