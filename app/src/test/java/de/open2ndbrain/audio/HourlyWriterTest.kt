package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId

/** Encoder-Attrappe: schreibt rohe Samples und merkt sich die Zeitstempel. */
internal class FakeEncoder(file: File, override val format: AudioFileFormat) : AudioFileEncoder {
    private val out = FileOutputStream(file)
    val pts = ArrayList<Long>()
    var finishResult = true
    var flushes = 0

    override fun flush() {
        flushes++
    }

    override fun write(pcm: ShortArray, offset: Int, length: Int, ptsUs: Long) {
        pts += ptsUs
        val b = ByteArray(length * 2)
        for (i in 0 until length) {
            b[2 * i] = pcm[offset + i].toByte()
            b[2 * i + 1] = (pcm[offset + i].toInt() shr 8).toByte()
        }
        out.write(b)
    }

    override fun finish(): Boolean {
        out.close()
        return finishResult
    }

    override fun abort() = out.close()
}

class HourlyWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Berlin")
    private var nowMs = 0L
    private val encoders = ArrayList<FakeEncoder>()
    private var failOpus = false

    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    private fun writer(discard: Boolean = true) = HourlyWriter(
        encoderFactory = { file, fmt, _ ->
            if (failOpus && fmt == AudioFileFormat.OPUS_OGG) throw IOException("kein Opus")
            FakeEncoder(file, fmt).also { encoders += it }
        },
        preferredFormat = { AudioFileFormat.OPUS_OGG },
        zone = { zone },
        now = { nowMs },
    ).apply { settings = HourlyWriter.Settings(tmp.root, 16, discard) }

    private val frame = ShortArray(320) { 1 }

    /** Schreibt [count] 20-ms-Frames ab [startMs]. */
    private fun HourlyWriter.frames(startMs: Long, count: Int, segmentStart: Boolean = true, input: String? = "mic") {
        for (i in 0 until count) {
            nowMs = startMs + i * 20L
            write(frame, 320, startMs + i * 20L, segmentStart && i == 0, input)
        }
    }

    private fun sidecar(f: File) = SidecarIo.decode(f.readText())

    private fun dayDir(s: String) = RecordingLayout.dayDir(tmp.root, at(s), zone)

    @Test
    fun rotatesAtHourBoundaryWithoutLosingFrames() {
        val w = writer()
        val start = at("2026-11-19T14:59:58")
        w.frames(start, 200) // 4 s: 2 s vor, 2 s nach der Stundengrenze
        w.close("stopped")

        val dir = dayDir("2026-11-19T14:00:00")
        val a = File(dir, "2026-11-19_14-00.ogg")
        val b = File(dir, "2026-11-19_15-00.ogg")
        assertTrue(a.exists() && b.exists())
        assertEquals(100 * 640L, a.length())
        assertEquals(100 * 640L, b.length())

        val sa = sidecar(File(dir, "2026-11-19_14-00.json"))
        val sb = sidecar(File(dir, "2026-11-19_15-00.json"))
        assertTrue(sa.complete && sb.complete)
        assertEquals(at("2026-11-19T14:00:00"), sa.hourStartEpochMs)
        assertEquals(listOf(RecordingSidecar.Segment(0, start, 2000, "mic")), sa.segments)
        assertEquals(listOf(RecordingSidecar.Segment(0, at("2026-11-19T15:00:00"), 2000, "mic")), sb.segments)
        assertEquals("opus", sa.codec)
        assertEquals(16_000, sa.bitrate)
        assertTrue(sa.discardSilence)
        // keine Reste
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".tmp") })
        assertTrue(RecordingSignals.activeFiles.none { it.startsWith(tmp.root.path) })
        // PTS lückenlos je Datei
        assertEquals((0 until 100).map { it * 20_000L }, encoders[0].pts)
        assertEquals((0 until 100).map { it * 20_000L }, encoders[1].pts)
    }

    @Test
    fun gapsBecomeSegmentsWhileFileTimeStaysContinuous() {
        val w = writer()
        val t1 = at("2026-11-19T10:00:05")
        val t2 = at("2026-11-19T10:10:00")
        w.frames(t1, 50)
        w.frames(t2, 25, segmentStart = true)
        w.frames(t2 + 500, 10, segmentStart = false, input = "BT") // Mikrofonwechsel → neues Segment
        w.close(null)
        val s = sidecar(File(dayDir("2026-11-19T10:00:00"), "2026-11-19_10-00.json"))
        assertEquals(
            listOf(
                RecordingSidecar.Segment(0, t1, 1000, "mic"),
                RecordingSidecar.Segment(1000, t2, 500, "mic"),
                RecordingSidecar.Segment(1500, t2 + 500, 200, "BT"),
            ),
            s.segments,
        )
        assertEquals((0 until 85).map { it * 20_000L }, encoders.single().pts)
    }

    @Test
    fun splitCreatesNextPartFileAndCarriesEvent() {
        val w = writer()
        val t = at("2026-11-19T10:20:00")
        w.frames(t, 10)
        w.split("mic_changed", "A → B")
        nowMs = t + 1000
        w.addEvent("note", null, t + 1000) // ohne offene Datei → vorgemerkt
        w.frames(t + 2000, 10)
        w.close("paused")
        w.frames(t + 5000, 10)
        w.close(null)
        val dir = dayDir("2026-11-19T10:00:00")
        assertTrue(File(dir, "2026-11-19_10-00.ogg").exists())
        assertTrue(File(dir, "2026-11-19_10-00_b.ogg").exists())
        assertTrue(File(dir, "2026-11-19_10-00_c.ogg").exists())
        val a = sidecar(File(dir, "2026-11-19_10-00.json"))
        val b = sidecar(File(dir, "2026-11-19_10-00_b.json"))
        assertEquals(listOf("mic_changed"), a.events.map { it.type })
        assertEquals(listOf("mic_changed", "note", "paused"), b.events.map { it.type })
        assertEquals("A → B", b.events.first().detail)
    }

    @Test
    fun splitNowClosesFileAndContinuesSeamlessly() {
        val w = writer()
        val t = at("2026-11-19T10:20:00")
        w.splitNow("ohne Datei") // nichts offen → No-op, kein vorgemerktes Ereignis
        w.frames(t, 50)
        nowMs = t + 1_000
        w.splitNow("Meeting Budget")
        val dir = dayDir("2026-11-19T10:00:00")
        val a = sidecar(File(dir, "2026-11-19_10-00.json")) // sofort abgeschlossen + umbenannt
        assertTrue(a.complete)
        assertEquals(listOf("split"), a.events.map { it.type })
        assertEquals("Meeting Budget", a.events.single().detail)
        // nahtlos weiter: nächster Frame (lückenlos, ohne segmentStart) öffnet _b
        for (i in 0 until 25) {
            nowMs = t + 1_000 + i * 20L
            w.write(frame, 320, t + 1_000 + i * 20L, false, "mic")
        }
        w.close(null)
        val b = sidecar(File(dir, "2026-11-19_10-00_b.json"))
        assertEquals(listOf("split"), b.events.map { it.type })
        assertEquals(listOf(RecordingSidecar.Segment(0, t + 1_000, 500, "mic")), b.segments)
        assertEquals(listOf(RecordingSidecar.Segment(0, t, 1000, "mic")), a.segments)
    }

    @Test
    fun pendingEventsFromOtherHoursAreDropped() {
        val w = writer()
        w.addEvent("resumed", null, at("2026-11-19T09:50:00"))
        w.frames(at("2026-11-19T10:05:00"), 5)
        w.close(null)
        val s = sidecar(File(dayDir("2026-11-19T10:00:00"), "2026-11-19_10-00.json"))
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun partFilesWhileOpenAndPeriodicSidecar() {
        val w = writer()
        val t = at("2026-11-19T11:00:00")
        w.frames(t, 50)
        val dir = dayDir("2026-11-19T11:00:00")
        val audioPart = File(dir, "2026-11-19_11-00.ogg.part")
        val jsonPart = File(dir, "2026-11-19_11-00.json.part")
        assertTrue(audioPart.exists())
        assertEquals(audioPart, w.currentFile)
        assertTrue(RecordingSignals.isActive(audioPart))
        assertTrue(sidecar(jsonPart).segments.isEmpty()) // beim Öffnen geschrieben
        assertFalse(sidecar(jsonPart).complete)

        nowMs = t + 61_000
        w.tick()
        val s = sidecar(jsonPart)
        assertEquals(1000L, s.segments.single().durationMs)
        assertEquals(1000L, w.currentStoredMs)
        w.close(null)
        assertNull(w.currentFile)
        assertFalse(RecordingSignals.isActive(audioPart))
    }

    @Test
    fun tickClosesFileAfterHourWithoutFrames() {
        val w = writer()
        w.frames(at("2026-11-19T14:59:00"), 50)
        val dir = dayDir("2026-11-19T14:00:00")
        nowMs = at("2026-11-19T15:00:02")
        w.tick()
        assertNotNull(w.currentFile) // Karenzzeit 3 s noch nicht vorbei
        assertTrue(encoders.single().flushes > 0) // Encoder-Ausgang wird geleert
        nowMs = at("2026-11-19T15:00:04")
        w.tick(idle = false) // noch Frames in der Warteschlange → nicht schließen
        assertNotNull(w.currentFile)
        w.tick(idle = true)
        assertNull(w.currentFile)
        assertTrue(File(dir, "2026-11-19_14-00.ogg").exists())
        assertTrue(sidecar(File(dir, "2026-11-19_14-00.json")).complete)
    }

    @Test
    fun emptyFileIsDiscarded() {
        val w = writer()
        w.frames(at("2026-11-19T12:00:00"), 0)
        w.close("stopped")
        assertFalse(tmp.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun fallsBackToAacWhenOpusFails() {
        failOpus = true
        val w = writer(discard = false)
        w.frames(at("2026-11-19T13:00:00"), 5)
        w.close(null)
        val dir = dayDir("2026-11-19T13:00:00")
        assertTrue(File(dir, "2026-11-19_13-00.m4a").exists())
        assertFalse(File(dir, "2026-11-19_13-00.ogg.part").exists())
        val s = sidecar(File(dir, "2026-11-19_13-00.json"))
        assertEquals("aac", s.codec)
        assertEquals("mp4", s.container)
        assertFalse(s.discardSilence)
    }

    @Test
    fun abortKeepsAacFileAsIncomplete() {
        failOpus = true
        val w = writer()
        w.frames(at("2026-11-19T13:00:00"), 5)
        w.abort("Schreibfehler")
        val dir = dayDir("2026-11-19T13:00:00")
        val s = sidecar(File(dir, "2026-11-19_13-00.json"))
        assertFalse(s.complete)
        assertEquals("error", s.events.last().type)
        assertTrue(File(dir, "2026-11-19_13-00.m4a").exists())
    }

    @Test
    fun failedFinishOfInvalidOggDiscardsFile() {
        val w = writer()
        w.frames(at("2026-11-19T13:00:00"), 5)
        encoders.single().finishResult = false
        w.close(null)
        // Attrappe schreibt kein gültiges OGG → nach Prüfung verworfen
        assertFalse(tmp.root.walkTopDown().any { it.isFile })
    }

    @Test
    fun storedMsInDirSumsFinalAndPartSidecars() {
        val w = writer()
        w.frames(at("2026-11-19T08:00:00"), 50) // 1 s
        w.split()
        w.frames(at("2026-11-19T09:00:00"), 100) // 2 s, bleibt offen
        val dir = dayDir("2026-11-19T08:00:00")
        nowMs += 61_000
        w.tick()
        assertEquals(3000L, SidecarIo.storedMsInDir(dir))
        assertEquals(1000L, SidecarIo.storedMsInDir(dir, exclude = setOfNotNull(w.currentBaseName)))
        assertNotNull(w.currentHourStart)
        w.close(null)
    }
}
