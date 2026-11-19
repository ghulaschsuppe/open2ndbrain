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
    @Suppress("unused") private val audioRoot: File,
    private val recordings: RecordingRepository,
    private val refreshMs: Long = 10_000,
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
            all.asSequence()
                .filter { !it.inProgress && !it.audio.name.endsWith(".part") && it.audio.isFile }
                .filter { rf -> TranscriptFiles.readVersion(rf.audio).let { it == null || it < Transcript.CURRENT_VERSION } }
                .sortedWith(compareBy<RecordingFile> { it.hourStartEpochMs }.thenBy { it.audio.name })
                .toList()
        }
    }

    override suspend fun delete(audio: File) {
        withContext(Dispatchers.IO) { TranscriptFiles.delete(audio) }
    }
}
