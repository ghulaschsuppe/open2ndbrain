package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.core.Utterance

/** Halboffener Bereich [start, end) in Samples (16 kHz) ab Dateibeginn. */
internal data class SampleRange(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

/** Erkanntes Token in Dateizeit. [text] beginnt mit Leerzeichen, wenn ein neues Wort anfängt. */
internal data class AsrToken(val text: String, val startMs: Long, val durationMs: Long)

internal const val SAMPLE_RATE = 16_000

internal fun samplesToMs(s: Long): Long = s * 1000 / SAMPLE_RATE
internal fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000

/** Fasst VAD-Abschnitte zu ASR-Blöcken zusammen (siehe docs/dev/PHASE2-MESSUNGEN.md). */
internal object BlockPlanner {
    /**
     * Abschnitte mit Lücke ≤ [maxGap] werden verbunden, solange der Block ≤ [maxLen] bleibt
     * (Originalaudio inkl. der Pausen). Danach bekommt jeder Block [margin] Rand, höchstens bis zur
     * Mitte der Lücke zum Nachbarblock (keine doppelt erkannten Wörter) und innerhalb [0, total].
     */
    fun plan(
        segments: List<SampleRange>,
        total: Long,
        maxGap: Long = msToSamples(2_000),
        maxLen: Long = msToSamples(20_000),
        margin: Long = msToSamples(200),
    ): List<SampleRange> {
        val merged = ArrayList<SampleRange>()
        for (s in segments.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && s.start - last.end <= maxGap && s.end - last.start <= maxLen) {
                merged[merged.lastIndex] = SampleRange(last.start, maxOf(last.end, s.end))
            } else {
                merged += s
            }
        }
        return merged.mapIndexed { i, b ->
            val prevEnd = if (i > 0) merged[i - 1].end else null
            val nextStart = if (i < merged.lastIndex) merged[i + 1].start else null
            val left = prevEnd?.let { minOf(margin, ((b.start - it) / 2).coerceAtLeast(0)) } ?: margin
            val right = nextStart?.let { minOf(margin, ((it - b.end) / 2).coerceAtLeast(0)) } ?: margin
            SampleRange((b.start - left).coerceAtLeast(0), (b.end + right).coerceAtMost(maxOf(total, b.end)))
        }
    }
}

/**
 * Sammelt aus einem Sample-Strom die Samples bestimmter Bereiche (dürfen sich überlappen) und liefert
 * jeden Bereich, sobald der Strom über sein Ende hinaus ist. So muss nie die ganze Datei im RAM liegen.
 */
