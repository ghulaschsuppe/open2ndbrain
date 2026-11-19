package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class FileRecordingRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = at("2026-11-19T15:30")

    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private fun repo() = FileRecordingRepository(tmp.root, refreshMs = 60_000, zone = { zone }, now = { now })

    /** Legt Audio (+ Sidecar) an. */
    private fun make(base: String, part: Boolean = false, durationMs: Long = 1000, sidecar: Boolean = true): File {
        val p = RecordingLayout.parseBaseName(base)!!
        val hour = p.localHour.atZone(zone).toInstant().toEpochMilli()
        val dir = RecordingLayout.dayDir(tmp.root, hour, zone).apply { mkdirs() }
        val suffix = if (part) ".part" else ""
        val audio = File(dir, "$base.ogg$suffix").apply { writeBytes(ByteArray(100)) }
        if (sidecar) {
            SidecarIo.writeAtomic(
                File(dir, "$base.json$suffix"),
                RecordingSidecar(
                    hourStartEpochMs = hour, bitrate = 16_000, discardSilence = true, complete = !part,
                    segments = listOf(RecordingSidecar.Segment(0, hour, durationMs)),
                ),
            )
        }
        return audio
    }

    @Test
    fun listsNewestFirstWithPartsAndDurations() {
        make("2026-11-18_09-00")
        make("2026-11-19_14-00", durationMs = 5000)
        make("2026-11-19_14-00_b", durationMs = 2000)
        val active = make("2026-11-19_15-00", part = true, durationMs = 300)
        make("2026-11-19_13-00", sidecar = false)
        RecordingSignals.markActive(active)
        val list = try {
            repo().scan()
        } finally {
            RecordingSignals.markInactive(active)
        }
        assertEquals(
            listOf("2026-11-19_15-00.ogg.part", "2026-11-19_14-00_b.ogg", "2026-11-19_14-00.ogg", "2026-11-19_13-00.ogg", "2026-11-18_09-00.ogg"),
            list.map { it.audio.name },
        )
        assertTrue(list[0].inProgress)
        assertEquals(300L, list[0].durationMs)
        assertEquals(5000L, list[2].durationMs)
        assertEquals(at("2026-11-19T13:00"), list[3].hourStartEpochMs)
        assertEquals(0L, list[3].durationMs)
        assertEquals(100L, list[3].sizeBytes)
        assertFalse(list[1].inProgress)
        // ohne aktive Engine ist eine .part-Datei verwaist, nicht „in Arbeit“
        assertFalse(repo().scan()[0].inProgress)
    }

    @Test
    fun deleteOlderThanZeroOrNegativeDeletesNothing() = runBlocking {
        make("2020-01-01_10-00")
        val r = repo()
        assertEquals(0, r.deleteOlderThan(0))
        assertEquals(0, r.deleteOlderThan(-5))
        assertEquals(1, r.scan().size)
    }

    @Test
    fun firstObserveRecoversOrphansOnlyWithoutRunningEngine() = runBlocking {
        val ogg = javaClass.getResourceAsStream("/audio/sine_2s.ogg")!!.use { it.readBytes() }
        val dir = File(tmp.root, "2026/11/19").apply { mkdirs() }
        File(dir, "2026-11-19_14-00.ogg.part").writeBytes(ogg)
        // Engine läuft → keine Wiederherstellung durchs Repository
        RecordingSignals.sessionStarted()
        try {
            val list = withTimeout(5_000) { repo().observeRecordings().first() }
            assertEquals("2026-11-19_14-00.ogg.part", list.single().audio.name)
        } finally {
            RecordingSignals.sessionEnded()
        }
        val list = withTimeout(5_000) { repo().observeRecordings().first() }
        assertEquals("2026-11-19_14-00.ogg", list.single().audio.name)
        assertEquals(2000L, list.single().durationMs)
        assertFalse(list.single().sidecar!!.complete)
    }

    @Test
    fun deleteOlderThanOnlyRemovesCompletedOldFiles() = runBlocking {
        make("2026-10-01_10-00")
        make("2026-10-01_11-00", part = true) // unvollständig → bleibt
        make("2026-11-18_10-00")
        make("2026-11-19_10-00")
        val r = repo()
        assertEquals(1, r.deleteOlderThan(30))
        assertEquals(1, r.deleteOlderThan(1))
        val names = r.scan().map { it.audio.name }
        assertEquals(listOf("2026-11-19_10-00.ogg", "2026-10-01_11-00.ogg.part"), names)
        assertFalse(File(tmp.root, "2026/11/18").exists()) // leerer Ordner entfernt
        assertTrue(File(tmp.root, "2026/10/01").exists())
    }

    @Test
    fun deleteSkipsActiveFile() = runBlocking {
        val active = make("2026-11-19_15-00", part = true)
        val r = repo()
        RecordingSignals.markActive(active)
        try {
            r.delete(r.scan().single())
            assertTrue(active.exists())
        } finally {
            RecordingSignals.markInactive(active)
        }
        r.delete(r.scan().single())
        assertFalse(active.exists())
        assertEquals(0L, r.totalSizeBytes())
    }

    @Test
    fun totalSizeCountsAllFiles() = runBlocking {
        make("2026-11-19_14-00")
        val r = repo()
        val expected = tmp.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(expected > 100)
        assertEquals(expected, r.totalSizeBytes())
    }

    @Test
    fun observeEmitsOnEngineSignal() = runBlocking {
        val r = repo()
        val ch = Channel<List<RecordingFile>>(10)
        val job = launch(Dispatchers.IO) { r.observeRecordings().collect { ch.send(it) } }
        assertEquals(0, withTimeout(5_000) { ch.receive() }.size)
        make("2026-11-19_14-00")
        RecordingSignals.notifyChanged()
        assertEquals(1, withTimeout(5_000) { ch.receive() }.size)
        job.cancel()
    }
}
