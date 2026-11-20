package de.open2ndbrain.transcription

import android.content.Context
import android.os.Build
import android.util.Log
import de.open2ndbrain.core.AsrEngine
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelLoadException
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerRepository
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.speakers.SpeakerMatcher
import de.open2ndbrain.speakers.SpeakerUpdate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Transkription mit sherpa-onnx: Silero-VAD, ASR-Modell aus [TranscriberConfig.asrModel] (FastConformer, Canary,
 * Parakeet, Whisper) und (optional) WeSpeaker-Sprecherprofile.
 *
 * - Modelle werden beim ersten [transcribe] geladen und bis [close] gehalten (~1 GB nativer Speicher).
 * - Blockierend, nicht thread-sicher; nur aus einem Hintergrund-Thread aufrufen.
 * - Fehler: [ModelLoadException] (Modell fehlt/defekt/ABI), [IOException] (Datei/Decoder, später erneut
 *   versuchen), [InterruptedException] (Abbruch; es wird nichts geschrieben), OutOfMemoryError unverändert.
 * - Sprecherprofile werden erst geändert, nachdem das Transkript geschrieben ist (idempotent je Datei).
 */
class SherpaTranscriber(
    private val context: Context,
    private val config: TranscriberConfig,
    private val speakers: SpeakerRepository,
) : Transcriber {

    private var recognizer: Recognizer? = null
    private var denoiser: SherpaDenoiser? = null
    private var embedder: SherpaEmbedder? = null
    private var closed = false

    override fun transcribe(recording: RecordingFile, onProgress: (Float) -> Unit): Transcript {
        check(!closed) { "Transcriber ist geschlossen" }
        TranscriptionPipeline.checkInterrupted()
        ensureLoaded()
        val started = System.currentTimeMillis() // Echtzeitfaktor ohne Ladezeit

        val audio = recording.audio
        if (!audio.isFile) throw IOException("Audiodatei fehlt: ${audio.name}")
        val sidecar = recording.sidecar
        val mapper = FileTimeMapper(sidecar?.segments.orEmpty(), recording.hourStartEpochMs)
        val matcher = speakers as? SpeakerMatcher
        val seenAt = sidecar?.segments?.lastOrNull()?.let { it.startEpochMs + it.durationMs } ?: recording.hourStartEpochMs
        val emb = embedder
        val audioName = audio.name.removeSuffix(".part")

        val pipeline = TranscriptionPipeline(
            recognizer = recognizer!!,
            asrSegmenter = { SherpaSegmenter(config.vadModelFile, minSilenceSec = 0.5f, maxSpeechSec = 25f) },
            // feine VAD: für Sprecher und für Modelle ohne Token-Zeitstempel (Einheiten je Abschnitt)
            speakerSegmenter = if (emb != null || !config.asrModel.hasTokenTimestamps()) {
                { SherpaSegmenter(config.vadModelFile, minSilenceSec = 0.25f, maxSpeechSec = 8f) }
            } else {
                null
            },
            embedder = emb,
            asrPreprocessor = denoiser,
            speakers = matcher,
        )
        val expected = msToSamples(recording.durationMs.takeIf { it > 0 } ?: 3_600_000L)
        val source = MediaCodecPcmSource(audio, MediaCodecPcmSource.partialPolicy(sidecar?.complete, recording.durationMs))
        val result = try {
            pipeline.run(source, mapper, expected, onProgress)
        } catch (e: NoAudioTrackException) {
            // Dauerhaft unlesbar (z. B. abgebrochene M4A ohne moov): leeres Transkript, damit sie nicht ewig wartet.
            Log.w(TAG, "Keine lesbare Audiospur in ${audio.name} – leeres Transkript", e)
            PipelineResult(emptyList(), 0, emb != null)
        }
        TranscriptionPipeline.checkInterrupted()

        val transcript = Transcript(
            audioFileName = audioName,
            asrModelId = config.asrModel.id,
            speakerModelId = if (result.speakersUsed) ModelCatalog.WESPEAKER_RESNET34.id else null,
            createdAtEpochMs = System.currentTimeMillis(),
            audioDurationMs = samplesToMs(result.decodedSamples).takeIf { it > 0 } ?: recording.durationMs,
            processingMs = System.currentTimeMillis() - started,
            utterances = result.utterances,
        )
        TranscriptFiles.write(audio, transcript)
        // Erst jetzt die Profile ändern (bei Abbruch/Fehler davor bleiben sie unverändert). Auch ohne Sprecher
        // aufrufen: ein früherer Beitrag dieser Datei wird dann abgezogen.
        if (matcher != null) {
            try {
                applySpeakersUninterruptibly(matcher, result.speakerUpdate ?: SpeakerUpdate(emptyList()), audioName, seenAt)
            } catch (e: InterruptedException) {
                throw e // Speichern läuft nicht abbrechbar zu Ende; Transkript bleibt gültig
            } catch (e: Exception) {
                // Transkript und Profile sollen zusammenpassen: Transkript verwerfen → pending() reiht neu ein
                Log.e(TAG, "Sprecherprofile nicht aktualisiert (${audio.name}) – Transkript wird verworfen", e)
                runCatching { TranscriptFiles.delete(audio) }
                throw IOException("Sprecherprofile nicht speicherbar: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
        Log.i(
            TAG,
            "${audio.name}: ${transcript.utterances.size} Äußerungen, ${transcript.audioDurationMs / 1000} s Audio " +
                "in ${transcript.processingMs / 1000} s",
        )
        return transcript
    }

    /**
     * [SpeakerMatcher.apply] ohne Abbruch: ein gesetztes Interrupt-Flag wird vorübergehend gelöscht (sonst bräche
     * runBlocking sofort ab) und danach wiederhergestellt. Das Speichern selbst ist im Repository nicht abbrechbar.
     */
    private fun applySpeakersUninterruptibly(matcher: SpeakerMatcher, update: SpeakerUpdate, audioName: String, seenAt: Long) {
        val wasInterrupted = Thread.interrupted()
        try {
            runBlocking(NonCancellable) { matcher.apply(update, audioName, seenAt) }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun ensureLoaded() {
        if (recognizer != null) return
        val t0 = System.currentTimeMillis()
        try {
            requireModelFile(config.vadModelFile)
            recognizer = createRecognizer()
            denoiser = config.denoiserModelFile?.let { SherpaDenoiser(it, config.numThreads.coerceIn(1, 4)) }
            embedder = config.speakerModelFile?.let { SherpaEmbedder(it, config.numThreads.coerceIn(1, 4)) }
        } catch (e: Throwable) {
            releaseModels()
            throw e // ModelLoadException bzw. OutOfMemoryError unverändert
        }
        Log.i(TAG, "Modelle geladen in ${System.currentTimeMillis() - t0} ms (Sprecher: ${embedder != null})")
    }

    /** sherpa-onnx-Modell bzw. (experimentell) die On-Device-Systemerkennung. */
    private fun createRecognizer(): Recognizer = when (config.asrModel.asrEngine) {
        AsrEngine.AndroidSystem -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                throw ModelLoadException("Systemerkennung mit Audiodateien erst ab Android 13")
            }
            AndroidSystemRecognizer(context, systemLanguage(config.asrModel.language))
        }
        else -> SherpaRecognizer(config.asrModelDir, config.asrModel, config.numThreads.coerceIn(1, 8))
    }

    private fun systemLanguage(lang: String?): String = when {
        lang == null -> SystemRecognizerSupport.DEFAULT_LANGUAGE
        lang.contains('-') -> lang
        lang == "de" -> "de-DE"
        else -> lang
    }

    private fun releaseModels() {
        runCatching { (recognizer as? AutoCloseable)?.close() }
        runCatching { denoiser?.close() }
        denoiser = null
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

private fun de.open2ndbrain.core.ModelSpec.hasTokenTimestamps() = asrEngine?.hasTokenTimestamps ?: true
