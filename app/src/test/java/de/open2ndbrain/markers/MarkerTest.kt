package de.open2ndbrain.markers

import de.open2ndbrain.core.BOOKMARK_LOOKBACK_MS
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.Utterance
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MarkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    private var ids = 0
    private fun repo(dir: File) = FileMarkerRepository(dir, now = { clock }, newId = { "id${++ids}" })

    @Test
    fun startEndAndRestore() = runTest {
        val dir = tmp.newFolder("markers")
        val r = repo(dir)
        assertNull(r.activeMeeting.value)
        val m = r.startMeeting("  Planung ")
        assertEquals("Planung", m.title)
        assertEquals(m, r.activeMeeting.value)
        // Zweiter Start liefert dasselbe Meeting
        assertEquals(m.id, r.startMeeting().id)

        // Laufendes Meeting überlebt einen Neustart (neue Instanz liest die Datei)
        val restored = repo(dir)
        assertEquals(m.id, restored.activeMeeting.value?.id)

        clock += 60_000
        val ended = restored.endMeeting()
        assertEquals(clock, ended?.endEpochMs)
        assertNull(restored.activeMeeting.value)
        assertNull(restored.endMeeting())
        assertEquals(clock, repo(dir).current().single().endEpochMs)
    }

    @Test
    fun bookmarkUpdateDelete() = runTest {
        val r = repo(tmp.newFolder("m2"))
        val b = r.addBookmark(" ")
        assertEquals(MarkerType.Bookmark, b.type)
        assertEquals(clock - BOOKMARK_LOOKBACK_MS, b.startEpochMs)
        assertEquals(clock, b.endEpochMs)
        assertNull(b.note)
        r.update(b.copy(note = "wichtig"))
        assertEquals("wichtig", r.current().single().note)
        r.delete(b.id)
        assertTrue(r.current().isEmpty())
    }

    @Test
    fun brokenFileIsSetAside() {
        val dir = tmp.newFolder("m3")
        File(dir, "markers.json").writeText("{kaputt")
        val r = repo(dir)
        assertTrue(r.current().isEmpty())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("markers.json.broken") })
    }

    private fun rec(name: String, segStart: Long, segDur: Long) = RecordingFile(
        audio = File("/a/$name"),
        sidecar = RecordingSidecar(
            hourStartEpochMs = 0, bitrate = 16_000, discardSilence = true,
            segments = listOf(RecordingSidecar.Segment(0, segStart, segDur)),
        ),
        hourStartEpochMs = 0, durationMs = segDur, sizeBytes = 1, inProgress = false,
    )

    private fun meeting(start: Long, end: Long?) = Marker("m", MarkerType.Meeting, start, end, createdAtEpochMs = start)

    @Test
    fun fileMeetingOverlap() {
        val r = rec("a.ogg", 10_000, 5_000) // 10 000 … 15 000
        assertTrue(MarkerLogic.overlaps(meeting(12_000, 20_000), r, 0))
        assertTrue(MarkerLogic.overlaps(meeting(0, 10_001), r, 0))
        assertFalse(MarkerLogic.overlaps(meeting(0, 10_000), r, 0)) // endet genau beim Dateibeginn
        assertFalse(MarkerLogic.overlaps(meeting(15_000, 20_000), r, 0))
        assertFalse(MarkerLogic.overlaps(meeting(0, 9_999), r, 0))
        // Laufendes Meeting reicht bis jetzt
        assertTrue(MarkerLogic.overlaps(meeting(5_000, null), r, now = 11_000))
        // Ohne Sidecar: ganze Stunde
        val noSidecar = r.copy(sidecar = null, hourStartEpochMs = 3_600_000)
        assertTrue(MarkerLogic.overlaps(meeting(3_600_000 + 10, 3_600_000 + 20), noSidecar, 0))
    }

    @Test
    fun meetingFilesFirst() {
        val a = rec("a.ogg", 0, 1000)
        val b = rec("b.ogg", 5000, 1000)
        val c = rec("c.ogg", 9000, 1000)
        val ordered = MarkerLogic.prioritize(listOf(a, b, c), listOf(meeting(4000, 7000)), 0)
        assertEquals(listOf(b, a, c), ordered)
        // Laufende Meetings werden nicht vorgezogen
        assertEquals(listOf(a, b, c), MarkerLogic.prioritize(listOf(a, b, c), listOf(meeting(4000, null)), 10_000))
    }

    @Test
    fun utterancesAcrossFiles() {
        fun t(name: String, vararg starts: Long) = Transcript(
            audioFileName = name, asrModelId = "x", createdAtEpochMs = 0, audioDurationMs = 0, processingMs = 0,
            utterances = starts.map { Utterance(0, 1000, it, "u$it") },
        )
        val lines = MarkerLogic.utterancesIn(100L until 300L, listOf(t("b.ogg", 250, 400), t("a.ogg", 50, 120)))
        assertEquals(listOf("a.ogg" to 120L, "b.ogg" to 250L), lines.map { it.audioFileName to it.utterance.startEpochMs })
    }

    @Test
    fun bookmarksAndLongMeetings() {
        val bm = Marker("b", MarkerType.Bookmark, 40_000, 70_000, createdAtEpochMs = 70_000)
        val r = rec("a.ogg", 0, 100_000)
        assertEquals(listOf(bm), MarkerLogic.bookmarksIn(r, listOf(bm, meeting(0, 10))))
        assertTrue(MarkerLogic.bookmarkHits(Utterance(0, 5_000, 38_000, "x"), bm))
        assertFalse(MarkerLogic.bookmarkHits(Utterance(0, 5_000, 80_000, "x"), bm))
        assertNotNull(MarkerLogic.activeMeeting(listOf(meeting(0, null))))
        assertTrue(MarkerLogic.isLongRunning(meeting(0, null), MarkerLogic.LONG_MEETING_MS))
        assertFalse(MarkerLogic.isLongRunning(meeting(0, null), MarkerLogic.LONG_MEETING_MS - 1))
        assertFalse(MarkerLogic.isLongRunning(meeting(0, 5), MarkerLogic.LONG_MEETING_MS * 2))
    }
}
