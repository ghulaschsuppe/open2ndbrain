package de.open2ndbrain.transcription

import android.content.Context
import android.util.Log
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerRepository
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.speakers.SpeakerMatcher
import java.io.IOException

/**
 * Transkription mit sherpa-onnx: Silero-VAD, Parakeet TDT v3 und (optional) WeSpeaker-Sprecherprofile.
 *
 * - Modelle werden beim ersten [transcribe] geladen und bis [close] gehalten (~1 GB nativer Speicher).
 * - Blockierend, nicht thread-sicher; nur aus einem Hintergrund-Thread aufrufen.
 * - Abbruch über Thread-Interrupt → [InterruptedException], es wird dann nichts geschrieben.
 */
class SherpaTranscriber(
    @Suppress("unused") private val context: Context,
    private val config: TranscriberConfig,
    private val speakers: SpeakerRepository,
) : Transcriber {

    private var recognizer: SherpaRecognizer? = null
    private var embedder: SherpaEmbedder? = null
    private var closed = false

    override fun transcribe(recording: RecordingFile, onProgress: (Float) -> Unit): Transcript {
        check(!closed) { "Transcriber ist geschlossen" }
        val started = System.currentTimeMillis()
        TranscriptionPipeline.checkInterrupted()
        ensureLoaded()

        val audio = recording.audio
        if (!audio.isFile) throw IOException("Audiodatei fehlt: ${audio.name}")
        val sidecar = recording.sidecar
        val mapper = FileTimeMapper(sidecar?.segments.orEmpty(), recording.hourStartEpochMs)
        val matcher = speakers as? SpeakerMatcher
        val seenAt = sidecar?.segments?.lastOrNull()?.let { it.startEpochMs + it.durationMs } ?: recording.hourStartEpochMs
        val emb = embedder

        val pipeline = TranscriptionPipeline(
            recognizer = recognizer!!,
            asrSegmenter = { SherpaSegmenter(config.vadModelFile, minSilenceSec = 0.5f, maxSpeechSec = 25f) },
            speakerSegmenter = if (emb != null) {
                { SherpaSegmenter(config.vadModelFile, minSilenceSec = 0.25f, maxSpeechSec = 8f) }
            } else {
                null
            },
            embedder = emb,
            speakers = matcher,
            seenAtEpochMs = seenAt,
        )
        val expected = msToSamples(recording.durationMs.takeIf { it > 0 } ?: 3_600_000L)
        val result = try {
            pipeline.run(MediaCodecPcmSource(audio), mapper, expected, onProgress)
        } catch (e: NoAudioTrackException) {
            // Dauerhaft unlesbar (z. B. abgebrochene M4A ohne moov): leeres Transkript, damit sie nicht ewig wartet.
            Log.w(TAG, "Keine lesbare Audiospur in ${audio.name} – leeres Transkript", e)
            PipelineResult(emptyList(), 0, emb != null)
        }
        TranscriptionPipeline.checkInterrupted()

        val transcript = Transcript(
            audioFileName = audio.name.removeSuffix(".part"),
            asrModelId = ModelCatalog.PARAKEET_V3.id,
            speakerModelId = if (result.speakersUsed) ModelCatalog.WESPEAKER_RESNET34.id else null,
            createdAtEpochMs = System.currentTimeMillis(),
            audioDurationMs = samplesToMs(result.decodedSamples).takeIf { it > 0 } ?: recording.durationMs,
            processingMs = System.currentTimeMillis() - started,
            utterances = result.utterances,
        )
        TranscriptFiles.write(audio, transcript)
        Log.i(
            TAG,
            "${audio.name}: ${transcript.utterances.size} Äußerungen, ${transcript.audioDurationMs / 1000} s Audio " +
                "in ${transcript.processingMs / 1000} s",
        )
        return transcript
    }

    private fun ensureLoaded() {
        if (recognizer != null) return
        val t0 = System.currentTimeMillis()
        try {
            requireModelFile(config.vadModelFile)
            recognizer = SherpaRecognizer(config.asrModelDir, config.numThreads.coerceIn(1, 8))
            embedder = config.speakerModelFile?.let { SherpaEmbedder(it, config.numThreads.coerceIn(1, 4)) }
        } catch (e: IllegalStateException) {
            releaseModels()
            throw e
        } catch (e: Throwable) {
            // z. B. UnsatisfiedLinkError (ABI) oder Fehler beim Laden der ONNX-Modelle
            releaseModels()
            throw IllegalStateException("Modelle nicht ladbar: ${e.message ?: e.javaClass.simpleName}", e)
        }
        Log.i(TAG, "Modelle geladen in ${System.currentTimeMillis() - t0} ms (Sprecher: ${embedder != null})")
    }

    private fun releaseModels() {
        runCatching { recognizer?.close() }
        runCatching { embedder?.close() }
        recognizer = null
        embedder = null
    }

    override fun close() {
        closed = true
        releaseModels()
    }

    private companion object {
        const val TAG = "O2B-ML"
    }
}
