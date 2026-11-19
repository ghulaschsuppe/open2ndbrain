package de.open2ndbrain.core

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Vertrag für die Sprachmodelle. Modelle sind nicht in der APK, sondern werden einmalig geladen
 * (oder vom Nutzer importiert) und liegen danach unter `filesDir/models/<id>/<Dateiname>`.
 */
enum class ModelKind { Asr, Vad, SpeakerEmbedding }

data class ModelFile(
    val name: String,
    val url: String,
    /** SHA-256 (hex, klein) – nach dem Download/Import zwingend geprüft. */
    val sha256: String,
    val sizeBytes: Long,
)

data class ModelSpec(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val files: List<ModelFile>,
    val description: String,
    val languages: String,
    /** Lizenz + Namensnennung, wird im Lizenz-Bildschirm angezeigt. */
    val license: String,
    val attribution: String,
    /** Wird bei der Einrichtung empfohlen bzw. ist für die Transkription nötig. */
    val required: Boolean,
    /** Nur für [ModelKind.Asr]: welche sherpa-onnx-Engine das Modell lädt. */
    val asrEngine: AsrEngine? = null,
    /** Nur für Asr: Sprache fest eingestellt (z. B. "de"); null = automatische Erkennung bzw. einsprachig. */
    val language: String? = null,
    /** Nur für Asr: Messwerte aus `docs/dev/PHASE2-MESSUNGEN.md` für die Auswahlliste (null = nicht gemessen). */
    val benchmark: AsrBenchmark? = null,
    /** Hervorgehoben als Empfehlung in der Auswahlliste. */
    val recommended: Boolean = false,
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

/** Wie das ASR-Modell in sherpa-onnx geladen wird; bestimmt auch, ob es Wort-Zeitstempel liefert. */
enum class AsrEngine(val hasTokenTimestamps: Boolean) {
    /** NeMo Transducer (Parakeet), `OfflineTransducerModelConfig`, modelType "nemo_transducer". */
    NemoTransducer(true),
    /** NeMo CTC (FastConformer), `OfflineNemoEncDecCtcModelConfig`. */
    NemoCtc(true),
    /** NeMo Canary, `OfflineCanaryModelConfig` mit srcLang/tgtLang = [ModelSpec.language]. Keine Zeitstempel. */
    NemoCanary(false),
    /** Whisper, `OfflineWhisperModelConfig` mit language = [ModelSpec.language]. Keine Zeitstempel. */
    Whisper(false),
}

/**
 * Messwerte (FLEURS-de, 40 Sätze; „Alltag“ = leise, Hall, Rauschen 8 dB SNR, Opus 16 kbit/s).
 * Wortfehlerrate in Prozent, Echtzeitfaktor am Entwicklungsrechner (4 Threads) – am Handy grob 2–5× höher.
 */
data class AsrBenchmark(val werCleanPct: Double, val werEverydayPct: Double, val realTimeFactor: Double)

object ModelCatalog {
    val PARAKEET_V3 = ModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 (multilingual)",
        kind = ModelKind.Asr,
        files = listOf(
            hf("encoder.int8.onnx", "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479", 652_282_294),
            hf("decoder.int8.onnx", "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5", 11_845_274),
            hf("joiner.int8.onnx", "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98", 6_355_277),
            hf("tokens.txt", "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93_939),
        ),
        description = "Spracherkennung mit Satzzeichen und Groß-/Kleinschreibung. Sehr gut für Deutsch.",
        languages = "25 europäische Sprachen, u. a. Deutsch, Englisch, Französisch, Spanisch, Italienisch",
        license = "CC-BY-4.0",
        attribution = "NVIDIA parakeet-tdt-0.6b-v3, ONNX-Export von k2-fsa/sherpa-onnx",
        required = false,
        asrEngine = AsrEngine.NemoTransducer,
        benchmark = AsrBenchmark(werCleanPct = 7.9, werEverydayPct = 59.0, realTimeFactor = 0.17),
    )

    val DE_FASTCONFORMER = ModelSpec(
        id = "nemo-de-fastconformer-large-pc-int8",
        displayName = "Deutsch – schnell (FastConformer)",
        kind = ModelKind.Asr,
        files = listOf(
            hfFile("sherpa-onnx-nemo-stt_de_fastconformer_hybrid_large_pc-int8", "model.int8.onnx",
                "fcf42d4fd55b42b0b11fed368b04467f0a636a52f44bcacd3f4667697c26e259", 131_652_945),
            hfFile("sherpa-onnx-nemo-stt_de_fastconformer_hybrid_large_pc-int8", "tokens.txt",
                "abb1136142604d6d1766ad5060bd4f4b1048d7a096cd094b2d40eec3e666be9f", 10_686),
        ),
        description = "Nur Deutsch – kein Abdriften ins Englische. Sehr schnell und robust bei leisen Aufnahmen. Mit Satzzeichen.",
        languages = "Deutsch",
        license = "CC-BY-4.0",
        attribution = "NVIDIA stt_de_fastconformer_hybrid_large_pc, ONNX-Export von k2-fsa/sherpa-onnx",
        required = false,
        asrEngine = AsrEngine.NemoCtc,
        language = "de",
        benchmark = AsrBenchmark(werCleanPct = 6.6, werEverydayPct = 19.0, realTimeFactor = 0.03),
        recommended = true,
    )

    val CANARY_180M_DE = ModelSpec(
        id = "nemo-canary-180m-flash-int8",
        displayName = "Deutsch – genau (Canary 180M)",
        kind = ModelKind.Asr,
        files = listOf(
            hfFile("sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8", "encoder.int8.onnx",
                "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f", 132_678_643),
            hfFile("sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8", "decoder.int8.onnx",
                "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2", 74_437_848),
            hfFile("sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8", "tokens.txt",
                "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77", 53_555),
        ),
        description = "Höchste Genauigkeit im Test, Sprache fest auf Deutsch. Etwa 3× langsamer als „Deutsch – schnell“.",
        languages = "Deutsch (fest); kann auch Englisch, Spanisch, Französisch",
        license = "CC-BY-4.0",
        attribution = "NVIDIA canary-180m-flash, ONNX-Export von k2-fsa/sherpa-onnx",
        required = false,
        asrEngine = AsrEngine.NemoCanary,
        language = "de",
        benchmark = AsrBenchmark(werCleanPct = 5.9, werEverydayPct = 16.6, realTimeFactor = 0.09),
    )

    val WHISPER_SMALL = ModelSpec(
        id = "whisper-small-int8",
        displayName = "Whisper small (99 Sprachen)",
        kind = ModelKind.Asr,
        files = listOf(
            hfFile("sherpa-onnx-whisper-small", "small-encoder.int8.onnx",
                "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 112_442_483),
            hfFile("sherpa-onnx-whisper-small", "small-decoder.int8.onnx",
                "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 262_226_114),
            hfFile("sherpa-onnx-whisper-small", "small-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816_730),
        ),
        description = "Sehr viele Sprachen, Sprache fest auf Deutsch. Langsamer und im Alltag schwächer als die Deutsch-Modelle.",
        languages = "99 Sprachen (hier fest: Deutsch)",
        license = "MIT",
        attribution = "OpenAI Whisper, ONNX-Export von k2-fsa/sherpa-onnx",
        required = false,
        asrEngine = AsrEngine.Whisper,
        language = "de",
        benchmark = AsrBenchmark(werCleanPct = 9.3, werEverydayPct = 35.5, realTimeFactor = 0.26),
    )

    val SILERO_VAD = ModelSpec(
        id = "silero-vad",
        displayName = "Silero VAD",
        kind = ModelKind.Vad,
        files = listOf(
            ModelFile(
                "silero_vad.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", 643_854,
            ),
        ),
        description = "Erkennt Sprachabschnitte für die Transkription.",
        languages = "sprachunabhängig",
        license = "MIT",
        attribution = "Silero Team (snakers4/silero-vad)",
        required = true,
    )

    val WESPEAKER_RESNET34 = ModelSpec(
        id = "wespeaker-voxceleb-resnet34",
        displayName = "WeSpeaker ResNet34 (Sprechererkennung)",
        kind = ModelKind.SpeakerEmbedding,
        files = listOf(
            ModelFile(
                "wespeaker_en_voxceleb_resnet34.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx",
                "5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94", 26_534_365,
            ),
        ),
        description = "Erkennt, wer spricht – auch über Aufnahmen hinweg. Optional.",
        languages = "sprachunabhängig",
        license = "Apache-2.0 (WeSpeaker), trainiert auf VoxCeleb (CC-BY-4.0)",
        attribution = "WeSpeaker (wenet-e2e/wespeaker), ONNX-Export von k2-fsa/sherpa-onnx",
        required = false,
    )

    /** Auswählbare Spracherkennungsmodelle, Empfehlung zuerst. */
    val asrModels: List<ModelSpec> = listOf(DE_FASTCONFORMER, CANARY_180M_DE, PARAKEET_V3, WHISPER_SMALL)

    /** Standard für neue Installationen. */
    val DEFAULT_ASR_ID: String = DE_FASTCONFORMER.id

    val all: List<ModelSpec> = asrModels + listOf(SILERO_VAD, WESPEAKER_RESNET34)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    private fun hfFile(repo: String, name: String, sha: String, size: Long) = ModelFile(
        name, "https://huggingface.co/csukuangfj/$repo/resolve/main/$name", sha, size,
    )

    private fun hf(name: String, sha: String, size: Long) = ModelFile(
        name, "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/$name", sha, size,
    )
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelState
    data object Verifying : ModelState
    data class Installed(val dir: File) : ModelState
    data class Failed(val message: String) : ModelState
}

interface ModelManager {
    /** Zustand je Modell-ID (alle Einträge aus [ModelCatalog.all]). */
    val states: StateFlow<Map<String, ModelState>>

    /** Startet bzw. setzt einen Download fort (läuft im Hintergrund weiter, auch wenn die UI geschlossen wird). */
    fun download(id: String)

    fun cancel(id: String)

    suspend fun delete(id: String)

    /** Verzeichnis eines vollständig installierten und geprüften Modells, sonst null. */
    fun installedDir(id: String): File?
}
