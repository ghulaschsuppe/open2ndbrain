package de.open2ndbrain.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Liefert eine Datei als PCM float, 16 kHz mono, in Blöcken. Mehrfach aufrufbar (zwei Durchläufe). */
internal fun interface PcmSource {
    /** Ruft [sink] für jeden Block auf (Array nur während des Aufrufs gültig). */
    fun read(sink: (FloatArray, Int) -> Unit)
}

/** Datei hat keine lesbare Audiospur (dauerhaftes Problem, kein Neuversuch sinnvoll). */
internal class NoAudioTrackException(msg: String) : IOException(msg)

/**
 * Dekodiert OGG/Opus bzw. M4A/AAC per MediaExtractor + MediaCodec. Der Opus-Decoder liefert meist 48 kHz →
 * Downmix auf mono und [Resampler] auf 16 kHz.
 *
 * Fehlerbehandlung:
 * - Alle Fehler der MediaCodec-/MediaExtractor-Aufrufe (auch `configure`/`start`) werden zu [IOException].
 *   Ausnahmen aus dem [PcmSource.read]-Sink (Pipeline, Abbruch) laufen unverändert durch.
 * - Vorübergehende Decoderfehler (`isTransient`/`isRecoverable`) → immer [IOException] (Neuversuch später).
 * - Bricht der Decoder sonst mitten in der Datei ab (z. B. nach Absturz abgeschnittene Datei), wird das bisher
 *   Dekodierte nur verwendet, wenn [acceptPartial] (dekodierte ms) zustimmt; sonst [IOException].
 * - Kein Fortschritt (weder Eingabe noch Ausgabe) für [STALL_TIMEOUT_MS] → [IOException].
 */
internal class MediaCodecPcmSource(
    private val file: File,
    private val acceptPartial: (decodedMs: Long) -> Boolean = { false },
) : PcmSource {

    /** Fehler eines MediaCodec-/MediaExtractor-Aufrufs, intern; wird zu IOException bzw. Teil-Audio. */
    private class CodecFailure(val transient: Boolean, cause: Throwable) : Exception(cause)

    private inline fun <T> mc(block: () -> T): T = try {
        block()
    } catch (e: MediaCodec.CodecException) {
        throw CodecFailure(e.isTransient || e.isRecoverable, e)
    } catch (e: IllegalStateException) {
        throw CodecFailure(false, e)
    } catch (e: IllegalArgumentException) {
        throw CodecFailure(false, e)
    }

    override fun read(sink: (FloatArray, Int) -> Unit) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: Exception) {
                throw NoAudioTrackException("Datei nicht lesbar: ${file.name} (${e.message})")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioTrackException("Keine Audiospur: ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("Kein Decoder für $mime", e)
            }
            try {
                try {
                    mc { codec.configure(format, null, null, 0) }
                    mc { codec.start() }
                } catch (f: CodecFailure) {
                    throw IOException("Decoder für $mime nicht startbar", f.cause)
                }
                decodeLoop(extractor, codec, format, sink)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec, inFormat: MediaFormat, sink: (FloatArray, Int) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var sampleRate = inFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: SAMPLE_RATE
        var channels = inFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        var floatPcm = false
        var resampler: Resampler? = null
        var mono = FloatArray(8192)
        var lastProgressNs = System.nanoTime()
        var emitted = 0L
        val countingSink: (FloatArray, Int) -> Unit = { b, n ->
            emitted += n
            sink(b, n)
        }

        fun emitPcm(buf: ByteBuffer, size: Int) {
            val r = resampler ?: Resampler(sampleRate, SAMPLE_RATE).also { resampler = it }
            val bytesPerSample = if (floatPcm) 4 else 2
            val frames = size / (bytesPerSample * channels)
            if (frames <= 0) return
            if (mono.size < frames) mono = FloatArray(frames)
            buf.order(ByteOrder.nativeOrder())
            if (floatPcm) {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    var acc = 0f
                    for (c in 0 until channels) acc += fb.get(i * channels + c)
                    mono[i] = acc / channels
                }
            } else {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    var acc = 0
                    for (c in 0 until channels) acc += sb.get(i * channels + c)
                    mono[i] = acc / (32768f * channels)
                }
            }
            r.process(mono, frames, countingSink)
        }

        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (System.nanoTime() - lastProgressNs > STALL_TIMEOUT_MS * 1_000_000) {
                    throw IOException("Decoder hängt (${STALL_TIMEOUT_MS / 1000} s ohne Fortschritt): ${file.name}")
                }
                if (!inputDone) {
                    val idx = mc { codec.dequeueInputBuffer(TIMEOUT_US) }
                    if (idx >= 0) {
                        val n = mc {
                            val buf = codec.getInputBuffer(idx) ?: throw IllegalStateException("Eingabepuffer null")
                            extractor.readSampleData(buf, 0)
                        }
                        if (n < 0) {
                            mc { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                            inputDone = true
                        } else {
                            mc {
                                codec.queueInputBuffer(idx, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                        lastProgressNs = System.nanoTime()
                    }
                }
                val out = mc { codec.dequeueOutputBuffer(info, TIMEOUT_US) }
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = mc { codec.outputFormat }
                        // Restliche Samples der alten Rate ausgeben, bevor neu angelegt wird
                        resampler?.flush(countingSink)
                        sampleRate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channels = (f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels).coerceAtLeast(1)
                        floatPcm = f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        Log.i(TAG, "Decoder: $sampleRate Hz, $channels Kanal/Kanäle, float=$floatPcm")
                        resampler = null
                        lastProgressNs = System.nanoTime()
                    }
                    out >= 0 -> {
                        lastProgressNs = System.nanoTime()
                        val buf = mc { codec.getOutputBuffer(out) }
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            emitPcm(buf.slice(), info.size) // außerhalb von codec{}: Sink-Ausnahmen laufen durch
                        }
                        mc { codec.releaseOutputBuffer(out, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (f: CodecFailure) {
            val decodedMs = samplesToMs(emitted)
            if (f.transient) throw IOException("Vorübergehender Decoderfehler in ${file.name}", f.cause)
            resampler?.flush(countingSink)
            if (acceptPartial(samplesToMs(emitted))) {
                Log.w(TAG, "Decoderfehler in ${file.name} nach $decodedMs ms – verwende bisher dekodiertes Audio", f.cause)
                return
            }
            throw IOException("Decoderfehler in ${file.name} nach $decodedMs ms", f.cause)
        }
        resampler?.flush(countingSink)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    companion object {
        private const val TAG = "O2B-ML"
        private const val TIMEOUT_US = 10_000L
        const val STALL_TIMEOUT_MS = 10_000L

        /**
         * Regel für Teil-Audio: nur bei nicht sauber abgeschlossener Aufnahme (Sidecar `complete == false`, z. B.
         * nach Absturz wiederhergestellt) oder wenn mindestens 95 % der erwarteten Dauer dekodiert wurden.
         */
        fun partialPolicy(sidecarComplete: Boolean?, expectedMs: Long): (Long) -> Boolean = { decodedMs ->
            sidecarComplete == false || (expectedMs > 0 && decodedMs >= expectedMs * 95 / 100)
        }
    }
}
