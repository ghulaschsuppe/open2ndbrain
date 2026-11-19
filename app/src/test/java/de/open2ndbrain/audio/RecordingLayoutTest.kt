package de.open2ndbrain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecordingLayoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(s: String, zone: ZoneId = berlin): Long =
        LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun hourStartAndNext_normalDay() {
        val t = at("2026-11-19T14:37:12")
        val h = RecordingLayout.hourStart(t, berlin)
        assertEquals(at("2026-11-19T14:00:00"), h)
        assertEquals(at("2026-11-19T15:00:00"), RecordingLayout.nextHourStart(h, berlin))
        assertEquals("2026-11-19_14-00", RecordingLayout.baseName(h, berlin, 0))
        assertEquals(File(tmp.root, "2026/11/19"), RecordingLayout.dayDir(tmp.root, h, berlin))
    }

    @Test
    fun midnightBelongsToNextDay() {
        val h = RecordingLayout.hourStart(at("2026-12-31T23:59:59"), berlin)
        val next = RecordingLayout.nextHourStart(h, berlin)
        assertEquals("2027-01-01_00-00", RecordingLayout.baseName(next, berlin, 0))
        assertEquals(File(tmp.root, "2027/01/01"), RecordingLayout.dayDir(tmp.root, next, berlin))
    }

    @Test
    fun springForward_hour02IsSkipped() {
        // 29.03.2026: 02:00 CET → 03:00 CEST
        val h1 = RecordingLayout.hourStart(at("2026-03-29T01:30:00"), berlin)
        val next = RecordingLayout.nextHourStart(h1, berlin)
        assertEquals(RecordingLayout.HOUR_MS, next - h1) // echte 60 min
        assertEquals("2026-03-29_03-00", RecordingLayout.baseName(next, berlin, 0))
        assertEquals(next, RecordingLayout.hourStart(next + 1, berlin))
    }

    @Test
    fun fallBack_hour02OccursTwice_secondGetsSuffix() {
        // 25.10.2026: 03:00 CEST → 02:00 CET
        val firstTwo = ZonedDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00"), berlin)
            .withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val h1 = RecordingLayout.hourStart(firstTwo, berlin)
        val h2 = RecordingLayout.nextHourStart(h1, berlin)
        val h3 = RecordingLayout.nextHourStart(h2, berlin)
        assertEquals(RecordingLayout.HOUR_MS, h2 - h1)
        assertEquals(RecordingLayout.HOUR_MS, h3 - h2)
        assertEquals("2026-10-25_02-00", RecordingLayout.baseName(h1, berlin, 0))
        assertEquals("2026-10-25_02-00", RecordingLayout.baseName(h2, berlin, 0))
        assertEquals("2026-10-25_03-00", RecordingLayout.baseName(h3, berlin, 0))
        // Zeitpunkt in der zweiten 02-Uhr-Stunde gehört zu h2, nicht zu h1
        assertEquals(h2, RecordingLayout.hourStart(h2 + 30 * 60_000, berlin))

        val dir = RecordingLayout.dayDir(tmp.root, h1, berlin).apply { mkdirs() }
        val b1 = RecordingLayout.allocateBaseName(dir, h1, berlin)
        File(dir, "$b1.ogg").writeText("x")
        val b2 = RecordingLayout.allocateBaseName(dir, h2, berlin)
        assertEquals("2026-10-25_02-00_b", b2)
    }

    @Test
    fun halfHourZone() {
        val india = ZoneId.of("Asia/Kolkata")
        val h = RecordingLayout.hourStart(at("2026-05-01T10:45:00", india), india)
        assertEquals(at("2026-05-01T10:00:00", india), h)
        assertEquals(at("2026-05-01T11:00:00", india), RecordingLayout.nextHourStart(h, india))
    }

    @Test
    fun partSuffixRoundTrip() {
        assertEquals("", RecordingLayout.partSuffix(0))
        assertEquals("_b", RecordingLayout.partSuffix(1))
        assertEquals("_c", RecordingLayout.partSuffix(2))
        assertEquals("_z", RecordingLayout.partSuffix(25))
        assertEquals("_aa", RecordingLayout.partSuffix(26))
        assertEquals("_ab", RecordingLayout.partSuffix(27))
        for (i in 0..800) {
            val s = RecordingLayout.partSuffix(i).removePrefix("_")
            assertEquals(i, RecordingLayout.partIndex(s.ifEmpty { null }))
        }
        assertNull(RecordingLayout.partIndex("a"))
        assertNull(RecordingLayout.partIndex("B"))
    }

    @Test
    fun parseNames() {
        val p = RecordingLayout.parseBaseName("2026-11-19_14-00_c")!!
        assertEquals(LocalDateTime.parse("2026-11-19T14:00"), p.localHour)
        assertEquals(2, p.partIndex)
        assertEquals(0, RecordingLayout.parseBaseName("2026-11-19_14-00")!!.partIndex)
        assertNull(RecordingLayout.parseBaseName("2026-11-19_14-30"))
        assertNull(RecordingLayout.parseBaseName("2026-13-19_14-00"))
        assertNull(RecordingLayout.parseBaseName("foo"))

        val fn = RecordingLayout.splitFileName("2026-11-19_14-00_b.ogg.part")!!
        assertEquals("2026-11-19_14-00_b", fn.base)
        assertEquals("ogg", fn.ext)
        assertTrue(fn.part)
        assertEquals("json", RecordingLayout.splitFileName("x.json")!!.ext)
    }

    @Test
    fun allocateSkipsTakenNamesIncludingPartAndSidecar() {
        val h = at("2026-11-19T14:00:00")
        val dir = File(tmp.root, "d").apply { mkdirs() }
        assertEquals("2026-11-19_14-00", RecordingLayout.allocateBaseName(dir, h, berlin))
        File(dir, "2026-11-19_14-00.ogg.part").writeText("x")
        assertEquals("2026-11-19_14-00_b", RecordingLayout.allocateBaseName(dir, h, berlin))
        File(dir, "2026-11-19_14-00_b.json").writeText("x") // auch ein verwaistes Sidecar belegt
        assertEquals("2026-11-19_14-00_c", RecordingLayout.allocateBaseName(dir, h, berlin))
        assertEquals(
            "2026-11-19_14-00_d",
            RecordingLayout.allocateBaseName(dir, h, berlin, reserved = setOf("2026-11-19_14-00_c")),
        )
    }
}
