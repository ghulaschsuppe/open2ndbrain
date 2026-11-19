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
 * Sprecher-Clustering einer Datei mit bekannten Profilen als feste Start-Cluster („Seeds“).
 *
 * Ablauf (Messungen und Schwellen: docs/dev/PHASE2-MESSUNGEN.md):
 * 1. Gierig je Abschnitt: bestes Zentrum unter Seeds (Schwelle [SEED_THRESHOLD]) und lokalen Clustern
 *    (Summen-Zentrum, Schwelle [DEFAULT_THRESHOLD]); keins passt → neuer lokaler Cluster.
 * 2. [refineRounds] Runden Neuzuordnung jedes Abschnitts zum ähnlichsten Zentrum (Seeds bleiben fest).
 * 3. Lokale Cluster mit Zentren-Cosinus ≥ [MERGE_THRESHOLD] zu einem Seed oder einem anderen lokalen Cluster
 *    werden zusammengelegt.
 *
 * Warum Seeds: In einer Datei können sich Abschnitte zweier Sprecher stärker ähneln als dem eigenen Zentrum
 * (gemessen bis 0,87 gegen fremd); die über Dateien gelernten Profile trennen sie dagegen sauber.
 */
object SpeakerClustering {
    /** Abschnitt ↔ lokales Summen-Zentrum (wie tools/model-eval/speaker_cluster.py). */
    const val DEFAULT_THRESHOLD = 0.6

    /** Abschnitt ↔ gespeichertes Profil (Plateau 0,6–0,75 gemessen, Mitte gewählt). */
    const val SEED_THRESHOLD = 0.68

    /** Zentrum ↔ Zentrum: gleicher Sprecher 0,92–0,98, verschiedene ≤ 0,81 → Mitte. */
    const val MERGE_THRESHOLD = 0.88

    data class Item(val embedding: FloatArray, val speechMs: Long)

    /**
     * @param labels Clusterindex je Item (Index in [clusters]).
     * @param seedOf je Cluster: Index des Seeds (bekanntes Profil) oder null für einen neuen Cluster.
     */
    data class Result(val labels: IntArray, val clusters: List<ClusterSummary>, val seedOf: List<Int?>)

    /** Ohne bekannte Profile. */
    fun cluster(items: List<Item>, threshold: Double = DEFAULT_THRESHOLD, refineRounds: Int = 2): Result =
        clusterSeeded(items, emptyList(), localThreshold = threshold, refineRounds = refineRounds)

