package de.open2ndbrain.transcription

import de.open2ndbrain.core.Utterance
import de.open2ndbrain.speakers.ClusterAssignment
import de.open2ndbrain.speakers.SpeakerClustering
import de.open2ndbrain.speakers.SpeakerMatcher
import de.open2ndbrain.speakers.SpeakerPlanning
import de.open2ndbrain.speakers.SpeakerUpdate
import kotlinx.coroutines.runBlocking

/** Sprachabschnitts-Erkennung (VAD) über einen Strom; eine Instanz pro Datei. */
internal interface Segmenter {
    fun accept(samples: FloatArray, n: Int)

    /** Strom zu Ende: alle Abschnitte (absolute Sample-Positionen), Ressourcen freigeben. */
    fun finish(): List<SampleRange>
}

internal data class RawToken(val text: String, val startSec: Float, val durationSec: Float)

internal interface Recognizer {
    /** Liefert das Modell Zeitstempel je Token? Sonst nur einen Text je Block ([recognize] → ein Token bei 0). */
    val hasTokenTimestamps: Boolean get() = true

    /** Erkennt einen Block (16 kHz float); Zeitstempel relativ zum Blockanfang. */
    fun recognize(samples: FloatArray): List<RawToken>
}

internal interface Embedder {
    /** Stimmabdruck eines Abschnitts oder null (zu kurz/nicht berechenbar). */
    fun embed(samples: FloatArray): FloatArray?
}

internal class PipelineResult(
    val utterances: List<Utterance>,
    val decodedSamples: Long,
    val speakersUsed: Boolean,
    /** Vorgemerkte Profiländerungen; erst nach dem Schreiben des Transkripts anwenden. */
    val speakerUpdate: SpeakerUpdate? = null,
)

/**
 * Ablauf je Datei (mehrere Dekodier-Durchläufe, nie die ganze Datei im RAM):
 * 1. Dekodieren → Silero-VAD grob (ASR-Blöcke) und fein (Sprecher; ohne Token-Zeitstempel auch ASR-Einheiten).
 * 2. **Mit Token-Zeitstempeln** (Transducer, CTC): Blöcke ([BlockPlanner]) und Sprecherabschnitte in einem
 *    Durchlauf einsammeln ([RangeCollector]) → ASR bzw. Embedding.
 *    **Ohne** (Canary, Whisper): erst Embeddings und Sprecher, dann feine Abschnitte gleichen Sprechers zu Einheiten
 *    ≤ [UNIT_MAX_MS] ([UnitPlanner]) und je Einheit erkennen; Wortzeiten werden proportional geschätzt.
 * 3. Sprecher clustern (bekannte Profile als Start-Cluster), IDs vormerken ([SpeakerPlanning.plan]), Tokens
 *    zuordnen, Äußerungen bilden. Die Profile ändert erst der Aufrufer nach dem Schreiben des Transkripts.
 * Vor ASR wird jeder Block lautstärkenormalisiert ([Loudness]).
 */
