package de.open2ndbrain.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Transkript einer Aufnahmedatei. Liegt als `<Audiodatei ohne Endung>.transcript.json` neben der Audiodatei
 * (atomar geschrieben über `.part`). Es ist die Quelle für den Suchindex in Phase 3.
 */
@Serializable
data class Transcript(
    val version: Int = CURRENT_VERSION,
    /** Dateiname der Audiodatei (ohne Pfad). */
    val audioFileName: String,
    val asrModelId: String,
    /** null = ohne Sprechererkennung erstellt. */
    val speakerModelId: String? = null,
    val createdAtEpochMs: Long,
    /** Gespeicherte Audiodauer der Datei. */
    val audioDurationMs: Long,
    /** Rechenzeit (für die Anzeige des Echtzeitfaktors). */
    val processingMs: Long,
    val utterances: List<Utterance>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Eine Äußerung: ein Satz bzw. zusammenhängender Redebeitrag eines Sprechers. */
@Serializable
data class Utterance(
    /** Position in der Audiodatei (ms) – zum Abspielen. */
    val fileOffsetMs: Long,
    val durationMs: Long,
    /** Wanduhrzeit (epoch ms) – für Zeitleiste und Suche. Über die Sidecar-Segmente berechnet. */
    val startEpochMs: Long,
    val text: String,
    /** Globale Sprecher-ID aus der [SpeakerRepository] (null = unbekannt/ohne Sprechererkennung). */
    val speakerId: String? = null,
)

/** Eine wiedererkannte Stimme, dateiübergreifend. Gespeichert in `filesDir/speakers/speakers.json`. */
@Serializable
data class SpeakerProfile(
    val id: String,
    /** Vom Nutzer vergeben; null → Anzeige „Sprecher <Nummer>“. */
    val name: String? = null,
    val number: Int,
    val isMe: Boolean = false,
    /** Normierter Mittelwert der Stimmabdrücke (Embedding). */
    val embedding: List<Float>,
    val sampleCount: Int,
    val totalSpeechMs: Long,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    /** IDs, die in diesen Sprecher zusammengeführt wurden (Transkripte werden nicht umgeschrieben). */
    val aliases: List<String> = emptyList(),
)

interface SpeakerRepository {
    fun observeSpeakers(): Flow<List<SpeakerProfile>>

    /** Löst eine in einem Transkript gespeicherte ID auf (inkl. Aliasse nach Zusammenführen). */
    suspend fun resolve(speakerId: String): SpeakerProfile?

    suspend fun rename(id: String, name: String?)

    /** Markiert genau einen Sprecher als „Ich“. */
    suspend fun setMe(id: String)

    /** Führt [fromId] in [intoId] zusammen (Embeddings gewichtet gemittelt, [fromId] wird Alias). */
    suspend fun merge(fromId: String, intoId: String)

    suspend fun delete(id: String)
}

interface TranscriptRepository {
    /** Transkript zur Audiodatei; aktualisiert sich, sobald es geschrieben wird. */
    fun observeTranscript(audio: File): Flow<Transcript?>

    /** Abgeschlossene Aufnahmen ohne (aktuelles) Transkript, älteste zuerst. */
    suspend fun pending(): List<RecordingFile>

    /** Transkript löschen (z. B. um neu zu transkribieren). */
    suspend fun delete(audio: File)
}

/** Einstellungen für einen Transkriptionslauf. */
data class TranscriberConfig(
    val asrModelDir: File,
    val vadModelFile: File,
    /** null → keine Sprechererkennung. */
    val speakerModelFile: File?,
    val numThreads: Int = 4,
)

/**
 * Transkribiert Aufnahmedateien. Hält die Modelle geladen, bis [close] aufgerufen wird (~1 GB RAM).
 * Nicht thread-sicher: ein Lauf zur Zeit. Blockierend – nur aus einem Hintergrund-Thread/Worker aufrufen.
 */
interface Transcriber : AutoCloseable {
    /** Transkribiert, schreibt das Transkript-JSON, aktualisiert die Sprecher und gibt es zurück. */
    fun transcribe(recording: RecordingFile, onProgress: (Float) -> Unit = {}): Transcript

    override fun close()
}

/** Status der Transkriptions-Warteschlange für UI und Benachrichtigung. */
data class TranscriptionStatus(
    val running: Boolean = false,
    val currentFile: String? = null,
    val progress: Float = 0f,
    val pendingCount: Int = 0,
    /** Echtzeitfaktor des letzten Laufs (Rechenzeit / Audiodauer). */
    val lastRealTimeFactor: Float? = null,
    val lastError: String? = null,
    /** Warum gerade nicht transkribiert wird, z. B. „Wartet auf Ladegerät“ oder „Modell fehlt“. */
    val waitingReason: String? = null,
)

/** Gemeinsamer Halter, damit Worker und UI denselben Status sehen. */
interface TranscriptionStatusHolder {
    val status: StateFlow<TranscriptionStatus>
}
