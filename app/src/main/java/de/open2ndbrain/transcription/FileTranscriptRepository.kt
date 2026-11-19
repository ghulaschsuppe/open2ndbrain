package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingRepository
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Transkripte als JSON neben den Audiodateien (siehe [TranscriptFiles]). Transkripte bleiben erhalten,
 * auch wenn das Audio gelöscht wird – [observeTranscript] funktioniert mit dem (nicht mehr existierenden)
 * Audiopfad weiter.
 */
class FileTranscriptRepository(
    private val audioRoot: File,
    private val recordings: RecordingRepository,
    private val refreshMs: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis,
) : TranscriptRepository {

    override fun observeTranscript(audio: File): Flow<Transcript?> = flow {
        while (true) {
            val seen = TranscriptFiles.version.value
            emit(TranscriptFiles.read(audio))
            withTimeoutOrNull(refreshMs) { TranscriptFiles.version.first { it != seen } }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun pending(): List<RecordingFile> {
        val all = recordings.observeRecordings().first()
        return withContext(Dispatchers.IO) {
            cleanupOrphanParts()
            all.asSequence()
                .filter { !it.inProgress && !it.audio.name.endsWith(".part") && it.audio.isFile }
                .filter { rf -> TranscriptFiles.readVersion(rf.audio).let { it == null || it < Transcript.CURRENT_VERSION } }
                .sortedWith(compareBy<RecordingFile> { it.hourStartEpochMs }.thenBy { it.audio.name })
                .toList()
        }
    }

    /** Abgebrochene Schreibvorgänge (`*.transcript.json.part` älter als 10 min) entfernen. */
    internal fun cleanupOrphanParts() {
        if (!audioRoot.isDirectory) return
        val cutoff = now() - ORPHAN_PART_AGE_MS
        audioRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(TranscriptFiles.SUFFIX + ".part") && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    override suspend fun delete(audio: File) {
        withContext(Dispatchers.IO) { TranscriptFiles.delete(audio) }
    }

    private companion object {
        const val ORPHAN_PART_AGE_MS = 10 * 60_000L
    }
}
