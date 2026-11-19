package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingSidecar.Segment
import de.open2ndbrain.speakers.FileSpeakerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ende-zu-Ende-Test der Pipeline mit Attrappen. Kodierung des synthetischen Signals:
 * ein „Wort“ = 400 ms konstante Amplitude. Amplitude 0,1x → Sprecher A, 0,2x → Sprecher B; x = Wortnummer.
 */
class TranscriptionPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 16_000

    private class Word(val atMs: Long, val speaker: Int, val n: Int, val last: Boolean = false)

    private fun signal(words: List<Word>, totalMs: Long): FloatArray {
        val a = FloatArray((totalMs * sr / 1000).toInt())
        for (w in words) {
            val v = (w.speaker + 1) * 0.1f + w.n * 0.001f + if (w.last) 0.0005f else 0f
            val s = (w.atMs * sr / 1000).toInt()
            for (i in s until minOf(a.size, s + sr * 4 / 10)) a[i] = v
        }
        return a
    }

    private class ArraySource(val data: FloatArray, val chunk: Int = 4_000) : PcmSource {
        var reads = 0
        override fun read(sink: (FloatArray, Int) -> Unit) {
            reads++
            var i = 0
            while (i < data.size) {
                val n = minOf(chunk, data.size - i)
                sink(data.copyOfRange(i, i + n), n)
                i += n
            }
        }
    }

    /** VAD-Attrappe: Nicht-Null-Bereiche, Lücken < [minGapMs] werden überbrückt. */
    private class FakeVad(val minGapMs: Long) : Segmenter {
        val out = ArrayList<SampleRange>()
        var pos = 0L
        var start = -1L
        var lastNonZero = -1L
        override fun accept(samples: FloatArray, n: Int) {
            for (i in 0 until n) {
                val p = pos + i
                if (samples[i] != 0f) {
                    if (start >= 0 && p - lastNonZero > msToSamples(minGapMs)) {
                        out += SampleRange(start, lastNonZero + 1)
                        start = -1
                    }
                    if (start < 0) start = p
                    lastNonZero = p
                }
            }
            pos += n
        }

        override fun finish(): List<SampleRange> {
            if (start >= 0) out += SampleRange(start, lastNonZero + 1)
            return out
        }
    }

    /** ASR-Attrappe: ein Token je Wort (Amplitudenplateau), Satzende bei markiertem letzten Wort. */
    private class FakeAsr : Recognizer {
        var calls = 0
        override fun recognize(samples: FloatArray): List<RawToken> {
            calls++
            val toks = ArrayList<RawToken>()
            var i = 0
            while (i < samples.size) {
                val v = samples[i]
                if (v == 0f) { i++; continue }
                var j = i
                while (j < samples.size && samples[j] == v) j++
                val whole = (v * 1000 + 1e-3).toInt()
                val n = whole % 100
                val last = v * 1000 - whole > 0.25 // 0,0005-Markierung
                toks += RawToken(" W$n", i / 16_000f, (j - i) / 16_000f)
                if (last) toks += RawToken(".", j / 16_000f, 0.01f)
                i = j
            }
            return toks
        }
    }

    /** Embedding-Attrappe: Sprecher aus mittlerer Amplitude → nahezu orthogonale Vektoren. */
    private class FakeEmbedder : Embedder {
        override fun embed(samples: FloatArray): FloatArray {
            val nz = samples.filter { it != 0f }
            val spk = if (nz.isEmpty()) 0 else (nz.average() * 10).toInt()
            return FloatArray(8) { if (it == spk) 1f else 0.05f * (it + 1) / 8 }
        }
    }

    private fun words(): List<Word> {
        val w = ArrayList<Word>()
        var t = 1_000L
        var n = 1
        // A spricht 3 Sätze à 4 Wörter, dann 3 s Pause, B 2 Sätze, dann A wieder
        repeat(3) { repeat(4) { k -> w += Word(t, 0, n++, last = k == 3); t += 500 } }
        t += 3_000
        repeat(2) { repeat(4) { k -> w += Word(t, 1, n++, last = k == 3); t += 500 } }
        t += 3_000
        repeat(4) { k -> w += Word(t, 0, n++, last = k == 3); t += 500 }
        return w
    }

    private fun pipeline(asr: FakeAsr, speakers: FileSpeakerRepository?) = TranscriptionPipeline(
        recognizer = asr,
        asrSegmenter = { FakeVad(minGapMs = 500) },
        speakerSegmenter = speakers?.let { { FakeVad(minGapMs = 250) } },
        embedder = speakers?.let { FakeEmbedder() },
        speakers = speakers,
    )

    @Test
    fun endToEndWithSpeakersAndWallClock() {
        val totalMs = 30_000L
        val source = ArraySource(signal(words(), totalMs))
        val mapper = FileTimeMapper(listOf(Segment(0, 1_000_000_000L, totalMs)), 0)
        val progress = ArrayList<Float>()
        val asr = FakeAsr()
        val repo = FileSpeakerRepository(tmp.root)
        val r = pipeline(asr, repo).run(source, mapper, msToSamples(totalMs)) { progress += it }

        assertEquals(2, source.reads) // zwei Durchläufe
        assertEquals(msToSamples(totalMs), r.decodedSamples)
        assertTrue(r.speakersUsed)
        assertEquals(6, r.utterances.size)
        assertEquals("W1 W2 W3 W4.", r.utterances[0].text)
        assertEquals("W13 W14 W15 W16.", r.utterances[3].text)
        assertEquals(1_000L, r.utterances[0].fileOffsetMs)
        assertEquals(1_000_001_000L, r.utterances[0].startEpochMs)
        val ids = r.utterances.map { it.speakerId }
        ids.forEach { assertNotNull(it) }
        assertEquals(ids[0], ids[1])
        assertEquals(ids[0], ids[5])
        assertEquals(ids[3], ids[4])
        assertNotEquals(ids[0], ids[3])
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(asr.calls in 3..4) // Blöcke: A (≤ 20 s, Lücken < 2 s), B, A
    }

    @Test
    fun withoutSpeakerModelNoIds() {
        val src = ArraySource(signal(words(), 30_000))
        val r = pipeline(FakeAsr(), null).run(src, FileTimeMapper(emptyList(), 0), msToSamples(30_000)) {}
        assertEquals(6, r.utterances.size)
        assertTrue(r.utterances.all { it.speakerId == null })
        assertTrue(!r.speakersUsed)
    }

    @Test
    fun silenceGivesNoUtterancesAndSinglePass() {
        val src = ArraySource(FloatArray(sr * 5))
        val asr = FakeAsr()
        val r = pipeline(asr, null).run(src, FileTimeMapper(emptyList(), 0), msToSamples(5_000)) {}
        assertTrue(r.utterances.isEmpty())
        assertEquals(1, src.reads)
        assertEquals(0, asr.calls)
    }

    @Test
    fun interruptAborts() {
        val src = ArraySource(signal(words(), 30_000))
        Thread.currentThread().interrupt()
        try {
            pipeline(FakeAsr(), null).run(src, FileTimeMapper(emptyList(), 0), msToSamples(30_000)) {}
            fail("InterruptedException erwartet")
        } catch (_: InterruptedException) {
            // erwartet
        } finally {
            Thread.interrupted() // Flag zurücksetzen
        }
    }
}
