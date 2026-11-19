package de.open2ndbrain.core

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Ablage auf dem Datenträger (festgelegt, damit Engine, UI und spätere Transkription übereinstimmen):
 *
 *   <outputRoot>/YYYY/MM/DD/YYYY-MM-DD_HH-00.ogg        erste Datei der Stunde
 *   <outputRoot>/YYYY/MM/DD/YYYY-MM-DD_HH-00_b.ogg      weitere Teildateien (nach Pause/Neustart): _b, _c, …
 *   <gleicher Name>.json                                Sidecar ([RecordingSidecar])
 *   <gleicher Name>.ogg.part / .json.part               während des Schreibens; nach Abschluss umbenannt
 *
 * Zeiten sind lokale Zeit des Geräts für die Dateinamen, epoch ms in den Sidecars.
 */
@Serializable
data class RecordingSidecar(
    val version: Int = 1,
    /** Beginn der Stunde (epoch ms), zu der die Datei gehört. */
    val hourStartEpochMs: Long,
    val codec: String = "opus",
    val container: String = "ogg",
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val bitrate: Int,
    val discardSilence: Boolean,
    /**
     * Abbildung Dateizeit → Wanduhrzeit. Ohne Stille-Verwerfen gibt es i. d. R. einen Abschnitt.
     * Mit Stille-Verwerfen ist jeder gespeicherte Sprachblock (inkl. Vor-/Nachlauf) ein Abschnitt.
     */
    val segments: List<Segment> = emptyList(),
    val events: List<Event> = emptyList(),
    /** true, wenn die Datei sauber abgeschlossen wurde (false = nach Absturz wiederhergestellt). */
    val complete: Boolean = false,
) {
    @Serializable
    data class Segment(
        /** Position in der Audiodatei (ms ab Dateibeginn). */
        val fileOffsetMs: Long,
        /** Wanduhrzeit (epoch ms) des Abschnittsbeginns. */
        val startEpochMs: Long,
        val durationMs: Long,
        /** Name des Mikrofons, das diesen Abschnitt aufgenommen hat. */
        val input: String? = null,
    )

    @Serializable
    data class Event(
        val atEpochMs: Long,
        /** z. B. "mic_changed", "paused", "resumed", "mic_busy", "mic_free", "error". */
        val type: String,
        val detail: String? = null,
    )
}

/** Eine abgeschlossene oder laufende Aufnahmedatei, wie sie die UI anzeigt. */
data class RecordingFile(
    val audio: File,
    val sidecar: RecordingSidecar?,
    val hourStartEpochMs: Long,
    /** Gespeicherte Audiodauer (Summe der Segmente). */
    val durationMs: Long,
    val sizeBytes: Long,
    val inProgress: Boolean,
)

interface RecordingRepository {
    /** Alle Aufnahmen, neueste zuerst. Aktualisiert sich, wenn Dateien hinzukommen/abgeschlossen werden. */
    fun observeRecordings(): Flow<List<RecordingFile>>

    /** Belegter Speicher aller Aufnahmen in Bytes. */
    suspend fun totalSizeBytes(): Long

    /** Löscht Audiodatei + Sidecar. */
    suspend fun delete(recording: RecordingFile)

    /**
     * Löscht abgeschlossene Aufnahmen, deren Stunde älter als [days] Tage ist. Rückgabe: Anzahl gelöschter Dateien.
     * [days] <= 0 bedeutet „unbegrenzt aufbewahren“: es wird nichts gelöscht (Rückgabe 0).
     */
    suspend fun deleteOlderThan(days: Int): Int
}
