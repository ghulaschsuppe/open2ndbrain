package de.open2ndbrain.transcription

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streamender Abtastratenwandler (gefensterter Sinc, Blackman-Fenster, vorberechnete Phasentabellen).
 * Typischer Fall: Opus-Decoder liefert 48 kHz → 16 kHz für die Modelle. Bei gleicher Rate: Durchreichen.
 * Nicht threadsicher.
 */
internal class Resampler(
    private val inRate: Int,
    private val outRate: Int,
    halfTaps: Int = 10,
    private val phases: Int = 256,
) {
    private val passthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    /** Grenzfrequenz relativ zur Eingangs-Nyquistfrequenz (mit etwas Abstand gegen Aliasing). */
    private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.92
    private val halfWidth = ceil(halfTaps / cutoff).toInt()
    private val taps = 2 * halfWidth
    private val table = Array(phases + 1) { p -> kernel(p.toDouble() / phases) }

    private var buf = FloatArray(4096)
    private var bufLen = 0
    /** Absoluter Eingangsindex von buf[0]. */
    private var bufStart = 0L
    private var outCount = 0L
    private var out = FloatArray(2048)

    /** Kernel-Gewichte für den Bruchteil [frac] ∈ [0,1]: Index k ↔ Eingangssample floor(t) - halfWidth + 1 + k. */
    private fun kernel(frac: Double): FloatArray {
        val w = FloatArray(taps)
        var sum = 0.0
        for (k in 0 until taps) {
            val x = (k - halfWidth + 1) - frac // Abstand Sample → Zielzeitpunkt
            val v = sinc(cutoff * x) * cutoff * blackman(x / halfWidth)
            w[k] = v.toFloat()
            sum += v
        }
        if (sum != 0.0) for (k in 0 until taps) w[k] = (w[k] / sum).toFloat() // DC-Verstärkung 1
        return w
    }

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun blackman(r: Double): Double {
        if (r <= -1.0 || r >= 1.0) return 0.0
        val a = PI * (r + 1.0)
        return 0.42 - 0.5 * cos(a) + 0.08 * cos(2 * a)
    }

    /** Verarbeitet [n] Samples; [emit] erhält Ausgabeblöcke (Array nur während des Aufrufs gültig). */
    fun process(input: FloatArray, n: Int, emit: (FloatArray, Int) -> Unit) {
        if (passthrough) {
            if (n > 0) emit(input, n)
            return
        }
        append(input, n)
        produce(emit)
    }

    /** Restliche Samples ausgeben (Ende des Stroms). */
    fun flush(emit: (FloatArray, Int) -> Unit) {
        if (passthrough) return
        append(FloatArray(halfWidth + 1), halfWidth + 1)
        produce(emit)
    }

    private fun append(input: FloatArray, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(input, 0, buf, bufLen, n)
        bufLen += n
    }

    private fun produce(emit: (FloatArray, Int) -> Unit) {
        var o = 0
        while (true) {
            val t = outCount * step
            val base = floor(t).toLong()
            val first = base - halfWidth + 1
            if (base + halfWidth >= bufStart + bufLen) break
            val frac = t - base
            val w = table[(frac * phases + 0.5).toInt().coerceIn(0, phases)]
            var acc = 0f
            var idx = (first - bufStart).toInt()
            for (k in 0 until taps) {
                if (idx >= 0) acc += buf[idx] * w[k] // vor Stromanfang: 0
                idx++
            }
            if (o == out.size) {
                emit(out, o)
                o = 0
            }
            out[o++] = acc
            outCount++
        }
        if (o > 0) emit(out, o)
        // Verbrauchten Anfang verwerfen
        val keepFrom = floor(outCount * step).toLong() - halfWidth + 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }
}
