package de.open2ndbrain.transcription

import de.open2ndbrain.core.Utterance
import de.open2ndbrain.speakers.ClusterSummary
import de.open2ndbrain.speakers.SpeakerClustering

/** Sprachabschnitts-Erkennung (VAD) über einen Strom; eine Instanz pro Datei. */
internal interface Segmenter {
    fun accept(samples: FloatArray, n: Int)

    /** Strom zu Ende: alle Abschnitte (absolute Sample-Positionen), Ressourcen freigeben. */
    fun finish(): List<SampleRange>
}

internal data class RawToken(val text: String, val startSec: Float, val durationSec: Float)

internal interface Recognizer {
    /** Erkennt einen Block (16 kHz float); Zeitstempel relativ zum Blockanfang. */
    fun recognize(samples: FloatArray): List<RawToken>
}

internal interface Embedder {
    /** Stimmabdruck eines Abschnitts oder null (zu kurz/nicht berechenbar). */
    fun embed(samples: FloatArray): FloatArray?
}

internal class PipelineResult(val utterances: List<Utterance>, val decodedSamples: Long, val speakersUsed: Boolean)

/**
 * Ablauf je Datei (zwei Dekodier-Durchläufe, nie die ganze Datei im RAM):
 * 1. Dekodieren → Silero-VAD grob (ASR) und fein (Sprecher) → nur Bereichsgrenzen merken.
 * 2. ASR-Blöcke planen ([BlockPlanner]); erneut dekodieren, Blöcke und Sprecherabschnitte einsammeln
 *    ([RangeCollector]), sobald vollständig: ASR bzw. Embedding.
 * 3. Sprecher lokal clustern, Cluster global abgleichen ([matchClusters]), Tokens zuordnen, Äußerungen bilden.
 */
