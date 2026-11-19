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
) {
    val sizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

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
        required = true,
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

    val all: List<ModelSpec> = listOf(PARAKEET_V3, SILERO_VAD, WESPEAKER_RESNET34)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

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
