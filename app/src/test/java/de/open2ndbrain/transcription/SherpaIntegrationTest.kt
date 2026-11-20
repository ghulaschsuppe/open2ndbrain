package de.open2ndbrain.transcription

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.speakers.FileSpeakerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Optionaler End-to-End-Test der echten sherpa-onnx-Pipeline auf dem Entwicklungsrechner (Linux-JNI statt Android).
 * Läuft nur, wenn diese Umgebungsvariablen gesetzt sind (sonst übersprungen, z. B. in CI):
 *  - O2B_SHERPA_JNI: Ordner mit libsherpa-onnx-jni.so + libonnxruntime.so (sherpa-onnx-vX-linux-x64-jni/lib)
 *  - O2B_MODELS: Ordner mit parakeet/ (encoder/decoder/joiner/tokens), silero_vad.onnx, wespeaker_en_voxceleb_resnet34.onnx
 *  - O2B_TEST_WAV: 16-kHz-Mono-WAV (PCM16); optional O2B_TEST_TRUTH: Zeilen "start end sprecher" in Sekunden
 *  - optional O2B_TEST_WAV2 / O2B_TEST_TRUTH2: zweite Aufnahme derselben Sprecher (andere Textstellen) für Runde 2 –
 *    prüft die Wiedererkennung über Dateien: gleiche wahre Sprecher müssen dieselben Profil-IDs wie in Runde 1 bekommen.
 */
class SherpaIntegrationTest {

