package de.open2ndbrain.speakers

import de.open2ndbrain.core.SpeakerProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class SpeakerStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24L * 3_600_000
    private var clock = 1_000L * day

    private fun repo() = FileSpeakerRepository(tmp.root, now = { clock })

    private fun profile(id: String, name: String? = null, me: Boolean = false, speechMs: Long = 10_000, lastSeen: Long = clock) =
        SpeakerProfile(id, name, number = 1, isMe = me, embedding = listOf(1f, 0f), sampleCount = 3,
            totalSpeechMs = speechMs, firstSeenEpochMs = lastSeen, lastSeenEpochMs = lastSeen)

    private fun cluster(seed: Int, count: Int = 3, ms: Long = 6_000): ClusterSummary {
        val r = Random(seed)
        return ClusterSummary(SpeakerMath.normalize(FloatArray(16) { r.nextFloat() - 0.5f }), count, ms)
    }

    private fun newEntry(id: String, c: ClusterSummary) = SpeakerUpdate.Entry(id, isNew = true, cluster = c)

    @Test
    fun seedSelectionAndLimit() {
        val now = clock
        val all = listOf(
            profile("named", name = "Anna", speechMs = 1_000, lastSeen = now - 400 * day),
            profile("me", me = true, speechMs = 1_000, lastSeen = now - 400 * day),
            profile("long", speechMs = 120_000, lastSeen = now - 400 * day),
            profile("recent", speechMs = 2_000, lastSeen = now - 5 * day),
            profile("old-short", speechMs = 2_000, lastSeen = now - 40 * day),
        )
        val ids = FileSpeakerRepository.selectSeeds(all, now).map { it.id }
        assertEquals(listOf("named", "me", "long", "recent"), ids)
        val many = (0 until 500).map { profile("p$it", speechMs = 60_000L + it, lastSeen = now) } + all[0]
        val seeds = FileSpeakerRepository.selectSeeds(many, now)
        assertEquals(FileSpeakerRepository.MAX_SEEDS, seeds.size)
        assertEquals("named", seeds.first().id) // benannte zuerst
        assertEquals("p499", seeds[1].id) // dann nach Sprechzeit
    }

    @Test
    fun cleanupRemovesOnlyOldShortUnnamed() {
        val now = clock
        val all = listOf(
            profile("old-short", speechMs = 30_000, lastSeen = now - 100 * day),
            profile("old-long", speechMs = 90_000, lastSeen = now - 100 * day),
            profile("old-named", name = "Bea", speechMs = 1_000, lastSeen = now - 100 * day),
            profile("old-me", me = true, speechMs = 1_000, lastSeen = now - 100 * day),
            profile("recent-short", speechMs = 1_000, lastSeen = now - 10 * day),
            profile("touched", speechMs = 1_000, lastSeen = now - 100 * day),
        )
        val kept = FileSpeakerRepository.cleanup(all, now, keep = setOf("touched")).map { it.id }
        assertEquals(listOf("old-long", "old-named", "old-me", "recent-short", "touched"), kept)
    }

    @Test
    fun cleanupRunsAtMostDailyDuringApply() = runBlocking {
        val r = repo()
        r.apply(SpeakerUpdate(listOf(newEntry("a", cluster(1)))), "a.ogg", seenAtEpochMs = clock - 100 * day)
        assertEquals(1, r.observeSpeakers().first().size) // gerade berührt → bleibt
        clock += 2 * day
        r.apply(SpeakerUpdate(listOf(newEntry("b", cluster(2)))), "b.ogg", seenAtEpochMs = clock)
        assertEquals(listOf("b"), r.observeSpeakers().first().map { it.id }) // „a“: alt, kurz, unbenannt
        assertNull(r.resolve("a"))
    }

    @Test
    fun contributionsLiveInSeparateFileAndUiEditsDoNotTouchThem() = runBlocking {
        val r = repo()
        r.apply(SpeakerUpdate(listOf(newEntry("a", cluster(1)))), "a.ogg", clock)
        val contribs = File(tmp.root, "speaker-contributions.json")
        val profiles = File(tmp.root, "speakers.json")
        assertTrue(contribs.isFile)
        assertFalse(profiles.readText().contains("a.ogg"))
        assertTrue(contribs.readText().contains("a.ogg"))
        val before = contribs.readText()
        contribs.setLastModified(0)
        r.rename("a", "Anna")
        r.setMe("a")
        assertEquals(before, contribs.readText())
        assertEquals(0L, contribs.lastModified())
        assertTrue(profiles.readText().contains("Anna"))
    }

    @Test
    fun crashBetweenWritesIsRolledBack() = runBlocking {
        val r = repo()
        r.apply(SpeakerUpdate(listOf(newEntry("a", cluster(1, count = 3, ms = 6_000)))), "x.ogg", clock)
        val profilesAfterFirst = File(tmp.root, "speakers.json").readText()
        // Neu-Transkribieren mit anderem Ergebnis, dann „Absturz“: Profildatei auf den alten Stand zurück
        val second = cluster(1, count = 5, ms = 10_000)
        r.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry("a", isNew = false, cluster = second))), "x.ogg", clock)
        File(tmp.root, "speakers.json").writeText(profilesAfterFirst)

        val reloaded = repo()
        assertEquals(3, reloaded.resolve("a")!!.sampleCount) // Profile wie nach dem ersten Lauf
        // erneutes Anwenden ergibt denselben Stand wie ohne Absturz
        reloaded.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry("a", isNew = false, cluster = second))), "x.ogg", clock)
        assertEquals(5, reloaded.resolve("a")!!.sampleCount)
        assertEquals(10_000L, reloaded.resolve("a")!!.totalSpeechMs)
    }

    @Test
    fun legacySingleFileIsMigrated() = runBlocking {
        // Version 1: Beiträge lagen in speakers.json
        val legacy = """
            {"version":1,"nextNumber":2,"speakers":[{"id":"a","number":1,"embedding":[1.0,0.0],"sampleCount":3,
             "totalSpeechMs":6000,"firstSeenEpochMs":1,"lastSeenEpochMs":$clock}],
             "contributions":{"x.ogg":[{"profileId":"a","count":3,"speechMs":6000,"atEpochMs":$clock}]}}
        """.trimIndent()
        File(tmp.root, "speakers.json").writeText(legacy)
        val r = repo()
        val c = ClusterSummary(floatArrayOf(1f, 0f), 3, 6_000)
        r.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry("a", isNew = false, cluster = c))), "x.ogg", clock)
        assertEquals(3, r.resolve("a")!!.sampleCount) // ersetzt, nicht addiert
        assertFalse(File(tmp.root, "speakers.json").readText().contains("x.ogg"))
    }

    @Test
    fun emptyUpdateRemovesOldContribution() = runBlocking {
        val r = repo()
        r.apply(SpeakerUpdate(listOf(newEntry("a", cluster(1, count = 3, ms = 6_000)))), "x.ogg", clock)
        r.apply(SpeakerUpdate(listOf(SpeakerUpdate.Entry("a", false, cluster(1, count = 2, ms = 4_000)))), "y.ogg", clock)
        assertEquals(5, r.resolve("a")!!.sampleCount)
        r.apply(SpeakerUpdate(emptyList()), "x.ogg", clock) // neu transkribiert, keine Sprecher mehr erkannt
        assertEquals(2, r.resolve("a")!!.sampleCount)
        assertEquals(4_000L, r.resolve("a")!!.totalSpeechMs)
    }

    @Test
    fun performanceWith500Profiles() = runBlocking {
        val dim = 256
        val rnd = Random(5)
        val r = repo()
        val entries = (0 until 500).map { i ->
            SpeakerUpdate.Entry("p$i", true, ClusterSummary(SpeakerMath.normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f }), 3, 70_000))
        }
        var t0 = System.nanoTime()
        r.apply(SpeakerUpdate(entries), "init.ogg", clock)
        val applyAllMs = (System.nanoTime() - t0) / 1_000_000

        // Eine Stunde mit vielen Abschnitten: 400 Abschnitte, 4 Sprecher, davon 2 bekannt
        val known = r.knownProfiles()
        val bases = listOf(known[0].second, known[1].second) + List(2) { SpeakerMath.normalize(FloatArray(dim) { rnd.nextFloat() - 0.5f }) }
        val items = List(400) { i ->
            val b = bases[i % 4]
            SpeakerClustering.Item(FloatArray(dim) { b[it] + (rnd.nextFloat() - 0.5f) * 0.05f }, 4_000)
        }
        t0 = System.nanoTime()
        val res = SpeakerClustering.clusterSeeded(items, known.map { it.second })
        val clusterMs = (System.nanoTime() - t0) / 1_000_000
        val (_, update) = SpeakerPlanning.plan(res.clusters.mapIndexed { k, c -> ClusterAssignment(c, res.seedOf[k]?.let { known[it].first }) })
        t0 = System.nanoTime()
        r.apply(update, "hour.ogg", clock)
        val applyMs = (System.nanoTime() - t0) / 1_000_000
        val fileKb = File(tmp.root, "speakers.json").length() / 1024
        println("500 Profile: Anlegen $applyAllMs ms, Seeds ${known.size}, Clustering 400 Abschnitte $clusterMs ms, " +
            "apply $applyMs ms, speakers.json $fileKb KB")
        assertEquals(FileSpeakerRepository.MAX_SEEDS, known.size)
        assertEquals(4, res.clusters.size)
        assertEquals(2, res.seedOf.count { it != null })
        assertTrue("Clustering zu langsam: $clusterMs ms", clusterMs < 5_000)
        assertTrue("apply zu langsam: $applyMs ms", applyMs < 5_000)
    }
}
