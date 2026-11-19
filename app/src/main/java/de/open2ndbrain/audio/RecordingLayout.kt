package de.open2ndbrain.audio

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Reine Zeit- und Namenslogik der Ablage (siehe [de.open2ndbrain.core.RecordingSidecar]):
 * `<root>/YYYY/MM/DD/YYYY-MM-DD_HH-00[_b|_c|…].<ext>[.part]`.
 *
 * Stunden sind an der lokalen Uhrzeit ausgerichtet. Bei der Sommerzeit-Rückstellung kommt
 * dieselbe lokale Stunde zweimal vor → gleicher Basisname, die zweite Stunde bekommt automatisch
 * ein Teildatei-Suffix (siehe [RecordingLayout.allocateBaseName]).
 */
internal object RecordingLayout {

    const val EXT_OGG = "ogg"
    const val EXT_M4A = "m4a"
    const val EXT_JSON = "json"
    const val PART = ".part"
    val AUDIO_EXTENSIONS = listOf(EXT_OGG, EXT_M4A)

    private val BASE_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})_(\d{2})-00(?:_([a-z]+))?$""")

    /** Beginn der lokalen Stunde, in der [epochMs] liegt (epoch ms). */
    fun hourStart(epochMs: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
            .truncatedTo(ChronoUnit.HOURS)
            .toInstant().toEpochMilli()

    /**
     * Nächste lokale Stundengrenze nach [hourStartMs] (epoch ms). Rechnet auf der Zeitachse,
     * daher ist jede Stunde echte 60 min lang – auch bei Sommerzeitumstellungen.
     */
    fun nextHourStart(hourStartMs: Long, zone: ZoneId): Long {
        val next = hourStartMs + HOUR_MS
        // Zonen mit halbstündigem Versatzwechsel: auf die nächste lokale volle Stunde ausrichten.
        return hourStart(next, zone).let { if (it > hourStartMs) it else next }
    }

    /** Lokales Datum der Stunde (bestimmt den Tagesordner). */
    fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Tagesordner `<root>/YYYY/MM/DD`. */
    fun dayDir(root: File, date: LocalDate): File =
        File(root, "%04d/%02d/%02d".format(date.year, date.monthValue, date.dayOfMonth))

    fun dayDir(root: File, epochMs: Long, zone: ZoneId): File = dayDir(root, localDate(epochMs, zone))

    /** Suffix der n-ten Teildatei: 0 → "", 1 → "_b", 25 → "_z", 26 → "_aa", 27 → "_ab" … */
    fun partSuffix(index: Int): String {
        require(index >= 0)
        if (index == 0) return ""
        var n = index + 1 // 1-basiert wie Tabellenspalten: 2 = b
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('a' + rem)
            n = (n - 1) / 26
        }
        return "_" + sb.reverse().toString()
    }

    /** Umkehrung von [partSuffix] (ohne Unterstrich); null bei ungültigem Suffix. */
    fun partIndex(suffix: String?): Int? {
        if (suffix.isNullOrEmpty()) return 0
        var n = 0
        for (c in suffix) {
            if (c !in 'a'..'z') return null
            n = n * 26 + (c - 'a' + 1)
        }
        return if (n >= 2) n - 1 else null // "a" wird nie vergeben
    }

    /** Basisname ohne Endung, z. B. `2026-11-19_14-00_b`. */
    fun baseName(hourStartMs: Long, zone: ZoneId, partIndex: Int): String {
        val t = Instant.ofEpochMilli(hourStartMs).atZone(zone)
        return "%04d-%02d-%02d_%02d-00%s".format(
            t.year, t.monthValue, t.dayOfMonth, t.hour, partSuffix(partIndex),
        )
    }

    data class ParsedName(val localHour: LocalDateTime, val partIndex: Int)

    /** Zerlegt einen Basisnamen (ohne Endung). */
    fun parseBaseName(base: String): ParsedName? {
        val m = BASE_REGEX.matchEntire(base) ?: return null
        val (y, mo, d, h) = m.destructured
        val idx = partIndex(m.groupValues[5].ifEmpty { null }) ?: return null
        return runCatching {
            ParsedName(LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), 0), idx)
        }.getOrNull()
    }

    /** Stundenbeginn aus einem Basisnamen (bei doppelter Stunde: die frühere Variante). */
    fun hourStartFromBaseName(base: String, zone: ZoneId): Long? =
        parseBaseName(base)?.localHour?.atZone(zone)?.toInstant()?.toEpochMilli()

    /** Zerlegt einen Dateinamen in Basisname + Endung (ohne `.part`) + part-Flag. */
    data class FileName(val base: String, val ext: String, val part: Boolean)

    fun splitFileName(name: String): FileName? {
        val part = name.endsWith(PART)
        val n = if (part) name.removeSuffix(PART) else name
        val dot = n.lastIndexOf('.')
        if (dot <= 0) return null
        return FileName(n.substring(0, dot), n.substring(dot + 1), part)
    }

    /** Alle Dateien, die zu einem Basisnamen gehören können. */
    fun relatedFiles(dir: File, base: String): List<File> =
        (AUDIO_EXTENSIONS + EXT_JSON).flatMap { ext ->
            listOf(File(dir, "$base.$ext$PART"), File(dir, "$base.$ext"))
        }

    /** Transkript zum Basisnamen (Phase 2). Wird nie zusammen mit dem Audio gelöscht. */
    const val TRANSCRIPT_SUFFIX = ".transcript.json"

    fun isTranscriptFile(name: String): Boolean = name.contains(".transcript.")

    /**
     * Ist der Basisname schon belegt? `.part` zuerst prüfen (Umbenennen läuft part → final).
     * Ein übrig gebliebenes Transkript belegt den Namen ebenfalls (sonst bekäme neues Audio ein fremdes Transkript).
     */
    fun isTaken(dir: File, base: String): Boolean =
        relatedFiles(dir, base).any { it.exists() } || File(dir, base + TRANSCRIPT_SUFFIX).exists()

    /**
     * Ersten freien Basisnamen der Stunde finden (`…_HH-00`, dann `_b`, `_c` …).
     * [reserved] enthält Namen, die gerade von dieser Engine vergeben, aber evtl. noch nicht angelegt sind.
     */
    fun allocateBaseName(
        dir: File,
        hourStartMs: Long,
        zone: ZoneId,
        reserved: Set<String> = emptySet(),
    ): String {
        var i = 0
        while (true) {
            val base = baseName(hourStartMs, zone, i)
            if (base !in reserved && !isTaken(dir, base)) return base
            i++
        }
    }

    const val HOUR_MS = 3_600_000L
}
