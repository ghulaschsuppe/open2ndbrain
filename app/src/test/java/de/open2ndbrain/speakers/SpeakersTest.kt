package de.open2ndbrain.speakers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetische Stimmabdrücke: je Sprecher ein Basisvektor, Abschnitte = Basis + Rauschen.
 * [similar]: Sprecher 1 liegt nahe bei Sprecher 0 (wie zwei ähnliche Stimmen in einer Aufnahme).
 */
internal class Voices(dim: Int = 64, count: Int = 4, seed: Int = 1, similar: Double? = null) {
    private val rnd = Random(seed)
    val bases: List<FloatArray> = List(count) { FloatArray(dim) { rnd.nextFloat() * 2 - 1 } }.let { b ->
        if (similar == null) b
        else b.toMutableList().also { it[1] = SpeakerMath.weightedMean(SpeakerMath.normalize(b[0]), similar, SpeakerMath.normalize(b[1]), 1 - similar) }
    }.map { SpeakerMath.normalize(it) }

    fun sample(speaker: Int, noise: Float = 0.6f): FloatArray {
        val b = bases[speaker]
        val n = 1f / sqrt(b.size.toFloat())
        return FloatArray(b.size) { b[it] + (rnd.nextFloat() * 2 - 1) * noise * n }
    }
}

class SpeakerClusteringTest {
    @Test
    fun mathBasics() {
        val a = floatArrayOf(3f, 4f)
        assertEquals(1.0, SpeakerMath.norm(SpeakerMath.normalize(a)), 1e-6)
        assertEquals(1.0, SpeakerMath.cosine(a, floatArrayOf(6f, 8f)), 1e-9)
        assertEquals(0.0, SpeakerMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(-1.0, SpeakerMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0.0)
        val m = SpeakerMath.weightedMean(floatArrayOf(1f, 0f), 3.0, floatArrayOf(0f, 1f), 1.0)
        assertTrue(m[0] > m[1])
        assertEquals(1.0, SpeakerMath.norm(m), 1e-6)
    }

    private val truth = listOf(0, 1, 0, 2, 2, 1, 0, 1, 2, 0, 0, 1, 2, 1, 0)

    private fun assertMatchesTruth(labels: IntArray) {
        for (i in truth.indices) for (j in truth.indices) assertEquals(truth[i] == truth[j], labels[i] == labels[j])
    }

    @Test
    fun threeSpeakersInterleavedGiveThreeClusters() {
        val v = Voices()
        val r = SpeakerClustering.cluster(truth.map { SpeakerClustering.Item(v.sample(it), 3_000) })
        assertEquals(3, r.clusters.size)
        assertMatchesTruth(r.labels)
        assertTrue(r.seedOf.all { it == null })
        assertEquals(truth.size * 3_000L, r.clusters.sumOf { it.speechMs })
        assertTrue(SpeakerClustering.cluster(emptyList()).clusters.isEmpty())
    }

    @Test
    fun seedsSeparateSimilarVoicesThatLocalClusteringWouldMerge() {
        // Zwei sehr ähnliche Stimmen (wie Sprecher 0/2 der zweiten Testaufnahme)
        val v = Voices(similar = 0.62, seed = 3)
        val items = truth.map { SpeakerClustering.Item(v.sample(it, noise = 0.9f), 2_000) }
        val withSeeds = SpeakerClustering.clusterSeeded(items, listOf(v.bases[0], v.bases[1], v.bases[2]))
        assertMatchesTruth(withSeeds.labels)
        // jeder Cluster gehört zum richtigen Seed
        for (i in truth.indices) assertEquals(truth[i], withSeeds.seedOf[withSeeds.labels[i]])
    }

    @Test
    fun unknownVoiceIsNotAbsorbedByKnownSeeds() {
        val v = Voices()
        val items = truth.map { SpeakerClustering.Item(v.sample(it), 2_000) }
        val r = SpeakerClustering.clusterSeeded(items, listOf(v.bases[0], v.bases[1])) // Sprecher 2 unbekannt
        assertMatchesTruth(r.labels)
        val clusterOf2 = r.labels[truth.indexOf(2)]
        assertNull(r.seedOf[clusterOf2])
        assertEquals(0, r.seedOf[r.labels[truth.indexOf(0)]])
    }

    @Test
    fun oversplitLocalClustersAreMergedIntoSeed() {
        val v = Voices()
        // kleine Seed-Schwelle-Verfehlung simulieren: Seed leicht verschoben, Abschnitte verrauscht
        val shifted = SpeakerMath.weightedMean(v.bases[0], 0.95, v.bases[3], 0.05)
        val items = List(8) { SpeakerClustering.Item(v.sample(0, noise = 1.2f), 2_000) }
        val r = SpeakerClustering.clusterSeeded(items, listOf(shifted), seedThreshold = 0.99)
        // Gierig passt nichts zum Seed (Schwelle 0,99) → lokale Cluster; deren Zentrum ≥ 0,88 → in den Seed gelegt
        assertEquals(1, r.clusters.size)
        assertEquals(0, r.seedOf.single())
    }
}

class FileSpeakerRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val voices = Voices()

