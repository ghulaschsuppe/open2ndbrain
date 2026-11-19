package de.open2ndbrain.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reine Formatierungsfunktionen (deutsch), ohne Android-Abhängigkeiten – unit-getestet. */
object Formatters {
    private val GERMAN = Locale.GERMANY
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN)
    private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", GERMAN)
    private val TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", GERMAN)

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

    /** Uhrzeit mit Sekunden „14:03:12“ (Transkript-Äußerungen). */
    fun timeWithSeconds(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME_SECONDS.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** „18.11.2026, 14:03“ */
    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Zeitraum „14:00–15:30“. */
    fun timeRange(startMs: Long, endMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${time(startMs, zone)}–${time(endMs, zone)}"

    /** Laufende Dauer als Stoppuhr „00:23:10“. */
    fun stopwatch(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        return String.format(GERMAN, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    /** Echtzeitfaktor „0,15×“. */
    fun realTimeFactor(rtf: Float): String = String.format(GERMAN, "%.2f×", rtf)

    /** Kurze Dauer mit einer Nachkommastelle: „3,2 s“. */
    fun seconds(ms: Long): String = String.format(GERMAN, "%.1f s", ms.coerceAtLeast(0) / 1000.0)

    /** Prozent „42 %“. */
    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()} %"

    /** Stundenbereich einer Aufnahmedatei: „14:00–15:00“. */
    fun hourRange(hourStartEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(hourStartEpochMs).atZone(zone)
        return "${TIME.format(start)}–${TIME.format(start.plusHours(1))}"
    }
}
