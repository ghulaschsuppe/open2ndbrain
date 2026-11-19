package de.open2ndbrain.transcription

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Werkzeug: exportiert Abschnitts-Embeddings (≥ 1 s) zweier Aufnahmen mit Wahrheit als CSV für
 * tools/model-eval/speaker_seeded_eval.py. Läuft nur mit O2B_SHERPA_JNI, O2B_MODELS, O2B_TEST_WAV(2),
 * O2B_TEST_TRUTH(2) und O2B_DUMP_DIR, sonst übersprungen.
 */
class SpeakerEmbeddingDumpTest {
    private fun wav(f: File): FloatArray {
        val bytes = f.readBytes()
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
        error("kein data")
    }

    @Test
    fun dump() {
        val jni = System.getenv("O2B_SHERPA_JNI")
        val models = System.getenv("O2B_MODELS")?.let(::File)
        val out = System.getenv("O2B_DUMP_DIR")?.let(::File)
        assumeTrue(jni != null && models != null && out != null)
        System.load(File(jni, "libonnxruntime.so").absolutePath)
        System.load(File(jni, "libsherpa-onnx-jni.so").absolutePath)
        val emb = SherpaEmbedder(File(models, "wespeaker_en_voxceleb_resnet34.onnx"), 4)
        for ((name, w, t) in listOf(Triple("c1", "O2B_TEST_WAV", "O2B_TEST_TRUTH"), Triple("c2", "O2B_TEST_WAV2", "O2B_TEST_TRUTH2"))) {
            val pcm = wav(File(System.getenv(w)))
            val truth = File(System.getenv(t)).readLines().filter { it.isNotBlank() }
                .map { l -> l.split(" ").let { Triple(it[0].toDouble(), it[1].toDouble(), it[2].toInt()) } }
            val seg = SherpaSegmenter(File(models, "silero_vad.onnx"), 0.25f, 8f)
            var i = 0
            while (i < pcm.size) { val n = minOf(512, pcm.size - i); seg.accept(pcm.copyOfRange(i, i + n), n); i += n }
            val sb = StringBuilder()
            for (s in seg.finish()) {
                if (s.length < 16_000) continue
                val v = emb.embed(pcm.copyOfRange(s.start.toInt(), s.end.toInt())) ?: continue
                val mid = (s.start + s.end) / 2.0 / 16000
                val r = truth.firstOrNull { mid in it.first..it.second }?.third ?: -1
                sb.append("${s.start},${s.end},$r,").append(v.joinToString(",")).append('\n')
            }
            File(out, "$name.csv").writeText(sb.toString())
        }
        emb.close()
    }
}