    private fun repo(dir: File = tmp.root) = FileSpeakerRepository(dir, now = { 42 })

    /** Eine „Datei“: Abschnitte der Sprecher (je 2 s), voller Ablauf Seeds → Clustering → Plan → apply. */
    private suspend fun FileSpeakerRepository.file(
        speakers: List<Int>,
        seen: Long,
        segMs: Long = 2_000,
        name: String = "f$seen.ogg",
        apply: Boolean = true,
    ): Map<Int, String?> {
        val known = knownProfiles()
        val items = speakers.map { SpeakerClustering.Item(voices.sample(it), segMs) }
        val r = SpeakerClustering.clusterSeeded(items, known.map { it.second })
        val (ids, update) = SpeakerPlanning.plan(r.clusters.mapIndexed { k, c -> ClusterAssignment(c, r.seedOf[k]?.let { known[it].first }) })
        if (apply) apply(update, name, seen)
        return speakers.withIndex().groupBy({ it.value }, { ids[r.labels[it.index]] }).mapValues { (_, l) -> l.first() }
    }

    @Test
    fun nothingIsPersistedUntilApplied() = runBlocking {
        val r = repo()
        val ids = r.file(listOf(0, 0, 1, 1), seen = 1_000, apply = false) // z. B. Abbruch vor dem Schreiben
        assertNotNull(ids[0])
        assertTrue(r.observeSpeakers().first().isEmpty())
        assertFalse(File(tmp.root, "speakers.json").exists())
    }

    @Test
    fun reTranscribingSameFileIsIdempotent() = runBlocking {
        val r = repo()
        val first = r.file(listOf(0, 0, 1, 1, 0), seen = 1_000, name = "a.ogg")
        val before = r.observeSpeakers().first()
        repeat(3) { assertEquals(first, r.file(listOf(0, 0, 1, 1, 0), seen = 1_000, name = "a.ogg")) }
        val after = r.observeSpeakers().first()
        assertEquals(before.map { it.sampleCount to it.totalSpeechMs }, after.map { it.sampleCount to it.totalSpeechMs })
        // Embedding wird nicht erneut eingerechnet
        assertEquals(before.map { it.embedding }, after.map { it.embedding })
        // andere Datei zählt dagegen dazu
        r.file(listOf(0, 0), seen = 2_000, name = "b.ogg")
        assertEquals(5, r.resolve(first[0]!!)!!.sampleCount)
        // nach Merge bleibt Neu-Transkribieren idempotent (Beitrag folgt dem Alias)
        r.merge(fromId = first[1]!!, intoId = first[0]!!)
        val merged = r.resolve(first[0]!!)!!
        r.file(listOf(0, 0, 0), seen = 1_000, name = "a.ogg") // nur noch eine Stimme erkannt
        val again = r.resolve(first[0]!!)!!
        assertEquals(merged.sampleCount - 5 + 3, again.sampleCount)
    }

