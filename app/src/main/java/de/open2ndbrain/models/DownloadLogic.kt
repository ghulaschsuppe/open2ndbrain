package de.open2ndbrain.models

import de.open2ndbrain.core.ModelFile
import de.open2ndbrain.core.ModelSpec
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** Reine Logik für Downloads mit Fortsetzung, Weiterleitungen und Prüfsummen (ohne Android, unit-getestet). */
object DownloadLogic {

    /** `Content-Range: bytes <start>-<end>/<total|*>` */
    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private val CONTENT_RANGE = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE)

    fun parseContentRange(header: String?): ContentRange? {
        val m = CONTENT_RANGE.matchEntire(header ?: return null) ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        val end = m.groupValues[2].toLongOrNull() ?: return null
        if (end < start) return null
        val total = m.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return ContentRange(start, end, total)
    }

    /** Wie geht es mit einer vorhandenen `.part`-Datei weiter? */
    sealed interface ResumePlan {
        /** Datei hat bereits die erwartete Größe → nur noch prüfen. */
        data object VerifyOnly : ResumePlan

        /** Ab Byte [from] anfordern (0 = von vorn). [truncateFirst]: vorhandene Datei vorher leeren. */
        data class Fetch(val from: Long, val truncateFirst: Boolean) : ResumePlan
    }

    fun planResume(existingBytes: Long, expectedBytes: Long): ResumePlan = when {
        existingBytes == expectedBytes && expectedBytes > 0 -> ResumePlan.VerifyOnly
        existingBytes > expectedBytes || existingBytes < 0 -> ResumePlan.Fetch(0, truncateFirst = true)
        else -> ResumePlan.Fetch(existingBytes, truncateFirst = false)
    }

    /** Was tun mit der Antwort des Servers? */
    sealed interface ResponseAction {
        /** An die vorhandene Datei anhängen (206 mit passendem Startbyte). */
        data object Append : ResponseAction

        /** Server liefert die ganze Datei (200, Range ignoriert) → von vorn schreiben. */
        data object Overwrite : ResponseAction

        /** 416: angeforderter Bereich liegt hinter dem Ende → Datei ist (vermutlich) vollständig. */
        data object AlreadyComplete : ResponseAction

        /** Antwort passt nicht zur vorhandenen Datei → verwerfen und von vorn beginnen. */
        data object RestartFromZero : ResponseAction

        data class Fail(val httpCode: Int) : ResponseAction
    }

    fun interpretResponse(httpCode: Int, requestedFrom: Long, contentRange: ContentRange?, expectedBytes: Long): ResponseAction =
        when (httpCode) {
            200 -> ResponseAction.Overwrite
            206 -> when {
                contentRange == null -> ResponseAction.RestartFromZero
                contentRange.start != requestedFrom -> ResponseAction.RestartFromZero
                contentRange.total != null && contentRange.total != expectedBytes -> ResponseAction.Fail(httpCode)
                else -> ResponseAction.Append
            }
            416 -> if (requestedFrom >= expectedBytes) ResponseAction.AlreadyComplete else ResponseAction.RestartFromZero
            else -> ResponseAction.Fail(httpCode)
        }

    fun isRedirect(code: Int) = code in setOf(301, 302, 303, 307, 308)

    /** Löst eine Weiterleitung auf; nur HTTPS ist erlaubt. */
    fun resolveRedirect(current: URI, location: String?): URI {
        if (location.isNullOrBlank()) throw IOException("Weiterleitung ohne Ziel")
        val next = current.resolve(location.trim())
        requireHttps(next)
        return next
    }

    fun requireHttps(uri: URI) {
        if (!uri.scheme.equals("https", ignoreCase = true)) throw IOException("Nur HTTPS erlaubt: $uri")
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return toHex(md.digest())
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Zuordnung einer importierten Datei zu einer Modelldatei. */
    sealed interface ImportMatch {
        data class Matched(val spec: ModelSpec, val file: ModelFile) : ImportMatch

        /** Name passt zu einer Modelldatei, Inhalt aber nicht (falsche Version/beschädigt). */
        data class WrongChecksum(val spec: ModelSpec, val file: ModelFile) : ImportMatch

        data object Unknown : ImportMatch
    }

    /** Vorrang hat die Prüfsumme (Dateiname kann umbenannt sein), dann der Dateiname. */
    fun matchImport(displayName: String?, sha256: String, catalog: List<ModelSpec>): ImportMatch {
        for (spec in catalog) for (f in spec.files) if (f.sha256.equals(sha256, ignoreCase = true)) return ImportMatch.Matched(spec, f)
        val name = displayName?.trim() ?: return ImportMatch.Unknown
        for (spec in catalog) for (f in spec.files) if (f.name == name) return ImportMatch.WrongChecksum(spec, f)
        return ImportMatch.Unknown
    }

    /** Fortschritt eines Modells: fertige Dateien + aktueller Stand der laufenden Datei. */
    fun overallProgress(spec: ModelSpec, verified: Set<String>, currentFile: String?, currentBytes: Long): Long =
        spec.files.sumOf { f ->
            when {
                f.name in verified -> f.sizeBytes
                f.name == currentFile -> currentBytes.coerceIn(0, f.sizeBytes)
                else -> 0L
            }
        }
}
