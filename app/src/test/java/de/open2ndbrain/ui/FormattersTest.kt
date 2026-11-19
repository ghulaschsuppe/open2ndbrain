package de.open2ndbrain.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FormattersTest {
    @Test
    fun duration() {
        assertEquals("0 s", Formatters.duration(0))
        assertEquals("45 s", Formatters.duration(45_999))
        assertEquals("12 min", Formatters.duration(12 * 60_000L + 30_000))
        assertEquals("1 h", Formatters.duration(3_600_000))
        assertEquals("3 h 12 min", Formatters.duration((3 * 60 + 12) * 60_000L))
        assertEquals("0 s", Formatters.duration(-5))
    }

    @Test
    fun seconds() {
        assertEquals("3,2 s", Formatters.seconds(3_240))
        assertEquals("0,0 s", Formatters.seconds(-1))
    }

    @Test
    fun elapsedMinutes() {
        assertEquals("< 1 min", Formatters.elapsedMinutes(0))
        assertEquals("1 min", Formatters.elapsedMinutes(1))
        assertEquals("3 h 12 min", Formatters.elapsedMinutes(192))
    }

    @Test
    fun clock() {
        assertEquals("0:00", Formatters.clock(0))
        assertEquals("0:05", Formatters.clock(5_400))
        assertEquals("12:34", Formatters.clock((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", Formatters.clock((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test
    fun bytes() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("12 KB", Formatters.bytes(12 * 1024L))
        // 16 kbit/s * 3600 s = 7 200 000 Byte ≈ 6,9 MiB (Konzept: 6,8 MB gemessen, VBR)
        assertEquals("6,9 MB", Formatters.bytes(7_200_000))
        assertEquals("1,5 GB", Formatters.bytes(1536L * 1024 * 1024))
    }

    @Test
    fun hourRangeAndTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = LocalDateTime.of(2026, 11, 19, 14, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14:00–15:00", Formatters.hourRange(start, zone))
        assertEquals("23:00–00:00", Formatters.hourRange(start + 9 * 3_600_000L, zone))
        assertEquals("14:35", Formatters.time(start + 35 * 60_000L, zone))
    }
}
