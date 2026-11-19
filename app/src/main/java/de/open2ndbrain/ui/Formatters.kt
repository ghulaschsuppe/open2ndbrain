package de.open2ndbrain.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reine Formatierungsfunktionen (deutsch), ohne Android-Abhängigkeiten – unit-getestet. */
object Formatters {
    private val GERMAN = Locale.GERMANY
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN)

    /** „3 h 12 min“, „3 h“, „12 min“, „45 s“. */
    fun duration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            minutes > 0 -> "$minutes min"
            else -> "${totalSeconds} s"
        }
    }

    /** Laufzeit in ganzen Minuten: „< 1 min“, „12 min“, „3 h 12 min“. */
    fun elapsedMinutes(minutes: Long): String =
        if (minutes < 1) "< 1 min" else duration(minutes * 60_000)

    /** Abspielposition: „0:05“, „12:34“, „1:02:03“. */
    fun clock(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(GERMAN, "%d:%02d:%02d", h, m, s)
        else String.format(GERMAN, "%d:%02d", m, s)
    }

    /** Binäre Einheiten (wie im Konzept: 16 kbit/s ≈ 6,8 MB/h): „512 B“, „12 KB“, „6,8 MB“, „1,2 GB“. */
    fun bytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b < kb -> "$b B"
            b < mb -> String.format(GERMAN, "%.0f KB", b / kb)
            b < gb -> String.format(GERMAN, "%.1f MB", b / mb)
            else -> String.format(GERMAN, "%.1f GB", b / gb)
        }
    }

    /** Uhrzeit „14:35“ in der gegebenen Zeitzone. */
    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Stundenbereich einer Aufnahmedatei: „14:00–15:00“. */
    fun hourRange(hourStartEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(hourStartEpochMs).atZone(zone)
        return "${TIME.format(start)}–${TIME.format(start.plusHours(1))}"
    }
}