internal class TranscriptionPipeline(
    private val recognizer: Recognizer,
    private val asrSegmenter: () -> Segmenter,
    /** Feine VAD; nötig für Sprecher und für Modelle ohne Token-Zeitstempel. */
    private val speakerSegmenter: (() -> Segmenter)?,
    private val embedder: Embedder?,
    /** null → Sprecher werden nur innerhalb der Datei unterschieden (keine IDs). */
    private val speakers: SpeakerMatcher?,
    /** Lautstärkenormalisierung auch vor dem Embedding (im Integrationstest geprüft). */
    private val normalizeForEmbeddings: Boolean = false,
    /** Optionale Vorstufe vor der ASR (z. B. Rauschfilter); nie vor den Embeddings. */
    private val asrPreprocessor: AudioPreprocessor? = null,
) {
    /** Audio für die Erkennung: Vorstufe (falls an), dann Lautstärkenormalisierung. */
    private fun forAsr(data: FloatArray): FloatArray = Loudness.normalize(asrPreprocessor?.process(data) ?: data)

    private class StopReading : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private sealed interface Tag {
        data object Block : Tag
        data class Speaker(val index: Int) : Tag
    }

    fun run(source: PcmSource, mapper: FileTimeMapper, expectedSamples: Long, onProgress: (Float) -> Unit): PipelineResult {
        val progress = ProgressThrottle(onProgress)
        val useSpeakers = speakerSegmenter != null && embedder != null
        val timestamps = recognizer.hasTokenTimestamps
        check(timestamps || speakerSegmenter != null) { "Modelle ohne Zeitstempel brauchen die feine VAD" }

        // --- Durchlauf 1: VAD
        val asrVad = if (timestamps) asrSegmenter() else null
        val fineVad = if (useSpeakers || !timestamps) speakerSegmenter!!.invoke() else null
        var total = 0L
        var coarse: List<SampleRange> = emptyList()
        var fine: List<SampleRange> = emptyList()
        try {
            source.read { buf, n ->
                checkInterrupted()
                asrVad?.accept(buf, n)
                fineVad?.accept(buf, n)
                total += n
                progress.report(PASS1_SHARE * fraction(total, expectedSamples))
            }
        } finally {
            // finish() gibt die VAD frei – auch bei Abbruch
            coarse = runCatching { asrVad?.finish() }.getOrNull() ?: emptyList()
            fine = (runCatching { fineVad?.finish() }.getOrNull() ?: emptyList()).sortedBy { it.start }
        }
        checkInterrupted()
        if ((timestamps && coarse.isEmpty()) || (!timestamps && fine.isEmpty())) {
            progress.report(1f, force = true)
            return PipelineResult(emptyList(), total, useSpeakers)
        }
        val embedIdx = if (useSpeakers) fine.indices.filter { fine[it].length >= MIN_EMBED_SAMPLES } else emptyList()
        val embeddings = HashMap<Int, FloatArray>()
        val tokens = ArrayList<AsrToken>()

        fun onEmbed(i: Int, data: FloatArray) {
            val d = if (normalizeForEmbeddings) Loudness.normalize(data) else data
            embedder!!.embed(d)?.let { embeddings[i] = it }
        }

        var speakerUpdate: SpeakerUpdate? = null
        var segIds: Array<String?> = arrayOfNulls(fine.size)
        if (timestamps) {
            // --- Durchlauf 2: ASR-Blöcke + Embeddings gemeinsam
            val items = ArrayList<Pair<SampleRange, Tag>>()
            BlockPlanner.plan(coarse, total).forEach { items += it to Tag.Block }
            embedIdx.forEach { items += fine[it] to Tag.Speaker(it) }
            collect(source, items, progress, PASS1_SHARE, 1f) { tag, range, data ->
                when (tag) {
                    Tag.Block -> recognizer.recognize(forAsr(data)).forEach { t -> tokens += t.toAsrToken(range.start) }
                    is Tag.Speaker -> onEmbed(tag.index, data)
                }
            }
            if (embeddings.isNotEmpty()) assignSpeakers(fine, embeddings).let { (ids, u) -> segIds = ids; speakerUpdate = u }
        } else {
            // --- Durchlauf 2 (nur mit Sprechern): Embeddings → Sprecher je feinem Abschnitt
            var asrFrom = PASS1_SHARE
            if (embedIdx.isNotEmpty()) {
                collect(source, embedIdx.map { fine[it] to Tag.Speaker(it) }, progress, PASS1_SHARE, EMBED_PASS_END) { tag, _, data ->
                    onEmbed((tag as Tag.Speaker).index, data)
                }
                if (embeddings.isNotEmpty()) assignSpeakers(fine, embeddings).let { (ids, u) -> segIds = ids; speakerUpdate = u }
                asrFrom = EMBED_PASS_END
            }
            // --- Durchlauf 3: ASR je Einheit (feine Abschnitte gleichen Sprechers, ≤ 15 s)
            val units = UnitPlanner.plan(fine, segIds.toList(), total)
            collect(source, units.map { it.padded to it }, progress, asrFrom, 1f) { unit, range, data ->
                val text = recognizer.recognize(forAsr(data)).joinToString(" ") { it.text }
                tokens += UnitPlanner.wordTokens(text, unit.speech.start, minOf(unit.speech.end, range.end))
            }
        }
        checkInterrupted()

        val starts = LongArray(fine.size) { fine[it].start }
        val ids = segIds
        val speakerAt: (Long) -> String? = if (ids.any { it != null }) { ms -> speakerAtSample(fine, starts, ids, msToSamples(ms)) } else { _ -> null }
        val utterances = UtteranceBuilder.build(tokens, mapper, speakerAt)
        progress.report(1f, force = true)
        return PipelineResult(utterances, total, useSpeakers, speakerUpdate)
    }

    private fun RawToken.toAsrToken(blockStart: Long) =
        AsrToken(text, samplesToMs(blockStart) + (startSec * 1000).toLong(), (durationSec * 1000).toLong().coerceAtLeast(1))

    /** Ein Dekodier-Durchlauf, der nur die Bereiche [items] einsammelt; endet vorzeitig, wenn alles da ist. */
    private fun <T> collect(
        source: PcmSource,
        items: List<Pair<SampleRange, T>>,
        progress: ProgressThrottle,
        from: Float,
        to: Float,
        onComplete: (T, SampleRange, FloatArray) -> Unit,
    ) {
        if (items.isEmpty()) return
        val lastEnd = items.maxOf { it.first.end }
        var pos = 0L
        val collector = RangeCollector(items) { tag, range, data ->
            checkInterrupted()
            onComplete(tag, range, data)
        }
        try {
            source.read { buf, n ->
                checkInterrupted()
                collector.accept(buf, n)
                pos += n
                progress.report(from + (to - from) * fraction(pos, lastEnd))
                if (collector.done) throw StopReading()
            }
        } catch (_: StopReading) {
            // alles Benötigte gelesen
        }
        collector.finish()
        checkInterrupted()
    }

    /** Sprecher-ID je feinem Abschnitt und vorgemerkte Profiländerungen. */
    private fun assignSpeakers(segs: List<SampleRange>, embeddings: Map<Int, FloatArray>): Pair<Array<String?>, SpeakerUpdate?> {
        val idx = embeddings.keys.sorted()
        val known = speakers?.let { runBlocking { it.knownProfiles() } }.orEmpty()
        val result = SpeakerClustering.clusterSeeded(
            idx.map { SpeakerClustering.Item(embeddings.getValue(it), samplesToMs(segs[it].length)) },
            known.map { it.second },
        )
        val assignments = result.clusters.mapIndexed { k, c -> ClusterAssignment(c, result.seedOf[k]?.let { known[it].first }) }
        val (ids, update) = if (speakers != null) SpeakerPlanning.plan(assignments) else List(assignments.size) { null } to null
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
        return segIds to update
    }

    /** Sprecher zur Sample-Position: überdeckender Abschnitt, in Pausen der nächste bis [PAUSE_ASSIGN_MAX_MS]. */
    private fun speakerAtSample(segs: List<SampleRange>, starts: LongArray, segIds: Array<String?>, s: Long): String? {
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
        if (k >= 0 && s < segs[k].end) return segIds[k]
        val before = if (k >= 0) k else null
        val after = if (k + 1 <= segs.lastIndex) k + 1 else null
        val db = before?.let { s - segs[it].end } ?: Long.MAX_VALUE
        val da = after?.let { segs[it].start - s } ?: Long.MAX_VALUE
        val (cand, d) = if (db <= da) before to db else after to da
        return if (cand != null && d <= msToSamples(PAUSE_ASSIGN_MAX_MS)) segIds[cand] else null
    }

    private fun fraction(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    private class ProgressThrottle(private val cb: (Float) -> Unit) {
        private var last = -1f
        private var lastAt = 0L

        fun report(p: Float, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || p - last >= 0.01f || (p > last && now - lastAt > 250_000_000L)) {
                last = maxOf(last, p)
                lastAt = now
                cb(last.coerceIn(0f, 1f))
            }
        }
    }

    companion object {
        private const val PASS1_SHARE = 0.1f
        private const val EMBED_PASS_END = 0.25f
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
