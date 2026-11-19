package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.speakers.FileSpeakerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

        val pcm = readWav16k(wav!!)
        val source = PcmSource { sink -> var i = 0; while (i < pcm.size) { val n = minOf(16_000, pcm.size - i); sink(pcm.copyOfRange(i, i + n), n); i += n } }
        val vad = File(models, "silero_vad.onnx")
        val speakersDir = Files.createTempDirectory("spk").toFile()
        val repo = FileSpeakerRepository(speakersDir)
        val start = 1_700_000_000_000L
        val mapper = FileTimeMapper(listOf(RecordingSidecar.Segment(0, start, pcm.size / 16L)), start)

        val t0 = System.currentTimeMillis()
        SherpaRecognizer(File(models, "parakeet"), 4).use { rec ->
            SherpaEmbedder(File(models, "wespeaker_en_voxceleb_resnet34.onnx"), 4).use { emb ->
                val pipeline = TranscriptionPipeline(
                    recognizer = rec,
                    asrSegmenter = { SherpaSegmenter(vad, minSilenceSec = 0.5f, maxSpeechSec = 25f) },
                    speakerSegmenter = { SherpaSegmenter(vad, minSilenceSec = 0.25f, maxSpeechSec = 8f) },
                    embedder = emb,
                    matchClusters = { c -> runBlocking { repo.matchOrCreate(c, start) } },
                )
                val result = pipeline.run(source, mapper, pcm.size.toLong()) {}
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                println("Audio ${pcm.size / 16_000} s, Rechenzeit %.1f s, RTF %.3f".format(secs, secs / (pcm.size / 16_000.0)))
                result.utterances.forEach { u ->
                    println("[%7.1f s] %-12s %s".format(u.fileOffsetMs / 1000.0, u.speakerId?.take(8) ?: "-", u.text))
                }
                val speakers = runBlocking { repo.observeSpeakers().first() }
                println("Sprecherprofile: ${speakers.size} → " + speakers.joinToString { "Nr. ${it.number}: ${it.totalSpeechMs / 1000} s" })
                assertTrue("keine Äußerungen", result.utterances.isNotEmpty())
                System.getenv("O2B_TEST_TRUTH")?.let { File(it) }?.takeIf { it.isFile }?.let { truthFile ->
                    val truth = truthFile.readLines().filter { it.isNotBlank() }.map { l -> l.split(" ").let { Triple(it[0].toDouble(), it[1].toDouble(), it[2]) } }
                    // Mehrheitszuordnung Sprecher-ID → wahrer Sprecher, Anteil richtig zugeordneter Äußerungs-Sekunden
                    val pairs = result.utterances.mapNotNull { u ->
                        val mid = (u.fileOffsetMs + u.durationMs / 2) / 1000.0
                        val t = truth.firstOrNull { mid in it.first..it.second } ?: return@mapNotNull null
                        Triple(u.speakerId, t.third, u.durationMs)
                    }
                    val major = pairs.groupBy { it.first }.mapValues { (_, l) -> l.groupBy { it.second }.maxBy { it.value.sumOf { p -> p.third } }.key }
                    val ok = pairs.filter { major[it.first] == it.second }.sumOf { it.third }
                    val all = pairs.sumOf { it.third }
                    println("Sprecherzuordnung: %.0f %% der Sprechzeit richtig".format(100.0 * ok / all))
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