internal class RangeCollector<T>(
    items: List<Pair<SampleRange, T>>,
    private val onComplete: (T, SampleRange, FloatArray) -> Unit,
) {
    private class Pending<T>(val range: SampleRange, val tag: T) {
        val data = FloatArray(range.length.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    private val waiting = ArrayDeque(items.filter { it.first.length > 0 }.sortedBy { it.first.start })
    private val active = ArrayList<Pending<T>>()
    private var pos = 0L

    /** Gibt es noch offene Bereiche? (Sonst kann der Strom vorzeitig enden.) */
    val done: Boolean get() = waiting.isEmpty() && active.isEmpty()

    fun accept(samples: FloatArray, n: Int) {
        val chunkEnd = pos + n
        while (waiting.isNotEmpty() && waiting.first().first.start < chunkEnd) {
            val (r, t) = waiting.removeFirst()
            active += Pending(r, t)
        }
        for (p in active) {
            val from = maxOf(p.range.start, pos)
            val to = minOf(p.range.end, chunkEnd)
            if (to > from) {
                System.arraycopy(samples, (from - pos).toInt(), p.data, (from - p.range.start).toInt(), (to - from).toInt())
            }
        }
        pos = chunkEnd
        val it = active.iterator()
        val finished = ArrayList<Pending<T>>()
        while (it.hasNext()) {
            val p = it.next()
            if (p.range.end <= pos) {
                finished += p
                it.remove()
            }
        }
        finished.sortedBy { it.range.end }.forEach { onComplete(it.tag, it.range, it.data) }
    }

    /** Strom zu Ende: offene Bereiche gekürzt ausliefern (z. B. kürzere Datei als erwartet). */
    fun finish() {
        for (p in active.sortedBy { it.range.end }) {
            val len = (pos - p.range.start).coerceIn(0, p.data.size.toLong()).toInt()
            if (len > 0) onComplete(p.tag, SampleRange(p.range.start, p.range.start + len), p.data.copyOf(len))
        }
        active.clear()
        waiting.clear()
    }
}

/**
 * Dateizeit → Wanduhrzeit über die Sidecar-Segmente. Zwischen/außerhalb von Segmenten wird vom
 * nächstgelegenen Segment aus linear fortgesetzt; ohne Segmente: Stundenbeginn + Dateizeit.
 */
internal class FileTimeMapper(segments: List<RecordingSidecar.Segment>, private val fallbackStartEpochMs: Long) {
    private val segs = segments.filter { it.durationMs > 0 }.sortedBy { it.fileOffsetMs }

    fun epochOf(fileMs: Long): Long {
        if (segs.isEmpty()) return fallbackStartEpochMs + fileMs
        // binäre Suche: letztes Segment mit fileOffset ≤ fileMs
        var lo = 0
        var hi = segs.lastIndex
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segs[mid].fileOffsetMs <= fileMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        val s = if (idx < 0) segs.first() else segs[idx]
        return s.startEpochMs + (fileMs - s.fileOffsetMs)
    }

    /** Wanduhr-Sprung zwischen zwei Dateizeiten (0 = lückenlos). */
    fun discontinuityMs(fromFileMs: Long, toFileMs: Long): Long =
        (epochOf(toFileMs) - epochOf(fromFileMs)) - (toFileMs - fromFileMs)
}

/** Bildet aus Tokens Äußerungen: Satzende, Sprecherwechsel, lange Pause, Wanduhr-Sprung, max. Länge. */
internal object UtteranceBuilder {
    private val SENTENCE_END = charArrayOf('.', '?', '!', '…')

    /** Häufige Abkürzungen, nach denen kein Satz endet (klein geschrieben, ohne Schlusspunkt). */
    private val ABBREVIATIONS = setOf(
        "z.b", "d.h", "u.a", "o.ä", "u.u", "v.a", "z.t", "bzw", "ca", "dr", "nr", "vgl", "ggf", "evtl", "inkl",
        "hr", "fr", "prof", "st", "abs", "mio", "mrd", "bspw", "usw", "etc",
    )

    /** Satzende nach [word]? Nicht bei Abkürzungen und nicht, wenn das nächste Wort klein beginnt. */
    private fun endsSentence(word: String, next: AsrToken): Boolean {
        val w = word.trim()
        if (w.isEmpty() || w.last() !in SENTENCE_END) return false
        if (w.last() == '.') {
            val stem = w.trimEnd('.').lowercase()
            if (stem in ABBREVIATIONS || (stem.length <= 3 && '.' in stem)) return false
        }
        val first = next.text.trim().firstOrNull() ?: return true
        return !first.isLowerCase()
    }

    fun build(
        tokens: List<AsrToken>,
        mapper: FileTimeMapper,
        speakerAt: (Long) -> String? = { null },
        maxUtteranceMs: Long = 30_000,
        maxPauseMs: Long = 2_000,
        maxWallJumpMs: Long = 1_000,
    ): List<Utterance> {
        val out = ArrayList<Utterance>()
        val cur = ArrayList<AsrToken>()
        var curSpeaker: String? = null
        var lastWord = StringBuilder()

        fun flush() {
            if (cur.isEmpty()) return
            val text = cur.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
            val first = cur.first()
            val last = cur.last()
            if (text.isNotEmpty()) {
                out += Utterance(
                    fileOffsetMs = first.startMs,
                    durationMs = (last.startMs + last.durationMs - first.startMs).coerceAtLeast(1),
                    startEpochMs = mapper.epochOf(first.startMs),
                    text = text,
                    speakerId = curSpeaker,
                )
            }
            cur.clear()
            curSpeaker = null
            lastWord = StringBuilder()
        }

        for (tok in tokens.sortedBy { it.startMs }) {
            val wordStart = tok.text.firstOrNull()?.isWhitespace() == true
            val spk = speakerAt(tok.startMs + tok.durationMs / 2)
            if (cur.isNotEmpty()) {
                val last = cur.last()
                val gap = tok.startMs - (last.startMs + last.durationMs)
                val split = gap > maxPauseMs ||
                    kotlin.math.abs(mapper.discontinuityMs(last.startMs, tok.startMs)) > maxWallJumpMs ||
                    (wordStart && endsSentence(lastWord.toString(), tok)) ||
                    (wordStart && spk != null && curSpeaker != null && spk != curSpeaker) ||
                    (wordStart && tok.startMs - cur.first().startMs >= maxUtteranceMs)
                if (split) flush()
            }
            if (cur.isEmpty()) curSpeaker = spk else if (curSpeaker == null) curSpeaker = spk
            cur += tok
            if (wordStart) lastWord = StringBuilder()
            lastWord.append(tok.text)
        }
        flush()
        return out
    }
}
