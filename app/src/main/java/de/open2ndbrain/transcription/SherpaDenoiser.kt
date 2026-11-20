package de.open2ndbrain.transcription

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import java.io.File

/** Vorstufe vor der ASR (nie vor den Sprecher-Embeddings). */
internal fun interface AudioPreprocessor {
    fun process(samples: FloatArray): FloatArray
}

/**
 * Optionaler Rauschfilter (GTCRN) über sherpa-onnx. Liefert immer 16 kHz (bei anderer Modellrate umgerechnet).
 * Gemessen schadet er FastConformer (19,0 % → 24,0 % WER, siehe docs/dev/PHASE2-MESSUNGEN.md) → Standard aus.
 */
internal class SherpaDenoiser(modelFile: File, numThreads: Int) : AudioPreprocessor, AutoCloseable {
    private val denoiser = loadModel("Rauschfilter") {
        OfflineSpeechDenoiser(
            null,
            OfflineSpeechDenoiserConfig().apply {
                model = OfflineSpeechDenoiserModelConfig().apply {
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig().apply { model = requireModelFile(modelFile) }
                    this.numThreads = numThreads
                    provider = "cpu"
                    debug = false
                }
            },
        )
    }

    override fun process(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val out = denoiser.run(samples, SAMPLE_RATE)
        val data = out.samples
        if (out.sampleRate == SAMPLE_RATE || out.sampleRate <= 0) return data
        val res = FloatArrayBuilder(samples.size)
        val r = Resampler(out.sampleRate, SAMPLE_RATE)
        r.process(data, data.size, res::add)
        r.flush(res::add)
        return res.toArray()
    }

    override fun close() = denoiser.release()
}

/** Wachsender Float-Puffer (ohne Boxing). */
internal class FloatArrayBuilder(initial: Int = 1024) {
    private var buf = FloatArray(maxOf(16, initial))
    private var size = 0

    fun add(src: FloatArray, n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        System.arraycopy(src, 0, buf, size, n)
        size += n
    }

    fun toArray(): FloatArray = buf.copyOf(size)
}
