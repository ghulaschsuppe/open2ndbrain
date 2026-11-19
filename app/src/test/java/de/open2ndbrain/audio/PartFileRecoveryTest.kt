package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class PartFileRecoveryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private val hour = LocalDateTime.parse("2026-11-19T14:00").atZone(zone).toInstant().toEpochMilli()
    private val dir by lazy { File(tmp.root, "2026/11/19").apply { mkdirs() } }

    private fun ogg(): ByteArray = javaClass.getResourceAsStream("/audio/sine_2s.ogg")!!.use { it.readBytes() }

    private fun sidecar(complete: Boolean, segments: List<RecordingSidecar.Segment>) = RecordingSidecar(
        hourStartEpochMs = hour, bitrate = 16_000, discardSilence = true, segments = segments, complete = complete,
    )

    private fun recover(skip: Set<String> = emptySet()) = PartFileRecovery.recover(tmp.root, zone, skip, nowMs = 42)

    @Test
    fun truncatedOggIsRepairedAndSegmentsExtended() {
        File(dir, "2026-11-19_14-00.ogg.part").writeBytes(ogg().copyOf(3000))
        SidecarIo.writeAtomic(
            File(dir, "2026-11-19_14-00.json.part"),
            sidecar(false, listOf(RecordingSidecar.Segment(0, hour + 5_000, 500, "mic"))),
        )
        assertEquals(1, recover())
        val audio = File(dir, "2026-11-19_14-00.ogg")
        assertTrue(audio.exists())
        assertEquals(2943L, audio.length())
        assertFalse(File(dir, "2026-11-19_14-00.ogg.part").exists())
        assertFalse(File(dir, "2026-11-19_14-00.json.part").exists())
        val s = SidecarIo.decode(File(dir, "2026-11-19_14-00.json").readText())
        assertFalse(s.complete)
        assertEquals((57600L - 312) / 48, s.segments.single().durationMs)
        assertEquals("recovered", s.events.last().type)
        assertEquals(42L, s.events.last().atEpochMs)
    }

    @Test
    fun headerOnlyOggIsDeleted() {
        File(dir, "2026-11-19_14-00_b.ogg.part").writeBytes(ogg().copyOf(121))
        SidecarIo.writeAtomic(File(dir, "2026-11-19_14-00_b.json.part"), sidecar(false, emptyList()))
        recover()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun completeSidecarIsKeptComplete() {
        // Absturz zwischen „Sidecar complete geschrieben“ und Umbenennen
        File(dir, "2026-11-19_14-00.ogg.part").writeBytes(ogg())
        val seg = listOf(RecordingSidecar.Segment(0, hour, 2000))
        SidecarIo.writeAtomic(File(dir, "2026-11-19_14-00.json.part"), sidecar(true, seg))
        recover()
        val s = SidecarIo.decode(File(dir, "2026-11-19_14-00.json").readText())
        assertTrue(s.complete)
        assertEquals(seg, s.segments)
        assertTrue(s.events.isEmpty())
        assertTrue(File(dir, "2026-11-19_14-00.ogg").exists())
    }

    @Test
    fun finalAudioWithPartSidecarIsFinished() {
        File(dir, "2026-11-19_14-00.ogg").writeBytes(ogg())
        SidecarIo.writeAtomic(File(dir, "2026-11-19_14-00.json.part"), sidecar(true, emptyList()))
        recover()
        assertTrue(File(dir, "2026-11-19_14-00.json").exists())
        assertFalse(File(dir, "2026-11-19_14-00.json.part").exists())
    }

    @Test
    fun missingSidecarIsCreatedFromName() {
        File(dir, "2026-11-19_14-00.ogg.part").writeBytes(ogg())
        recover()
        val s = SidecarIo.decode(File(dir, "2026-11-19_14-00.json").readText())
        assertEquals(hour, s.hourStartEpochMs)
        assertEquals(2000L, s.segments.single().durationMs)
        assertFalse(s.complete)
    }

    @Test
    fun aacPartIsKeptAsIncomplete() {
        File(dir, "2026-11-19_14-00.m4a.part").writeBytes(ByteArray(1000) { 1 })
        File(dir, "2026-11-19_14-00_b.m4a.part").writeBytes(ByteArray(0))
        recover()
        assertTrue(File(dir, "2026-11-19_14-00.m4a").exists())
        val s = SidecarIo.decode(File(dir, "2026-11-19_14-00.json").readText())
        assertEquals("aac", s.codec)
        assertFalse(s.complete)
        assertFalse(File(dir, "2026-11-19_14-00_b.m4a.part").exists())
        assertFalse(File(dir, "2026-11-19_14-00_b.m4a").exists())
    }

    @Test
    fun activeFilesAndTmpHandling() {
        val active = File(dir, "2026-11-19_14-00.ogg.part").apply { writeBytes(ogg().copyOf(121)) }
        val tmpFile = File(dir, "2026-11-19_13-00.json.part.tmp").apply { writeText("{") }
        val orphanJson = File(dir, "2026-11-19_12-00.json.part").apply { writeText("{}") }
        recover(skip = setOf(active.absolutePath))
        assertTrue(active.exists())
        assertFalse(tmpFile.exists())
        assertFalse(orphanJson.exists()) // Sidecar ohne Audio
    }

    @Test
    fun reconcileWithoutSegmentsCreatesOne() {
        val s = PartFileRecovery.reconcile(sidecar(false, emptyList()), 3_000, lastModifiedMs = hour + 60_000, nowMs = 1)
        assertEquals(RecordingSidecar.Segment(0, hour + 57_000, 3_000), s.segments.single())
        val unchanged = PartFileRecovery.reconcile(
            sidecar(false, listOf(RecordingSidecar.Segment(0, hour, 3_000))), 2_000, hour, 1,
        )
        assertEquals(3_000L, unchanged.segments.single().durationMs)
    }
}
