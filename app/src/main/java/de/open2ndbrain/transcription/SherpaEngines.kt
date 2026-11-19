package de.open2ndbrain.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import de.open2ndbrain.core.ModelLoadException
import java.io.File

/** Prüft Modelldateien vor dem Laden (die native Bibliothek bricht bei fehlenden Dateien sonst hart ab). */
internal fun requireModelFile(f: File): String {
    if (!f.isFile || f.length() <= 0) throw ModelLoadException("Modelldatei fehlt: ${f.name}")
    return f.absolutePath
}

/** Native Modell-Ladefehler (auch UnsatisfiedLinkError) → [ModelLoadException]; OutOfMemoryError läuft durch. */
internal inline fun <T> loadModel(what: String, block: () -> T): T = try {
    block()
} catch (e: ModelLoadException) {
    throw e
} catch (e: Exception) {
    throw ModelLoadException("$what nicht ladbar: ${e.message ?: e.javaClass.simpleName}", e)
} catch (e: LinkageError) {
    throw ModelLoadException("$what nicht ladbar (native Bibliothek): ${e.message}", e)
}

/** Silero-VAD über sherpa-onnx; eine Instanz pro Datei (Positionen beginnen bei 0). */
internal class SherpaSegmenter(
    modelFile: File,
    minSilenceSec: Float,
    maxSpeechSec: Float,
    threshold: Float = 0.5f,
    minSpeechSec: Float = 0.25f,
) : Segmenter {
    private val vad = loadModel("Silero-VAD") { Vad(
        null,
        VadModelConfig().apply {
            sileroVadModelConfig = SileroVadModelConfig().apply {
                model = requireModelFile(modelFile)
                this.threshold = threshold
                minSilenceDuration = minSilenceSec
                minSpeechDuration = minSpeechSec
                windowSize = WINDOW
                maxSpeechDuration = maxSpeechSec
            }
            sampleRate = SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
        },
    ) }
    private val window = FloatArray(WINDOW)
    private var fill = 0
    private val segments = ArrayList<SampleRange>()
    private var released = false

    override fun accept(samples: FloatArray, n: Int) {
        var i = 0
        while (i < n) {
            val k = minOf(WINDOW - fill, n - i)
            System.arraycopy(samples, i, window, fill, k)
            fill += k
            i += k
            if (fill == WINDOW) {
                vad.acceptWaveform(window.copyOf())
                fill = 0
                drain()
            }
        }
    }

    private fun drain() {
        while (!vad.empty()) {
            val seg = vad.front()
            segments += SampleRange(seg.start.toLong(), seg.start.toLong() + seg.samples.size)
            vad.pop()
        }
    }

    override fun finish(): List<SampleRange> {
        if (released) return segments
        try {
            if (fill > 0) {
                window.fill(0f, fill, WINDOW)
                vad.acceptWaveform(window.copyOf())
                fill = 0
            }
            vad.flush()
            drain()
        } finally {
            released = true
            vad.release()
        }
        return segments
    }

    private companion object {
        const val WINDOW = 512
    }
}

/** Parakeet TDT (NeMo-Transducer) über den OfflineRecognizer, greedy. */
internal class SherpaRecognizer(modelDir: File, numThreads: Int) : Recognizer, AutoCloseable {
    private val recognizer = loadModel("Parakeet") { OfflineRecognizer(
        null,
        OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = OfflineModelConfig().apply {
                transducer = OfflineTransducerModelConfig().apply {
                    encoder = requireModelFile(File(modelDir, "encoder.int8.onnx"))
                    decoder = requireModelFile(File(modelDir, "decoder.int8.onnx"))
                    joiner = requireModelFile(File(modelDir, "joiner.int8.onnx"))
                }
                tokens = requireModelFile(File(modelDir, "tokens.txt"))
                this.numThreads = numThreads
                modelType = "nemo_transducer"
                provider = "cpu"
                debug = false
            }
            decodingMethod = "greedy_search"
        },
    ) }

    override fun recognize(samples: FloatArray): List<RawToken> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            val toks = r.tokens
            val ts = r.timestamps
            val ds = r.durations
            return toks.indices.map { i ->
                val start = ts.getOrElse(i) { 0f }
                // Dauer: vom Modell, sonst bis zum nächsten Token (max. 0,5 s)
                val dur = ds.getOrNull(i)?.takeIf { it > 0f }
                    ?: ((ts.getOrNull(i + 1) ?: (start + 0.08f)) - start).coerceIn(0.02f, 0.5f)
                RawToken(toks[i], start, dur)
            }
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/** WeSpeaker-ResNet34-Stimmabdruck. */
internal class SherpaEmbedder(modelFile: File, numThreads: Int) : Embedder, AutoCloseable {
    private val extractor = loadModel("WeSpeaker") {
        SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(requireModelFile(modelFile), numThreads, false, "cpu"))
    }

    override fun embed(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}
