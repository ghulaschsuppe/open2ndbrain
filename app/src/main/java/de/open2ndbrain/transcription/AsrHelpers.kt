package de.open2ndbrain.transcription

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** ASR-Einheit für Modelle ohne Token-Zeitstempel: [speech] = Sprachbereich, [padded] = mit Rand. */
internal data class AsrUnit(val speech: SampleRange, val padded: SampleRange, val speakerId: String?)

/**
 * Fasst feine VAD-Abschnitte zu ASR-Einheiten zusammen: gleicher Sprecher (unbekannt passt zu allem),
 * Lücke ≤ [maxGap], Länge ≤ [maxLen] (Whisper verträgt höchstens 30 s, Canary ist bei ~15 s gut).
 * Rand wie bei [BlockPlanner] höchstens bis zur Lückenmitte.
 */
internal object UnitPlanner {
    fun plan(
        segments: List<SampleRange>,
        speakerIds: List<String?>,
        total: Long,
        maxGap: Long = msToSamples(2_000),
        maxLen: Long = msToSamples(15_000),
        margin: Long = msToSamples(200),
    ): List<AsrUnit> {
        val order = segments.indices.sortedBy { segments[it].start }
        val merged = ArrayList<Pair<SampleRange, String?>>()
        for (i in order) {
            val s = segments[i]
            val id = speakerIds.getOrNull(i)
            val last = merged.lastOrNull()
            val sameSpeaker = last != null && (last.second == null || id == null || last.second == id)
            if (last != null && sameSpeaker && s.start - last.first.end <= maxGap && s.end - last.first.start <= maxLen) {
                merged[merged.lastIndex] = SampleRange(last.first.start, maxOf(last.first.end, s.end)) to (last.second ?: id)
            } else {
                merged += s to id
            }
        }
        return merged.mapIndexed { i, (b, id) ->
            val prevEnd = if (i > 0) merged[i - 1].first.end else null
            val nextStart = if (i < merged.lastIndex) merged[i + 1].first.start else null
            val left = prevEnd?.let { minOf(margin, ((b.start - it) / 2).coerceAtLeast(0)) } ?: margin
            val right = nextStart?.let { minOf(margin, ((it - b.end) / 2).coerceAtLeast(0)) } ?: margin
            AsrUnit(b, SampleRange((b.start - left).coerceAtLeast(0), (b.end + right).coerceAtMost(maxOf(total, b.end))), id)
        }
    }

    /**
     * Wörter eines Einheitentexts mit geschätzten Zeiten: über den Sprachbereich proportional zur Zeichenzahl
     * verteilt (für Satzaufteilung, Abspielen ab Satz und Sprecherzuordnung genau genug).
     */
    fun wordTokens(text: String, speechStart: Long, speechEnd: Long): List<AsrToken> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val startMs = samplesToMs(speechStart)
        val spanMs = (samplesToMs(speechEnd) - startMs).coerceAtLeast(words.size.toLong())
        val weights = words.map { it.length + 1 }
        val sum = weights.sum().toDouble()
        var acc = 0
        return words.mapIndexed { i, w ->
            val s = startMs + (spanMs * acc / sum).toLong()
            acc += weights[i]
            val e = startMs + (spanMs * acc / sum).toLong()
            AsrToken(" $w", s, (e - s).coerceAtLeast(1))
        }
    }
}

/**
 * Lautstärkenormalisierung vor der Erkennung: RMS auf [TARGET_DBFS], höchstens [MAX_GAIN]-fach, nie leiser. Die
 * Verstärkung wird so begrenzt, dass höchstens ~0,1 % der Samples übersteuern; diese werden auf ±1 begrenzt.
 */
internal object Loudness {
    const val TARGET_DBFS = -22.0
    const val MAX_GAIN = 30.0
    private val TARGET_RMS = 10.0.pow(TARGET_DBFS / 20)

    fun gainFor(samples: FloatArray): Double {
        if (samples.isEmpty()) return 1.0
        var sum = 0.0
        for (x in samples) sum += x.toDouble() * x
        val rms = sqrt(sum / samples.size)
        if (rms < 1e-7) return 1.0
        // 99,9-%-Quantil des Betrags über ein Histogramm (ohne Sortieren)
        val bins = IntArray(1024)
        for (x in samples) bins[(abs(x) * 1023).toInt().coerceIn(0, 1023)]++
        val limit = samples.size - samples.size / 1000
        var cum = 0
        var q = 1.0
        for (b in bins.indices) {
            cum += bins[b]
            if (cum >= limit) {
                q = (b + 1) / 1023.0
                break
            }
        }
        return minOf(MAX_GAIN, TARGET_RMS / rms, 0.99 / q).coerceAtLeast(1.0)
    }

    /** Normierte Kopie (bei Verstärkung 1 das Original). */
    fun normalize(samples: FloatArray): FloatArray {
        val g = gainFor(samples).toFloat()
        if (g == 1f) return samples
        return FloatArray(samples.size) { (samples[it] * g).coerceIn(-1f, 1f) }
    }
}

/** Bereinigung der erkannten Texte. */
internal object TextCleanup {
    private val SPACE_BEFORE_PUNCT = Regex("\\s+([,.;:!?…)\\]»“])")
    private val SPACE_AFTER_OPEN = Regex("([(\\[«„])\\s+")
    private val MULTI_SPACE = Regex("\\s{2,}")

    /**
     * Typische Whisper-Halluzinationen bei (fast) stillen Abschnitten (Trainingsdaten aus Untertiteln). Eine Äußerung,
     * die nur daraus besteht, wird verworfen.
     */
    private val HALLUCINATIONS = listOf(
        Regex("untertitel(ung)?( im auftrag)? (des|der|von) (zdf|ard|wdr|br|ndr|swr|mdr|hr|funk)\\b.*"),
        Regex("untertitel(ung)?( der)? amara\\.?org[- ]?community.*"),
        Regex("untertitel (von|im auftrag von) .{0,40}"),
        Regex("(vielen )?dank (fürs|für's|für das|für ihr|für eure) (zuschauen|zuhören|aufmerksamkeit)[ .!,]*(und )?(bis zum nächsten mal)?[ .!]*"),
        Regex("(und )?bis zum nächsten mal[ .!,]*(tschüss|ciao)?"),
        Regex("das war'?s (für heute|von mir)"),
        Regex("swr \\d{4}.*|copyright.{0,40}|© .{0,40}"),
        Regex("abonnier(t|en sie) (den kanal|meinen kanal|uns).*"),
    )

    fun clean(text: String): String = text
        .replace(SPACE_BEFORE_PUNCT, "$1")
        .replace(SPACE_AFTER_OPEN, "$1")
        .replace(MULTI_SPACE, " ")
        .trim()

    fun isHallucination(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', '!', ' ')
        return t.isNotEmpty() && HALLUCINATIONS.any { it.matches(t) }
    }
}
