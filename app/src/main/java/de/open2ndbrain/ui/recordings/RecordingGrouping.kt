package de.open2ndbrain.ui.recordings

import de.open2ndbrain.core.RecordingFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Aufnahmen eines Kalendertages. */
data class DayGroup(
    val date: LocalDate,
    /** Neueste zuerst. */
    val recordings: List<RecordingFile>,
    val totalDurationMs: Long,
    val totalSizeBytes: Long,
)

/** Überschrift eines Tages; „Heute“/„Gestern“ werden in der UI aus Ressourcen aufgelöst. */
sealed interface DayLabel {
    data object Today : DayLabel
    data object Yesterday : DayLabel
    data class Date(val text: String) : DayLabel
}

object RecordingGrouping {
    private val DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
    private val PART = Regex("""_\d{2}-\d{2}_([a-z])\.""")

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Gruppiert nach lokalem Tag der Aufnahmestunde, neueste Tage und Stunden zuerst. */
    fun groupByDay(recordings: List<RecordingFile>, zone: ZoneId): List<DayGroup> =
        recordings
            .groupBy { localDate(it.hourStartEpochMs, zone) }
            .map { (date, items) ->
                val sorted = items.sortedWith(
                    compareByDescending<RecordingFile> { it.hourStartEpochMs }.thenByDescending { it.audio.name },
                )
                DayGroup(date, sorted, sorted.sumOf { it.durationMs }, sorted.sumOf { it.sizeBytes })
            }
            .sortedByDescending { it.date }

    fun dayLabel(date: LocalDate, today: LocalDate): DayLabel = when (date) {
        today -> DayLabel.Today
        today.minusDays(1) -> DayLabel.Yesterday
        else -> DayLabel.Date(DATE.format(date))
    }

    /** Gespeicherte Dauer aller Aufnahmen des Tages [today]. */
    fun storedMsOn(recordings: List<RecordingFile>, today: LocalDate, zone: ZoneId): Long =
        recordings.filter { localDate(it.hourStartEpochMs, zone) == today }.sumOf { it.durationMs }

    /** Teilnummer aus dem Dateinamen: `…_14-00.ogg` = 1, `…_14-00_b.ogg` = 2 usw. */
    fun partNumber(fileName: String): Int {
        val match = PART.find(fileName) ?: return 1
        return match.groupValues[1][0] - 'a' + 1
    }

    /** Mikrofone laut Sidecar, in Reihenfolge des ersten Auftretens. */
    fun inputNames(recording: RecordingFile): List<String> =
        recording.sidecar?.segments.orEmpty().mapNotNull { it.input }.distinct()
}
