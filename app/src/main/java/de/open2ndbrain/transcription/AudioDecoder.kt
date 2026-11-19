package de.open2ndbrain.transcription

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
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
 * Downmix auf mono und [Resampler] auf 16 kHz. Bricht der Decoder mitten in der Datei ab (z. B. nach Absturz
 * abgeschnittene Datei), wird das bis dahin Dekodierte verwendet.
 */
internal class MediaCodecPcmSource(private val file: File) : PcmSource {

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
                codec.configure(format, null, null, 0)
                codec.start()
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
        var idleLoops = 0

        fun emitPcm(buf: java.nio.ByteBuffer, size: Int) {
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
            r.process(mono, frames, sink)
        }

        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channels = (f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels).coerceAtLeast(1)
                        floatPcm = f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (resampler == null) Log.i(TAG, "Decoder: $sampleRate Hz, $channels Kanal/Kanäle, float=$floatPcm")
                        else Log.w(TAG, "Ausgabeformat geändert: $sampleRate Hz")
                        resampler = null // neu mit der aktuellen Rate
                    }
                    out >= 0 -> {
                        idleLoops = 0
                        val buf = codec.getOutputBuffer(out)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            emitPcm(buf.slice(), info.size)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> if (inputDone && ++idleLoops > MAX_IDLE_LOOPS) {
                        Log.w(TAG, "Decoder liefert kein Streamende – Abbruch nach Leerlauf")
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "Decoderfehler in ${file.name} – verwende bisher dekodiertes Audio", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoderfehler in ${file.name} – verwende bisher dekodiertes Audio", e)
        }
        resampler?.flush(sink)
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private companion object {
        const val TAG = "O2B-ML"
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_LOOPS = 300 // ~3 s ohne Ausgabe nach Eingabeende
    }
}
