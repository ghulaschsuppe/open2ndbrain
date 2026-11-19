package de.open2ndbrain.transcription

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingRepository
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** PLATZHALTER – wird vom ML-Agenten implementiert. */
class FileTranscriptRepository(
    @Suppress("unused") private val audioRoot: File,
    @Suppress("unused") private val recordings: RecordingRepository,
) : TranscriptRepository {
    override fun observeTranscript(audio: File): Flow<Transcript?> = flowOf(null)
    override suspend fun pending(): List<RecordingFile> = emptyList()
    override suspend fun delete(audio: File) = Unit
}
