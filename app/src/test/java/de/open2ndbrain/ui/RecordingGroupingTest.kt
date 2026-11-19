package de.open2ndbrain.ui

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.ui.recordings.DayLabel
import de.open2ndbrain.ui.recordings.RecordingGrouping
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecordingGroupingTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun rec(day: Int, hour: Int, name: String = "x", durationMs: Long = 60_000, size: Long = 1000) = RecordingFile(
        audio = File("/a/2026-11-%02d_%02d-00%s.ogg".format(day, hour, name)),
        sidecar = null,
        hourStartEpochMs = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant().toEpochMilli(),
        durationMs = durationMs,
        sizeBytes = size,
        inProgress = false,
    )

    @Test
    fun groupsByLocalDayNewestFirst() {
        val list = listOf(rec(18, 9, ""), rec(19, 8, ""), rec(19, 14, ""), rec(19, 14, "_b"), rec(17, 23, ""))
        val groups = RecordingGrouping.groupByDay(list, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 17)), groups.map { it.date })
        val today = groups[0]
        assertEquals(listOf("2026-11-19_14-00_b.ogg", "2026-11-19_14-00.ogg", "2026-11-19_08-00.ogg"), today.recordings.map { it.audio.name })
        assertEquals(180_000L, today.totalDurationMs)
        assertEquals(3000L, today.totalSizeBytes)
    }

    @Test
    fun midnightBelongsToNewDay() {
        val groups = RecordingGrouping.groupByDay(listOf(rec(19, 0, ""), rec(18, 23, "")), zone)
        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 11, 19), groups[0].date)
    }

    @Test
    fun dayLabels() {
        val today = LocalDate.of(2026, 11, 19)
        assertEquals(DayLabel.Today, RecordingGrouping.dayLabel(today, today))
        assertEquals(DayLabel.Yesterday, RecordingGrouping.dayLabel(today.minusDays(1), today))
        assertEquals(DayLabel.Date("Dienstag, 17. November 2026"), RecordingGrouping.dayLabel(today.minusDays(2), today))
    }

    @Test
    fun storedToday() {
        val list = listOf(rec(19, 8, "", durationMs = 1000), rec(19, 9, "", durationMs = 2000), rec(18, 9, "", durationMs = 5000))
        assertEquals(3000L, RecordingGrouping.storedMsOn(list, LocalDate.of(2026, 11, 19), zone))
    }

    @Test
    fun partNumber() {
        assertEquals(1, RecordingGrouping.partNumber("2026-11-19_14-00.ogg"))
        assertEquals(2, RecordingGrouping.partNumber("2026-11-19_14-00_b.ogg"))
        assertEquals(3, RecordingGrouping.partNumber("2026-11-19_14-00_c.ogg"))
        assertEquals(2, RecordingGrouping.partNumber("2026-11-19_14-00_b.ogg.part"))
        assertEquals(2, RecordingGrouping.partNumber("2026-11-19_14-00_b.m4a"))
        assertEquals(1, RecordingGrouping.partNumber("2026-11-19_14-00.m4a"))
    }

    @Test
    fun inputNamesDistinctInOrder() {
        val sidecar = RecordingSidecar(
            hourStartEpochMs = 0,
            bitrate = 16_000,
            discardSilence = true,
            segments = listOf(
                RecordingSidecar.Segment(0, 0, 10, "Pixel Buds"),
                RecordingSidecar.Segment(10, 10, 10, null),
                RecordingSidecar.Segment(20, 20, 10, "Eingebaut"),
                RecordingSidecar.Segment(30, 30, 10, "Pixel Buds"),
            ),
        )
        val r = rec(19, 8, "").copy(sidecar = sidecar)
        assertEquals(listOf("Pixel Buds", "Eingebaut"), RecordingGrouping.inputNames(r))
    }
}
