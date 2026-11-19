package de.open2ndbrain.speakers

import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.SpeakerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** PLATZHALTER – wird vom ML-Agenten implementiert. */
class FileSpeakerRepository(@Suppress("unused") private val dir: File) : SpeakerRepository {
    override fun observeSpeakers(): Flow<List<SpeakerProfile>> = flowOf(emptyList())
    override suspend fun resolve(speakerId: String): SpeakerProfile? = null
    override suspend fun rename(id: String, name: String?) = Unit
    override suspend fun setMe(id: String) = Unit
    override suspend fun merge(fromId: String, intoId: String) = Unit
    override suspend fun delete(id: String) = Unit
}