    fun clusterSeeded(
        items: List<Item>,
        seeds: List<FloatArray>,
        seedThreshold: Double = SEED_THRESHOLD,
        localThreshold: Double = DEFAULT_THRESHOLD,
        mergeThreshold: Double = MERGE_THRESHOLD,
        refineRounds: Int = 2,
    ): Result {
        if (items.isEmpty()) return Result(IntArray(0), emptyList(), emptyList())
        val vecs = items.map { SpeakerMath.normalize(it.embedding) }
        val ns = seeds.size
        val seedC = seeds.map { SpeakerMath.normalize(it) }
        val sums = ArrayList<FloatArray>() // lokale Cluster: Index k ↔ Label ns + k
        val labels = IntArray(items.size)

        fun center(label: Int): FloatArray? =
            if (label < ns) seedC[label] else sums[label - ns].takeIf { s -> s.any { it != 0f } }?.let(SpeakerMath::normalize)

        // 1. gierig
        for ((i, v) in vecs.withIndex()) {
            var best = -1
            var bestSim = -2.0
            for (k in 0 until ns + sums.size) {
                val c = center(k) ?: continue
                val sim = SpeakerMath.cosine(c, v)
                val thr = if (k < ns) seedThreshold else localThreshold
                if (sim >= thr && sim > bestSim) {
                    bestSim = sim
                    best = k
                }
            }
            if (best < 0) {
                sums += v.copyOf()
                labels[i] = ns + sums.lastIndex
            } else {
                labels[i] = best
                if (best >= ns) sums[best - ns].let { c -> for (d in c.indices) c[d] += v[d] }
            }
        }

        fun recomputeSums() {
            for (c in sums) c.fill(0f)
            for ((i, v) in vecs.withIndex()) {
                if (labels[i] >= ns) sums[labels[i] - ns].let { c -> for (d in c.indices) c[d] += v[d] }
            }
        }

        // 2. Nachzuordnung zum ähnlichsten Zentrum
        repeat(refineRounds) {
            val centers = (0 until ns + sums.size).map { center(it) }
            var changed = false
            for ((i, v) in vecs.withIndex()) {
                var best = labels[i]
                var bestSim = centers[best]?.let { SpeakerMath.cosine(it, v) } ?: -2.0
                for ((k, c) in centers.withIndex()) {
                    if (c == null) continue
                    val sim = SpeakerMath.cosine(c, v)
                    if (sim > bestSim + 1e-9) {
                        bestSim = sim
                        best = k
                    }
                }
                if (best != labels[i]) {
                    labels[i] = best
                    changed = true
                }
            }
            recomputeSums()
            if (!changed) return@repeat
        }

        // 3. ähnliche lokale Cluster zusammenlegen (in Seeds oder ältere lokale Cluster)
        var merged = true
        while (merged) {
            merged = false
            val centers = (0 until ns + sums.size).map { k -> if (labels.contains(k)) center(k) else null }
            for (b in ns until centers.size) {
                val cb = centers[b] ?: continue
                var target = -1
                var bestSim = -2.0
                for (a in centers.indices) {
                    if (a == b || (a >= ns && a > b)) continue
                    val ca = centers[a] ?: continue
                    val sim = SpeakerMath.cosine(ca, cb)
                    if (sim > bestSim) {
                        bestSim = sim
                        target = a
                    }
                }
                if (target >= 0 && bestSim >= mergeThreshold) {
                    for (i in labels.indices) if (labels[i] == b) labels[i] = target
                    recomputeSums()
                    merged = true
                    break
                }
            }
        }

        // Ergebnis: belegte Labels neu nummerieren
        val used = labels.distinct().sorted()
        val remap = used.withIndex().associate { (newIdx, old) -> old to newIdx }
        val clusters = used.map { old ->
            val members = labels.indices.filter { labels[it] == old }
            val sum = FloatArray(vecs[0].size)
            members.forEach { m -> vecs[m].forEachIndexed { d, x -> sum[d] += x } }
            ClusterSummary(SpeakerMath.normalize(sum), members.size, members.sumOf { items[it].speechMs })
        }
        return Result(
            labels = IntArray(labels.size) { remap.getValue(labels[it]) },
            clusters = clusters,
            seedOf = used.map { if (it < ns) it else null },
        )
    }
}

/** Ein Cluster einer Datei und – falls erkannt – das bekannte Profil, zu dem er gehört. */
data class ClusterAssignment(val cluster: ClusterSummary, val profileId: String?)

/** Zugriff des Transcribers auf die Profile (nicht Teil des Vertrags). */
interface SpeakerMatcher {
    /** Bekannte Profile (ID, normiertes Embedding) als Start-Cluster für [SpeakerClustering.clusterSeeded]. */
    suspend fun knownProfiles(): List<Pair<String, FloatArray>>

    /**
     * Übernimmt das Ergebnis einer Datei: Cluster mit [ClusterAssignment.profileId] werden in dieses Profil
     * eingerechnet (Aliasse werden aufgelöst, falls inzwischen zusammengeführt); übrige werden neue Profile
     * „Sprecher N“, aber nur ab [minNewSpeechMs] Sprache (sonst null). Rückgabe: Profil-ID je Cluster.
     */
    suspend fun commit(
        assignments: List<ClusterAssignment>,
        seenAtEpochMs: Long,
        minNewSpeechMs: Long = MIN_NEW_PROFILE_SPEECH_MS,
    ): List<String?>

    companion object {
        const val MIN_NEW_PROFILE_SPEECH_MS = 3_000L
    }
}