internal class TranscriptionPipeline(
    private val recognizer: Recognizer,
    private val asrSegmenter: () -> Segmenter,
    private val speakerSegmenter: (() -> Segmenter)?,
    private val embedder: Embedder?,
    private val matchClusters: (List<ClusterSummary>) -> List<String?>,
    private val clusterThreshold: Double = SpeakerClustering.DEFAULT_THRESHOLD,
) {
    private class StopReading : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    fun run(source: PcmSource, mapper: FileTimeMapper, expectedSamples: Long, onProgress: (Float) -> Unit): PipelineResult {
        val progress = ProgressThrottle(onProgress)
        val useSpeakers = speakerSegmenter != null && embedder != null

        // --- Durchlauf 1: VAD
        val asrVad = asrSegmenter()
        val spkVad = if (useSpeakers) speakerSegmenter!!.invoke() else null
        var total = 0L
        var pass1Asr: List<SampleRange> = emptyList()
        var pass1Spk: List<SampleRange> = emptyList()
        try {
            source.read { buf, n ->
                checkInterrupted()
                asrVad.accept(buf, n)
                spkVad?.accept(buf, n)
                total += n
                progress.report(PASS1_SHARE * fraction(total, expectedSamples))
            }
        } finally {
            // finish() gibt die VAD frei – auch bei Abbruch
            pass1Asr = runCatching { asrVad.finish() }.getOrNull() ?: emptyList()
            pass1Spk = runCatching { spkVad?.finish() }.getOrNull() ?: emptyList()
        }
        checkInterrupted()
        val blocks = BlockPlanner.plan(pass1Asr, total)
        val spkSegs = pass1Spk.sortedBy { it.start }
        if (blocks.isEmpty()) {
            progress.report(1f, force = true)
            return PipelineResult(emptyList(), total, useSpeakers)
        }

        // --- Durchlauf 2: ASR + Embeddings
        val tokens = ArrayList<AsrToken>()
        val embeddings = HashMap<Int, FloatArray>()
        val items = ArrayList<Pair<SampleRange, Tag>>()
        blocks.forEach { items += it to Tag.Block }
        if (useSpeakers) {
            spkSegs.forEachIndexed { i, r -> if (r.length >= MIN_EMBED_SAMPLES) items += r to Tag.Speaker(i) }
        }
        val lastEnd = items.maxOf { it.first.end }
        var pos = 0L
        val collector = RangeCollector(items) { tag, range, data ->
            checkInterrupted()
            when (tag) {
                Tag.Block -> recognizer.recognize(data).forEach { t ->
                    val startMs = samplesToMs(range.start) + (t.startSec * 1000).toLong()
                    tokens += AsrToken(t.text, startMs, (t.durationSec * 1000).toLong().coerceAtLeast(1))
                }
                is Tag.Speaker -> embedder!!.embed(data)?.let { embeddings[tag.index] = it }
            }
        }
        try {
            source.read { buf, n ->
                checkInterrupted()
                collector.accept(buf, n)
                pos += n
                progress.report(PASS1_SHARE + (1 - PASS1_SHARE) * fraction(pos, lastEnd))
                if (collector.done) throw StopReading()
            }
        } catch (_: StopReading) {
            // alles Benötigte gelesen
        }
        collector.finish()
        checkInterrupted()

        // --- Sprecher
        val speakerAt: (Long) -> String? = if (useSpeakers && embeddings.isNotEmpty()) {
            assignSpeakers(spkSegs, embeddings)
        } else {
            { null }
        }
        val utterances = UtteranceBuilder.build(tokens, mapper, speakerAt)
        progress.report(1f, force = true)
        return PipelineResult(utterances, total, useSpeakers)
    }

    private sealed interface Tag {
        data object Block : Tag
        data class Speaker(val index: Int) : Tag
    }

    /** Liefert eine Funktion Dateizeit (ms) → Sprecher-ID. */
    private fun assignSpeakers(segs: List<SampleRange>, embeddings: Map<Int, FloatArray>): (Long) -> String? {
        val idx = embeddings.keys.sorted()
        val result = SpeakerClustering.cluster(
            idx.map { SpeakerClustering.Item(embeddings.getValue(it), samplesToMs(segs[it].length)) },
            clusterThreshold,
        )
        val ids = matchClusters(result.clusters)
        val segIds = arrayOfNulls<String>(segs.size)
        idx.forEachIndexed { k, segIndex -> segIds[segIndex] = ids.getOrNull(result.labels[k]) }
        // Kurze Abschnitte (ohne eigenes Embedding): Sprecher der Nachbarn übernehmen – wenn beide Nachbarn
        // übereinstimmen bis [NEIGHBOR_AGREE_MAX_GAP_MS] Abstand, sonst den nächsten bis [NEIGHBOR_MAX_GAP_MS].
        val embeddedIdx = idx.toIntArray()
        for (i in segs.indices) {
            if (i in embeddings) continue
            val pos = java.util.Arrays.binarySearch(embeddedIdx, i).let { -(it + 1) }
            val prev = embeddedIdx.getOrNull(pos - 1)
            val next = embeddedIdx.getOrNull(pos)
            val gapPrev = prev?.let { segs[i].start - segs[it].end } ?: Long.MAX_VALUE
            val gapNext = next?.let { segs[it].start - segs[i].end } ?: Long.MAX_VALUE
            val idPrev = prev?.let { segIds[it] }
            val idNext = next?.let { segIds[it] }
            segIds[i] = when {
                idPrev != null && idPrev == idNext && maxOf(gapPrev, gapNext) <= msToSamples(NEIGHBOR_AGREE_MAX_GAP_MS) -> idPrev
                gapPrev <= gapNext && gapPrev <= msToSamples(NEIGHBOR_MAX_GAP_MS) -> idPrev
                gapNext < gapPrev && gapNext <= msToSamples(NEIGHBOR_MAX_GAP_MS) -> idNext
                else -> null
            }
        }
        val starts = LongArray(segs.size) { segs[it].start }
        return { ms ->
            val s = msToSamples(ms)
            // letzter Abschnitt mit start ≤ s
            var lo = 0
            var hi = segs.lastIndex
            var k = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= s) {
                    k = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            when {
                k >= 0 && s < segs[k].end -> segIds[k]
                else -> {
                    // in einer Pause: nächstgelegener Abschnitt, wenn nah genug
                    val before = if (k >= 0) k else null
                    val after = if (k + 1 <= segs.lastIndex) k + 1 else null
                    val db = before?.let { s - segs[it].end } ?: Long.MAX_VALUE
                    val da = after?.let { segs[it].start - s } ?: Long.MAX_VALUE
                    val (cand, d) = if (db <= da) before to db else after to da
                    if (cand != null && d <= msToSamples(PAUSE_ASSIGN_MAX_MS)) segIds[cand] else null
                }
            }
        }
    }

    private fun fraction(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    private class ProgressThrottle(private val cb: (Float) -> Unit) {
        private var last = -1f
        private var lastAt = 0L

        fun report(p: Float, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || p - last >= 0.01f || (p > last && now - lastAt > 250_000_000L)) {
                last = p
                lastAt = now
                cb(p.coerceIn(0f, 1f))
            }
        }
    }

    companion object {
        private const val PASS1_SHARE = 0.1f
        /** Abschnitte < 1 s bekommen kein eigenes Embedding. */
        val MIN_EMBED_SAMPLES = msToSamples(1_000)
        private const val NEIGHBOR_MAX_GAP_MS = 1_000L
        private const val NEIGHBOR_AGREE_MAX_GAP_MS = 5_000L
        private const val PAUSE_ASSIGN_MAX_MS = 500L

        fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Transkription abgebrochen")
        }
    }
}