    @Test
    fun transkribiertEchteSpracheMitSprechern() {
        val jni = System.getenv("O2B_SHERPA_JNI")
        val models = System.getenv("O2B_MODELS")?.let(::File)
        val wav = System.getenv("O2B_TEST_WAV")?.let(::File)
        assumeTrue("Integrationstest übersprungen (Umgebung fehlt)", jni != null && models != null && wav?.isFile == true)
        System.load(File(jni, "libonnxruntime.so").absolutePath)
        System.load(File(jni, "libsherpa-onnx-jni.so").absolutePath)

        fun sourceOf(pcm: FloatArray) = PcmSource { sink -> var i = 0; while (i < pcm.size) { val n = minOf(16_000, pcm.size - i); sink(pcm.copyOfRange(i, i + n), n); i += n } }
        fun truthOf(env: String) = System.getenv(env)?.let { File(it) }?.takeIf { it.isFile }?.readLines()
            ?.filter { it.isNotBlank() }
            ?.map { l -> l.split(" ").let { Triple(it[0].toDouble(), it[1].toDouble(), it[2]) } }
        val wav2 = System.getenv("O2B_TEST_WAV2")?.let(::File)?.takeIf { it.isFile }
        val first = readWav16k(wav!!) to truthOf("O2B_TEST_TRUTH")
        // Runde 2: zweite Aufnahme (falls vorhanden), Runde 3: wieder die erste Datei
        val inputs = listOf(first, (wav2?.let(::readWav16k) ?: first.first) to (if (wav2 != null) truthOf("O2B_TEST_TRUTH2") else first.second), first)
        println("Runde 2 mit ${if (wav2 != null) "zweiter Aufnahme ${wav2.name}" else "derselben Datei"}, Runde 3 mit der ersten Datei")
        val vad = File(models, "silero_vad.onnx")
        val speakersDir = Files.createTempDirectory("spk").toFile()
        val repo = FileSpeakerRepository(speakersDir)
        val start = System.currentTimeMillis() - 3_600_000L // realistisch: Profile gelten als „kürzlich gehört“
        var idToTruth: Map<String?, String> = emptyMap()

        // ASR-Modell wählbar: O2B_ASR_MODEL = ID aus ModelCatalog.asrModels (Standard Parakeet)
        val asr = System.getenv("O2B_ASR_MODEL")?.let { id -> ModelCatalog.asrModels.first { it.id == id } } ?: ModelCatalog.PARAKEET_V3
        val asrDir = File(models, mapOf(
            ModelCatalog.PARAKEET_V3.id to "parakeet",
            ModelCatalog.DE_FASTCONFORMER.id to "de-fc",
            ModelCatalog.CANARY_180M_DE.id to "canary",
            ModelCatalog.WHISPER_SMALL.id to "whisper-small",
        ).getValue(asr.id))
        val normEmb = System.getenv("O2B_NORM_EMB") == "1"
        // optional: Rauschfilter vor der ASR (O2B_DENOISE=1, Modell unter models/denoise/gtcrn_simple.onnx)
        val denoiser = if (System.getenv("O2B_DENOISE") == "1") SherpaDenoiser(File(models, "denoise/gtcrn_simple.onnx"), 4) else null
        println("Rauschfilter: ${denoiser != null}")
        println("ASR-Modell: ${asr.id} (Zeitstempel: ${asr.asrEngine?.hasTokenTimestamps}), Normalisierung Embeddings: $normEmb")
        SherpaRecognizer(asrDir, asr, 4).use { rec ->
            SherpaEmbedder(File(models, "wespeaker_en_voxceleb_resnet34.onnx"), 4).use { emb ->
                val pipeline = TranscriptionPipeline(
                    recognizer = rec,
                    asrSegmenter = { SherpaSegmenter(vad, minSilenceSec = 0.5f, maxSpeechSec = 25f) },
                    speakerSegmenter = { SherpaSegmenter(vad, minSilenceSec = 0.25f, maxSpeechSec = 8f) },
                    embedder = emb,
                    speakers = repo,
                    normalizeForEmbeddings = normEmb,
                    asrPreprocessor = denoiser,
                )
                // Runde 1: neue Profile; Runde 2/3: Wiedererkennung über Dateien, keine neuen Profile
                var profilesAfterFirst = 0
                for (round in 1..3) {
                    val (pcm, truth) = inputs[round - 1]
                    val source = sourceOf(pcm)
                    val mapper = FileTimeMapper(listOf(RecordingSidecar.Segment(0, start, pcm.size / 16L)), start)
                    val t0 = System.currentTimeMillis()
                    val result = pipeline.run(source, mapper, pcm.size.toLong()) {}
                    // wie SherpaTranscriber: Profile erst nach dem (hier gedachten) Schreiben ändern; Runde 3 = Datei 1
                    // erneut → ersetzt deren Beitrag (idempotent)
                    result.speakerUpdate?.let { u -> runBlocking { repo.apply(u, if (round == 2 && wav2 != null) "datei2" else "datei1", start) } }
                    val secs = (System.currentTimeMillis() - t0) / 1000.0
                    println("Runde $round: Audio ${pcm.size / 16_000} s, Rechenzeit %.1f s, RTF %.3f".format(secs, secs / (pcm.size / 16_000.0)))
                    if (round == 1) {
                        result.utterances.forEach { u ->
                            println("[%7.1f s] %-12s %s".format(u.fileOffsetMs / 1000.0, u.speakerId?.take(8) ?: "-", u.text))
                        }
                    }
                    val speakers = runBlocking { repo.observeSpeakers().first() }
                    println("Sprecherprofile: ${speakers.size} → " + speakers.joinToString { "Nr. ${it.number}: ${it.totalSpeechMs / 1000} s" })
                    assertTrue("keine Äußerungen", result.utterances.isNotEmpty())
                    if (round == 1) profilesAfterFirst = speakers.size
                    else assertEquals("Wiedererkennung: keine neuen Profile in Runde $round", profilesAfterFirst, speakers.size)
                    if (truth != null) {
                        // Mehrheitszuordnung Sprecher-ID → wahrer Sprecher, Anteil richtig zugeordneter Äußerungs-Sekunden
                        val pairs = result.utterances.mapNotNull { u ->
                            val mid = (u.fileOffsetMs + u.durationMs / 2) / 1000.0
                            val t = truth.firstOrNull { mid in it.first..it.second } ?: return@mapNotNull null
                            Triple(u.speakerId, t.third, u.durationMs)
                        }
                        // Runde 1: Mehrheitszuordnung festlegen; Runde 2: dieselbe Zuordnung verwenden (= Wiedererkennung)
                        val major = if (round == 1) {
                            pairs.groupBy { it.first }.mapValues { (_, l) -> l.groupBy { it.second }.maxBy { it.value.sumOf { p -> p.third } }.key }
                                .also { idToTruth = it }
                        } else idToTruth
                        val ok = pairs.filter { it.first != null && major[it.first] == it.second }.sumOf { it.third }
                        val all = pairs.sumOf { it.third }
                        val share = ok.toDouble() / all
                        println("Sprecherzuordnung Runde $round: %.0f %% der Sprechzeit richtig".format(100.0 * share))
                        val trueSpeakers = truth.map { it.third }.distinct().size
                        assertEquals("Anzahl Profile", trueSpeakers, speakers.size)
                        assertTrue("Sprecherzuordnung %.0f %% < 90 %%".format(100 * share), share >= 0.9)
                    }
                }
            }
        }
    }

    private fun readWav16k(f: File): FloatArray {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val bytes = inp.readBytes()
            // Datenblock "data" suchen
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4)
                val len = ByteBuffer.wrap(bytes, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") {
                    val bb = ByteBuffer.wrap(bytes, p + 8, minOf(len, bytes.size - p - 8)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    return FloatArray(bb.remaining()) { bb.get(it) / 32768f }
                }
                p += 8 + len
            }
        }
        error("kein data-Block")
    }
}
