package de.open2ndbrain.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Ausgabeformat einer Aufnahmedatei. */
internal enum class AudioFileFormat(val codec: String, val container: String, val extension: String) {
    OPUS_OGG("opus", "ogg", RecordingLayout.EXT_OGG),
    AAC_M4A("aac", "mp4", RecordingLayout.EXT_M4A),
}

/** Kodiert PCM16 mono in eine Datei. Nicht threadsicher; ein Objekt pro Datei. */
internal interface AudioFileEncoder {
    val format: AudioFileFormat

    /** [ptsUs] = Dateizeit des ersten Samples (lückenlos fortlaufend, unabhängig von der Wanduhr). */
    fun write(pcm: ShortArray, offset: Int, length: Int, ptsUs: Long)

    /** Schließt sauber ab. true = Datei ist vollständig und enthält Audio. */
    fun finish(): Boolean

    /** Gibt alles frei, ohne auf den Encoder zu warten (nach Fehlern). */
    fun abort()
}

/** Erzeugt Encoder; austauschbar für Tests. */
internal fun interface AudioFileEncoderFactory {
    fun create(file: File, format: AudioFileFormat, bitrateKbps: Int): AudioFileEncoder
}

/**
 * MediaCodec + MediaMuxer: Opus in OGG (API 29) bzw. AAC-LC in MP4 als Fallback.
 * Läuft synchron auf dem Writer-Thread.
 */
internal class MediaCodecAudioEncoder(
    file: File,
    override val format: AudioFileFormat,
    bitrateKbps: Int,
    private val sampleRate: Int = SAMPLE_RATE,
) : AudioFileEncoder {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var track = -1
    private var muxerStarted = false
    private var samplesWritten = 0
    private var lastPtsUs = -1L
    private var released = false
    private val info = MediaCodec.BufferInfo()

    init {
        val mediaFormat = mediaFormat(format, bitrateKbps, sampleRate)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(mediaFormat)
            ?: throw IOException("Kein Encoder für ${format.codec}")
        val c = MediaCodec.createByCodecName(name)
        try {
            c.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (t: Throwable) {
            runCatching { c.release() }
            throw t
        }
        codec = c
        muxer = try {
            MediaMuxer(
                file.absolutePath,
                if (format == AudioFileFormat.OPUS_OGG) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        } catch (t: Throwable) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            throw t
        }
        Log.i(TAG, "Encoder $name → ${file.name} (${bitrateKbps} kbit/s)")
    }

    override fun write(pcm: ShortArray, offset: Int, length: Int, ptsUs: Long) {
        var off = offset
        var remaining = length
        var pts = ptsUs
        val deadline = System.nanoTime() + STALL_TIMEOUT_NS
        while (remaining > 0) {
            val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (idx < 0) {
                drain(0)
                if (System.nanoTime() > deadline) throw IOException("Encoder nimmt keine Daten an")
                continue
            }
            val buf = codec.getInputBuffer(idx) ?: throw IOException("Eingabepuffer null")
            buf.clear()
            val n = minOf(remaining, buf.capacity() / 2)
            if (n <= 0) throw IOException("Eingabepuffer zu klein")
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, n)
            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
            off += n
            remaining -= n
            samplesWritten += n
            pts += n * 1_000_000L / sampleRate
        }
        drain(0)
    }

    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && info.size > 0 && !isConfig && muxerStarted) {
                        // Muxer verlangt monoton steigende Zeitstempel.
                        if (info.presentationTimeUs <= lastPtsUs) info.presentationTimeUs = lastPtsUs + 1
                        lastPtsUs = info.presentationTimeUs
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
                // INFO_OUTPUT_BUFFERS_CHANGED (veraltet) u. a.: ignorieren
            }
        }
    }

    override fun finish(): Boolean {
        if (released) return false
        var ok = false
        try {
            val endPts = samplesWritten * 1_000_000L / sampleRate
            val deadline = System.nanoTime() + STALL_TIMEOUT_NS
            var queued = false
            while (!queued && System.nanoTime() < deadline) {
                val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, endPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain(0)
                }
            }
            var eos = false
            while (queued && !eos && System.nanoTime() < deadline) eos = drain(OUTPUT_TIMEOUT_US)
            if (!eos) Log.w(TAG, "Encoder-EOS nicht erhalten – Datei wird trotzdem abgeschlossen")
            if (muxerStarted) {
                muxer.stop()
                ok = true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Abschluss der Datei fehlgeschlagen", t)
            ok = false
        } finally {
            releaseAll()
        }
        return ok
    }

    override fun abort() {
        if (released) return
        if (muxerStarted) runCatching { muxer.stop() } // OGG-Seiten so weit wie möglich schreiben
        releaseAll()
    }

    private fun releaseAll() {
        if (released) return
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
    }

    companion object {
        private const val TAG = "O2B-Audio"
        const val SAMPLE_RATE = 16_000
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 20_000L
        private const val STALL_TIMEOUT_NS = 2_000_000_000L

        fun mediaFormat(format: AudioFileFormat, bitrateKbps: Int, sampleRate: Int = SAMPLE_RATE): MediaFormat =
            when (format) {
                AudioFileFormat.OPUS_OGG ->
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps.coerceIn(6, 64) * 1000)
                    }
                AudioFileFormat.AAC_M4A ->
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                        // AAC braucht für vergleichbare Sprachqualität etwa die doppelte Bitrate.
                        setInteger(MediaFormat.KEY_BIT_RATE, (bitrateKbps * 2).coerceIn(24, 96) * 1000)
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }
            }.apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024) }

        /** Gibt es einen Encoder für dieses Format? (Ergebnis wird gemerkt.) */
        fun isSupported(format: AudioFileFormat): Boolean = supportCache.getOrPut(format) {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findEncoderForFormat(mediaFormat(format, 16)) != null
            }.getOrDefault(false)
        }

        private val supportCache = java.util.concurrent.ConcurrentHashMap<AudioFileFormat, Boolean>()

        /** Standard-Fabrik: Opus/OGG, falls verfügbar, sonst AAC/M4A. */
        val FACTORY = AudioFileEncoderFactory { file, fmt, kbps -> MediaCodecAudioEncoder(file, fmt, kbps) }
    }
}
