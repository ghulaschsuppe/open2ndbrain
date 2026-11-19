package de.open2ndbrain.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Prüft eine (evtl. abgebrochene) Ogg-Opus-Datei Seite für Seite (Kopf, Länge, CRC).
 * Grundlage der Wiederherstellung nach einem Absturz: Die Datei wird hinter der letzten
 * vollständigen Seite abgeschnitten und bleibt so abspielbar.
 */
internal object OggScanner {

    data class Result(
        /** Byte-Position hinter der letzten gültigen Seite. */
        val validLength: Long,
        val fileLength: Long,
        val pageCount: Int,
        /** Granule-Position der letzten gültigen Seite (Opus: 48-kHz-Samples). */
        val lastGranule: Long,
        /** Pre-Skip aus dem OpusHead (48-kHz-Samples), 0 wenn unbekannt. */
        val preSkip: Int,
        val isOpus: Boolean,
    ) {
        /** Enthält die Datei Audiodaten (nicht nur Kopfseiten)? */
        val hasAudio: Boolean get() = lastGranule > 0
        val needsTruncate: Boolean get() = validLength < fileLength

        /** Abspieldauer laut Granule (nur Opus sinnvoll). */
        val durationMs: Long
            get() = if (!isOpus || lastGranule <= 0) 0 else ((lastGranule - preSkip).coerceAtLeast(0) / 48)
    }

    private const val HEADER_LEN = 27
    private const val MAX_PAGE = HEADER_LEN + 255 + 255 * 255

    fun scan(file: File): Result = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        var pos = 0L
        var pages = 0
        var lastGranule = 0L
        var preSkip = 0
        var isOpus = false
        val page = ByteArray(MAX_PAGE)
        while (pos + HEADER_LEN <= len) {
            raf.seek(pos)
            raf.readFully(page, 0, HEADER_LEN)
            if (page[0] != 'O'.code.toByte() || page[1] != 'g'.code.toByte() ||
                page[2] != 'g'.code.toByte() || page[3] != 'S'.code.toByte() || page[4] != 0.toByte()
            ) break
            val nSeg = page[26].toInt() and 0xFF
            if (pos + HEADER_LEN + nSeg > len) break
            raf.readFully(page, HEADER_LEN, nSeg)
            var bodyLen = 0
            for (i in 0 until nSeg) bodyLen += page[HEADER_LEN + i].toInt() and 0xFF
            val total = HEADER_LEN + nSeg + bodyLen
            if (pos + total > len) break
            raf.readFully(page, HEADER_LEN + nSeg, bodyLen)
            val storedCrc = readLe32(page, 22)
            page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
            if (OggCrc.compute(page, 0, total) != storedCrc) break
            val granule = readLe64(page, 6)
            if (pages == 0) {
                val body = HEADER_LEN + nSeg
                if (bodyLen >= 19 && String(page, body, 8, Charsets.US_ASCII) == "OpusHead") {
                    isOpus = true
                    preSkip = (page[body + 10].toInt() and 0xFF) or ((page[body + 11].toInt() and 0xFF) shl 8)
                }
            }
            if (granule != -1L) lastGranule = granule // -1: Seite ohne abgeschlossenes Paket
            pages++
            pos += total
        }
        Result(pos, len, pages, lastGranule, preSkip, isOpus)
    }

    /** Schneidet die Datei auf [length] Bytes ab. */
    fun truncate(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(length) }
    }

    private fun readLe32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readLe64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}

/** CRC-32 der Ogg-Spezifikation (Polynom 0x04C11DB7, nicht reflektiert, Start 0, kein XOR am Ende). */
internal object OggCrc {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}
