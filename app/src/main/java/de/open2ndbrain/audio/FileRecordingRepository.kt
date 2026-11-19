package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** PLATZHALTER – wird vom Audio-Agenten implementiert. */
class FileRecordingRepository(@Suppress("unused") private val root: File) : RecordingRepository {
    override fun observeRecordings(): Flow<List<RecordingFile>> = flowOf(emptyList())
    override suspend fun totalSizeBytes(): Long = 0
    override suspend fun delete(recording: RecordingFile) = Unit
    override suspend fun deleteOlderThan(days: Int): Int = 0
}
