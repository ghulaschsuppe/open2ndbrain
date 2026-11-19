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
import kotlin.random.Random

/** Synthetische Stimmabdrücke: je Sprecher ein Basisvektor, Abschnitte = Basis + Rauschen. */
internal class Voices(dim: Int = 64, count: Int = 4, seed: Int = 1) {
    private val rnd = Random(seed)
    val bases = List(count) { FloatArray(dim) { rnd.nextFloat() * 2 - 1 } }

    fun sample(speaker: Int, noise: Float = 0.6f): FloatArray {
        val b = bases[speaker]
        val n = SpeakerMath.norm(b).toFloat() / kotlin.math.sqrt(b.size.toFloat())
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

    @Test
    fun threeSpeakersInterleavedGiveThreeClusters() {
        val v = Voices()
        val truth = listOf(0, 1, 0, 2, 2, 1, 0, 1, 2, 0, 0, 1, 2, 1, 0)
        val items = truth.map { SpeakerClustering.Item(v.sample(it), 3_000) }
        val r = SpeakerClustering.cluster(items)
        assertEquals(3, r.clusters.size)
        // gleiche Wahrheit ↔ gleiches Label
        for (i in truth.indices) for (j in truth.indices) {
            assertEquals(truth[i] == truth[j], r.labels[i] == r.labels[j])
        }
        assertEquals(truth.size, r.clusters.sumOf { it.count })
        assertEquals(truth.size * 3_000L, r.clusters.sumOf { it.speechMs })
        assertTrue(SpeakerClustering.cluster(emptyList()).clusters.isEmpty())
    }
}

class FileSpeakerRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val voices = Voices()

    private fun repo(dir: File = tmp.root) = FileSpeakerRepository(dir, now = { 42 })

    private fun cluster(speaker: Int, count: Int = 5, ms: Long = 10_000): ClusterSummary {
        val sum = FloatArray(64)
        repeat(count) { voices.sample(speaker).forEachIndexed { i, x -> sum[i] += x } }
        return ClusterSummary(SpeakerMath.normalize(sum), count, ms)
    }

    @Test
    fun createsThenRecognizesAcrossFiles() = runBlocking {
        val r = repo()
        val first = r.matchOrCreate(listOf(cluster(0), cluster(1), cluster(2, ms = 1_000)), seenAtEpochMs = 1_000)
        assertNotNull(first[0])
        assertNotNull(first[1])
        assertNotEquals(first[0], first[1])
        assertNull(first[2]) // zu wenig Sprache für ein neues Profil
        val second = r.matchOrCreate(listOf(cluster(1), cluster(0)), seenAtEpochMs = 5_000)
        assertEquals(listOf(first[1], first[0]), second)
        val all = r.observeSpeakers().first()
        assertEquals(listOf(1, 2), all.map { it.number })
        val p = all.first { it.id == first[0] }
        assertEquals(10, p.sampleCount)
        assertEquals(20_000L, p.totalSpeechMs)
        assertEquals(1_000L, p.firstSeenEpochMs)
        assertEquals(5_000L, p.lastSeenEpochMs)
        assertEquals(1.0, SpeakerMath.norm(p.embedding.toFloatArray()), 1e-5)
        // kurzer Cluster eines bekannten Sprechers wird zugeordnet
        assertEquals(first[0], r.matchOrCreate(listOf(cluster(0, count = 1, ms = 1_200)), 6_000).single())
    }

    @Test
    fun renameSetMeMergeAliasDeleteAndPersistence() = runBlocking {
        val r = repo()
        val (a, b, c) = r.matchOrCreate(listOf(cluster(0), cluster(1), cluster(2)), 1_000).map { it!! }
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
        assertEquals(10, merged.sampleCount)
        assertEquals(a, r.resolve(c)?.id) // alte ID im Transkript → zusammengeführtes Profil
        assertEquals(a, r.resolve(a)?.id)

        // Persistenz: neue Instanz liest dieselben Daten
        val r2 = repo()
        assertEquals(all, r2.observeSpeakers().first())
        r2.delete(b)
        assertNull(r2.resolve(b))
        // Nummern werden nach dem Löschen nicht wiederverwendet
        val d = r2.matchOrCreate(listOf(cluster(3)), 2_000).single()!!
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
