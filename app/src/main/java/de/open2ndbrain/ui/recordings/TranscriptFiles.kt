package de.open2ndbrain.ui.recordings

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptionStatus
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Status einer Aufnahme in der Liste. */
enum class TranscriptBadge { None, Waiting, Transcribing, Done, Failed }

/**
 * Transkript-Dateien laut Vertrag: `<Basisname der Audiodatei>.transcript.json` neben der Audiodatei.
 * Sie bleiben erhalten, wenn das Audio gelöscht wird – dann zeigen wir das Transkript trotzdem an.
 */
object TranscriptFiles {
    const val SUFFIX = ".transcript.json"
    private val JSON = Json { ignoreUnknownKeys = true }
    private val BASE = Regex("""^(\d{4})-(\d{2})-(\d{2})_(\d{2})-00(?:_[a-z]+)?$""")

    /** Basisname ohne Endung und ohne `.part`: `2026-11-19_14-00_b.ogg.part` → `2026-11-19_14-00_b`. */
    fun baseName(audioName: String): String {
        val n = audioName.removeSuffix(".part")
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    fun transcriptFileFor(audio: File): File = File(audio.parentFile, baseName(audio.name) + SUFFIX)

    /** Stundenbeginn aus dem Basisnamen (lokale Zeit), null wenn nicht im erwarteten Format. */
    fun hourStartFromBase(base: String, zone: ZoneId): Long? {
        val m = BASE.matchEntire(base) ?: return null
        val (y, mo, d, h) = m.destructured
        return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), 0).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    fun decode(file: File): Transcript? =
        runCatching { JSON.decodeFromString(Transcript.serializer(), file.readText()) }.getOrNull()

    /** Alle Transkript-Dateien unterhalb von [root]. */
    fun listAll(root: File): List<File> =
        if (!root.isDirectory) emptyList() else root.walkTopDown().filter { it.isFile && it.name.endsWith(SUFFIX) }.toList()

    /**
     * Transkripte ohne Audiodatei als Platzhalter-Aufnahmen (Größe 0), damit sie in der Liste erscheinen.
     * [knownBases] = Basisnamen der vorhandenen Aufnahmen.
     */
    fun orphans(transcriptFiles: List<File>, knownBases: Set<String>, zone: ZoneId): List<RecordingFile> =
        transcriptFiles.mapNotNull { tf ->
            val base = tf.name.removeSuffix(SUFFIX)
            if (base in knownBases) return@mapNotNull null
            val t = decode(tf) ?: return@mapNotNull null
            val hour = hourStartFromBase(base, zone) ?: t.utterances.firstOrNull()?.startEpochMs ?: return@mapNotNull null
            RecordingFile(
                audio = File(tf.parentFile, t.audioFileName),
                sidecar = null,
                hourStartEpochMs = hour,
                durationMs = t.audioDurationMs,
                sizeBytes = 0,
                inProgress = false,
            )
        }

    fun badge(
        recording: RecordingFile,
        hasTranscript: Boolean,
        status: TranscriptionStatus,
        failedPermanently: Boolean,
        transcriptionOff: Boolean,
    ): TranscriptBadge = when {
        recording.inProgress -> TranscriptBadge.None
        status.running && status.currentFile == recording.audio.name -> TranscriptBadge.Transcribing
        hasTranscript -> TranscriptBadge.Done
        failedPermanently -> TranscriptBadge.Failed
        transcriptionOff -> TranscriptBadge.None
        else -> TranscriptBadge.Waiting
    }
}
