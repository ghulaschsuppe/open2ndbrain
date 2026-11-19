package de.open2ndbrain.audio

import android.util.Log
import de.open2ndbrain.core.RecordingSidecar
import java.io.File
import java.time.ZoneId

/**
 * Wiederherstellung verwaister `.part`-Dateien nach Absturz/Prozessende.
 *
 * Vorgehen je Basisname (pragmatisch):
 * - `.ogg.part`: Seiten prüfen, hinter der letzten gültigen Seite abschneiden (Reparatur) und in
 *   `.ogg` umbenennen. Enthält sie kein Audio (nur Kopfseiten/leer) → Audio + Sidecar löschen.
 * - `.m4a.part` (AAC-Fallback): ohne abschließendes `moov` meist nicht abspielbar; wird trotzdem als
 *   `.m4a` mit `complete=false` behalten (nur leere Dateien werden gelöscht).
 * - Sidecar: `.json.part` übernehmen (sonst aus dem Dateinamen neu anlegen). War die Datei nicht
 *   sauber abgeschlossen, wird die tatsächliche OGG-Dauer mit den Segmenten abgeglichen (der letzte
 *   Abschnitt wird verlängert) und ein Ereignis `recovered` ergänzt; `complete` bleibt false.
 * - Verwaiste `.tmp`-Dateien werden gelöscht.
 *
 * Dateien in [skip] (gerade aktiv geschrieben) werden nicht angefasst.
 */
internal object PartFileRecovery {
    private const val TAG = "O2B-Audio"

    /** Liefert die Anzahl bearbeiteter Basisnamen. */
    fun recover(root: File, zone: ZoneId, skip: Set<String>, nowMs: Long = System.currentTimeMillis()): Int {
        if (!root.isDirectory) return 0
        var count = 0
        root.walkTopDown().filter { it.isDirectory }.toList().forEach { dir ->
            val files = dir.listFiles() ?: return@forEach
            val bases = LinkedHashSet<String>()
            for (f in files) {
                if (f.name.endsWith(".tmp")) {
                    if (f.absolutePath !in skip) f.delete()
                    continue
                }
                val fn = RecordingLayout.splitFileName(f.name) ?: continue
                if (fn.part && f.absolutePath !in skip) bases += fn.base
            }
            for (base in bases) {
                if (RecordingLayout.relatedFiles(dir, base).any { it.absolutePath in skip }) continue
                runCatching { recoverBase(dir, base, zone, nowMs) }
                    .onSuccess { count++ }
                    .onFailure { Log.e(TAG, "Wiederherstellung von $base fehlgeschlagen", it) }
            }
        }
        return count
    }

    private fun recoverBase(dir: File, base: String, zone: ZoneId, nowMs: Long) {
        val jsonPart = File(dir, "$base.json${RecordingLayout.PART}")
        val jsonFinal = File(dir, "$base.json")
        val partSidecar = SidecarIo.readOrNull(jsonPart)

        var format: AudioFileFormat? = null
        var audioFinal: File? = null
        var oggDurationMs: Long? = null
        for (fmt in AudioFileFormat.entries) {
            val part = File(dir, "$base.${fmt.extension}${RecordingLayout.PART}")
            val fin = File(dir, "$base.${fmt.extension}")
            if (part.exists()) {
                format = fmt
                if (fmt == AudioFileFormat.OPUS_OGG) {
                    val r = OggScanner.scan(part)
                    if (!r.hasAudio) {
                        Log.i(TAG, "Leere Teildatei ${part.name} gelöscht")
                        part.delete()
                        continue
                    }
                    if (r.needsTruncate) {
                        Log.w(TAG, "${part.name}: ${r.fileLength - r.validLength} Byte hinter letzter Seite abgeschnitten")
                        OggScanner.truncate(part, r.validLength)
                    }
                    oggDurationMs = r.durationMs
                } else if (part.length() == 0L) {
                    part.delete()
                    continue
                }
                if (!part.renameTo(fin)) Log.w(TAG, "Umbenennen fehlgeschlagen: ${part.name}")
                audioFinal = fin
            } else if (fin.exists()) {
                format = fmt
                audioFinal = fin
            }
        }

        if (audioFinal == null || !audioFinal.exists()) {
            // Kein Audio (mehr) → Sidecar-Reste entfernen.
            jsonPart.delete()
            if (!jsonFinal.exists() || SidecarIo.readOrNull(jsonFinal) == null) jsonFinal.delete()
            return
        }
        if (!jsonPart.exists() && jsonFinal.exists()) return // nichts zu tun

        val hourStart = RecordingLayout.hourStartFromBaseName(base, zone) ?: audioFinal.lastModified()
        var sidecar = partSidecar ?: RecordingSidecar(
            hourStartEpochMs = hourStart,
            codec = format!!.codec,
            container = format.container,
            bitrate = 0, // unbekannt
            discardSilence = true,
        )
        if (!sidecar.complete) {
            sidecar = reconcile(sidecar, oggDurationMs, audioFinal.lastModified(), nowMs)
        }
        SidecarIo.writeAtomic(jsonFinal, sidecar)
        jsonPart.delete()
        Log.i(TAG, "Wiederhergestellt: ${audioFinal.name} (complete=${sidecar.complete})")
    }

    /**
     * Gleicht die Segmente mit der tatsächlichen Audiodauer ab (Sidecar wird nur ~1×/min geschrieben).
     * Fehlendes Audio wird dem letzten Segment zugeschlagen (Wanduhrzeiten dahinter sind dann
     * ungefähr); gibt es keins, entsteht eines, das an der Dateiänderungszeit endet.
     */
    fun reconcile(sidecar: RecordingSidecar, audioDurationMs: Long?, lastModifiedMs: Long, nowMs: Long): RecordingSidecar {
        val segs = sidecar.segments.toMutableList()
        val known = segs.lastOrNull()?.let { it.fileOffsetMs + it.durationMs } ?: 0L
        var detail = "nach Abbruch wiederhergestellt"
        if (audioDurationMs != null && audioDurationMs > known) {
            val missing = audioDurationMs - known
            if (segs.isNotEmpty()) {
                val last = segs.removeAt(segs.lastIndex)
                segs += last.copy(durationMs = last.durationMs + missing)
            } else {
                segs += RecordingSidecar.Segment(
                    fileOffsetMs = 0,
                    startEpochMs = (lastModifiedMs - audioDurationMs).coerceAtLeast(sidecar.hourStartEpochMs),
                    durationMs = audioDurationMs,
                )
            }
            detail += "; ${missing} ms Audio ohne Sidecar-Eintrag dem letzten Abschnitt zugeordnet"
        }
        return sidecar.copy(
            segments = segs,
            events = sidecar.events + RecordingSidecar.Event(nowMs, "recovered", detail),
            complete = false,
        )
    }
}