    @Test
    fun createsThenRecognizesAcrossFiles() = runBlocking {
        val r = repo()
        val first = r.file(listOf(0, 1, 0, 1, 0, 1, 2), seen = 1_000) // Sprecher 2: nur 2 s → kein Profil
        assertNotNull(first[0])
        assertNotNull(first[1])
        assertNotEquals(first[0], first[1])
        assertNull(first[2])
        val second = r.file(listOf(1, 0, 1, 0, 0, 1), seen = 5_000)
        assertEquals(first[0], second[0])
        assertEquals(first[1], second[1])
        val all = r.observeSpeakers().first()
        assertEquals(listOf(1, 2), all.map { it.number })
        val p = all.first { it.id == first[0] }
        assertEquals(6, p.sampleCount)
        assertEquals(12_000L, p.totalSpeechMs)
        assertEquals(1_000L, p.firstSeenEpochMs)
        assertEquals(5_000L, p.lastSeenEpochMs)
        assertEquals(1.0, SpeakerMath.norm(p.embedding.toFloatArray()), 1e-5)
        // kurzer Auftritt eines bekannten Sprechers wird zugeordnet (unter 3 s, aber bekannt)
        assertEquals(first[0], r.file(listOf(0), seen = 6_000)[0])
        // neuer Sprecher neben bekannten → neues Profil
        val third = r.file(listOf(3, 0, 3, 3, 0), seen = 7_000)
        assertEquals(first[0], third[0])
        assertNotNull(third[3])
        assertEquals(3, r.observeSpeakers().first().size)
    }

    @Test
    fun applyFollowsAliasAndSkipsDeletedProfile() = runBlocking {
        val r = repo()
        val ids = r.file(listOf(0, 0, 1, 1), seen = 1_000)
        val known = r.knownProfiles()
        r.merge(fromId = ids[1]!!, intoId = ids[0]!!)
        // Seed-Zuordnung zeigt noch auf die alte ID → landet im zusammengeführten Profil
        val c = ClusterSummary(known.first { it.first == ids[1] }.second, 2, 4_000)
        val countBefore = r.resolve(ids[0]!!)!!.sampleCount
        r.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry(ids[1]!!, false, c))), "x.ogg", 2_000)
        assertEquals(countBefore + 2, r.resolve(ids[0]!!)!!.sampleCount)
        r.delete(ids[0]!!)
        // gelöschtes Profil wird nicht wieder angelegt
        r.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry(ids[0]!!, false, c))), "y.ogg", 3_000)
        assertTrue(r.observeSpeakers().first().isEmpty())
    }

    @Test
    fun renameSetMeMergeAliasDeleteAndPersistence() = runBlocking {
        val r = repo()
        val ids = r.file(listOf(0, 0, 1, 1, 2, 2), seen = 1_000)
        val (a, b, c) = listOf(ids[0]!!, ids[1]!!, ids[2]!!)
        r.rename(a, "  Anna ")
        r.rename(b, "   ")
        r.setMe(b)
        r.setMe(c)
        var all = r.observeSpeakers().first()
        assertEquals("Anna", all.first { it.id == a }.name)
        assertNull(all.first { it.id == b }.name)
        assertEquals(listOf(c), all.filter { it.isMe }.map { it.id })

        r.merge(fromId = c, intoId = a)
        all = r.observeSpeakers().first()
        assertEquals(2, all.size)
        val merged = all.first { it.id == a }
        assertEquals(listOf(c), merged.aliases)
        assertTrue(merged.isMe)
        assertEquals("Anna", merged.name)
        assertEquals(4, merged.sampleCount)
        assertEquals(a, r.resolve(c)?.id) // alte ID im Transkript → zusammengeführtes Profil
        assertEquals(a, r.resolve(a)?.id)

        // Persistenz: neue Instanz liest dieselben Daten
        val r2 = repo()
        assertEquals(all, r2.observeSpeakers().first())
        r2.delete(b)
        assertNull(r2.resolve(b))
        // Nummern werden nach dem Löschen nicht wiederverwendet
        val d = r2.file(listOf(3, 3), seen = 2_000)[3]!!
        assertEquals(4, r2.resolve(d)!!.number)
        assertFalse(File(tmp.root, "speakers.json.tmp").exists())
    }

    @Test
    fun brokenFileIsKeptAsideNotOverwritten() = runBlocking {
        File(tmp.root, "speakers.json").writeText("{kaputt")
        val r = repo()
        assertTrue(r.observeSpeakers().first().isEmpty())
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("speakers.json.broken-") })
    }
}
