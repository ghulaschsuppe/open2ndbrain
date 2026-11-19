package de.open2ndbrain.markers

import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.Utterance

/** Reine Logik rund um Markierungen (unit-getestet). */
object MarkerLogic {
    const val HOUR_MS = 3_600_000L
    /** Ab dieser Dauer fragt die App nach, ob das Meeting noch läuft. */
    const val LONG_MEETING_MS = 8 * HOUR_MS

    fun activeMeeting(markers: List<Marker>): Marker? =
        markers.filter { it.type == MarkerType.Meeting && it.endEpochMs == null }.maxByOrNull { it.startEpochMs }

    /** Wanduhr-Zeitraum einer Aufnahmedatei: aus den Sidecar-Segmenten, sonst die ganze Stunde. */
    fun fileRange(recording: RecordingFile): LongRange {
        val segs = recording.sidecar?.segments.orEmpty()
        if (segs.isEmpty()) return recording.hourStartEpochMs until recording.hourStartEpochMs + HOUR_MS
        val start = segs.minOf { it.startEpochMs }
        val end = segs.maxOf { it.startEpochMs + it.durationMs }
        return start until maxOf(end, start + 1)
    }

    /** Zeitraum einer Markierung; ein laufendes Meeting reicht bis [now]. */
    fun markerRange(marker: Marker, now: Long): LongRange {
        val end = marker.endEpochMs ?: now
        return marker.startEpochMs until maxOf(end, marker.startEpochMs + 1)
    }

    fun overlaps(a: LongRange, b: LongRange): Boolean = a.first < b.last + 1 && b.first < a.last + 1

    fun overlaps(marker: Marker, recording: RecordingFile, now: Long): Boolean =
        overlaps(markerRange(marker, now), fileRange(recording))

    /**
     * Reihenfolge der Transkription: Dateien, die ein **beendetes** Meeting überlappen, zuerst (älteste zuerst),
     * danach der Rest in der bisherigen Reihenfolge (älteste zuerst).
     */
    fun prioritize(pending: List<RecordingFile>, markers: List<Marker>, now: Long): List<RecordingFile> {
        val ended = markers.filter { it.type == MarkerType.Meeting && it.endEpochMs != null }
        if (ended.isEmpty()) return pending
        val (meeting, rest) = pending.partition { rec -> ended.any { overlaps(it, rec, now) } }
        return meeting + rest
    }

    /** Äußerung (Wanduhr) innerhalb des Zeitraums? Maßgeblich ist der Beginn der Äußerung. */
    fun inRange(u: Utterance, range: LongRange): Boolean = u.startEpochMs in range

    /** Eine Äußerung samt Datei, aus der sie stammt (für dateiübergreifende Meeting-Ansichten). */
    data class LocatedUtterance(val audioFileName: String, val utterance: Utterance)

    /** Alle Äußerungen aus [transcripts] im Zeitraum, zeitlich sortiert. */
    fun utterancesIn(range: LongRange, transcripts: List<Transcript>): List<LocatedUtterance> =
        transcripts.flatMap { t -> t.utterances.filter { inRange(it, range) }.map { LocatedUtterance(t.audioFileName, it) } }
            .sortedBy { it.utterance.startEpochMs }

    /** Lesezeichen, deren Zeitpunkt (Tippen) in der Datei liegt. */
    fun bookmarksIn(recording: RecordingFile, markers: List<Marker>): List<Marker> {
        val range = fileRange(recording)
        return markers.filter { it.type == MarkerType.Bookmark && (it.endEpochMs ?: it.startEpochMs) in range }
    }

    /** Lesezeichen „gehört“ zur Äußerung, wenn sein Rückblick-Zeitraum sie überlappt. */
    fun bookmarkHits(u: Utterance, bookmark: Marker): Boolean {
        val uRange = u.startEpochMs until u.startEpochMs + maxOf(u.durationMs, 1)
        return overlaps(uRange, markerRange(bookmark, bookmark.endEpochMs ?: bookmark.startEpochMs))
    }

    /** Lange laufendes Meeting? (Hinweis, kein automatisches Ende.) */
    fun isLongRunning(meeting: Marker?, now: Long): Boolean =
        meeting != null && meeting.endEpochMs == null && now - meeting.startEpochMs >= LONG_MEETING_MS
}
