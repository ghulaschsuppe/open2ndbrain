package de.open2ndbrain.speakers

import kotlin.math.sqrt

/** Vektorhilfen für Stimmabdrücke (Embeddings). */
object SpeakerMath {
    fun norm(v: FloatArray): Double {
        var s = 0.0
        for (x in v) s += x.toDouble() * x
        return sqrt(s)
    }

    /** Auf Länge 1 normiert (Nullvektor bleibt unverändert). */
    fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        if (n == 0.0) return v.copyOf()
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        val n = norm(a) * norm(b)
        return if (n == 0.0) -1.0 else dot / n
    }

    /** Normierter gewichteter Mittelwert zweier (normierter) Vektoren. */
    fun weightedMean(a: FloatArray, wa: Double, b: FloatArray, wb: Double): FloatArray {
        if (a.size != b.size) return normalize(b)
        val r = FloatArray(a.size) { (a[it] * wa + b[it] * wb).toFloat() }
        return normalize(r)
    }
}

/** Ein lokaler Sprecher-Cluster einer Datei, der global abgeglichen wird. */
data class ClusterSummary(
    /** Normiertes Zentrum. */
    val centroid: FloatArray,
    /** Anzahl Abschnitte mit Embedding. */
    val count: Int,
    val speechMs: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ClusterSummary && count == other.count && speechMs == other.speechMs && centroid.contentEquals(other.centroid)

    override fun hashCode(): Int = 31 * (31 * centroid.contentHashCode() + count) + speechMs.hashCode()
}

/**
 * Lokales Clustering innerhalb einer Datei: inkrementell per Cosinus (wie in tools/model-eval/speaker_cluster.py),
 * danach [refineRounds] Runden Neuzuordnung zum nächsten Zentrum (robuster gegen Reihenfolge-Effekte).
 */
object SpeakerClustering {
    const val DEFAULT_THRESHOLD = 0.6

    data class Item(val embedding: FloatArray, val speechMs: Long)

    data class Result(val labels: IntArray, val clusters: List<ClusterSummary>)

    fun cluster(items: List<Item>, threshold: Double = DEFAULT_THRESHOLD, refineRounds: Int = 2): Result {
        if (items.isEmpty()) return Result(IntArray(0), emptyList())
        val vecs = items.map { SpeakerMath.normalize(it.embedding) }
        val sums = ArrayList<FloatArray>()
        val labels = IntArray(items.size)
        for ((i, v) in vecs.withIndex()) {
            var best = -1
            var bestSim = -2.0
            for ((k, c) in sums.withIndex()) {
                val s = SpeakerMath.cosine(c, v)
                if (s > bestSim) {
                    bestSim = s
                    best = k
                }
            }
            if (best >= 0 && bestSim >= threshold) {
                val c = sums[best]
                for (d in c.indices) c[d] += v[d]
                labels[i] = best
            } else {
                sums += v.copyOf()
                labels[i] = sums.lastIndex
            }
        }
        repeat(refineRounds) {
            val centers = sums.map { SpeakerMath.normalize(it) }
            var changed = false
            for ((i, v) in vecs.withIndex()) {
                var best = labels[i]
                var bestSim = SpeakerMath.cosine(centers[best], v)
                for ((k, c) in centers.withIndex()) {
                    val s = SpeakerMath.cosine(c, v)
                    if (s > bestSim + 1e-9) {
                        bestSim = s
                        best = k
                    }
                }
                if (best != labels[i]) {
                    labels[i] = best
                    changed = true
                }
            }
            for (c in sums) c.fill(0f)
            for ((i, v) in vecs.withIndex()) {
                val c = sums[labels[i]]
                for (d in c.indices) c[d] += v[d]
            }
            if (!changed) return@repeat
        }
        // Leere Cluster entfernen und neu nummerieren
        val used = labels.distinct().sorted()
        val remap = used.withIndex().associate { (newIdx, old) -> old to newIdx }
        val newLabels = IntArray(labels.size) { remap.getValue(labels[it]) }
        val clusters = used.map { old ->
            val members = labels.indices.filter { labels[it] == old }
            ClusterSummary(
                centroid = SpeakerMath.normalize(sums[old]),
                count = members.size,
                speechMs = members.sumOf { items[it].speechMs },
            )
        }
        return Result(newLabels, clusters)
    }
}

/** Abgleich lokaler Cluster mit den gespeicherten Profilen (vom Transcriber genutzt, nicht Teil des Vertrags). */
interface SpeakerMatcher {
    /**
     * Ordnet jedem Cluster eine Profil-ID zu: bestes Profil mit Cosinus ≥ [threshold] (Profil wird mit dem
     * Cluster nachgeführt), sonst neues Profil „Sprecher N“ – aber nur bei mindestens [minNewSpeechMs]
     * Sprache (sonst null, damit Störgeräusche keine Profile erzeugen).
     */
    suspend fun matchOrCreate(
        clusters: List<ClusterSummary>,
        seenAtEpochMs: Long,
        threshold: Double = SpeakerClustering.DEFAULT_THRESHOLD,
        minNewSpeechMs: Long = MIN_NEW_PROFILE_SPEECH_MS,
    ): List<String?>

    companion object {
        const val MIN_NEW_PROFILE_SPEECH_MS = 3_000L
    }
}
