package de.open2ndbrain.speakers

import android.util.Log
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.SpeakerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Sprecherprofile in `<dir>/speakers.json` (atomar über `.tmp` + rename). Alle Änderungen laufen unter
 * einem Mutex; [observeSpeakers] liefert den aktuellen Stand als Flow. Implementiert zusätzlich
 * [SpeakerMatcher] für den Transcriber.
 */
class FileSpeakerRepository(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : SpeakerRepository, SpeakerMatcher {

    @Serializable
    internal data class Store(
        val version: Int = 1,
        /** Nächste Nummer für „Sprecher N“ (wird nach Löschen nicht wiederverwendet). */
        val nextNumber: Int = 1,
        val speakers: List<SpeakerProfile> = emptyList(),
    )

    private val file = File(dir, "speakers.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow<Store?>(null)

    override fun observeSpeakers(): Flow<List<SpeakerProfile>> = flow {
        ensureLoaded()
        emitAll(state.asSpeakers())
    }

    private fun MutableStateFlow<Store?>.asSpeakers(): Flow<List<SpeakerProfile>> = flow {
        collect { s -> if (s != null) emit(s.speakers.sortedBy { it.number }) }
    }

    override suspend fun resolve(speakerId: String): SpeakerProfile? =
        ensureLoaded().speakers.firstOrNull { it.id == speakerId || speakerId in it.aliases }

    override suspend fun rename(id: String, name: String?) = edit { s ->
        s.copy(speakers = s.speakers.map { if (it.id == id) it.copy(name = name?.trim()?.ifEmpty { null }) else it })
    }

    override suspend fun setMe(id: String) = edit { s ->
        if (s.speakers.none { it.id == id }) s
        else s.copy(speakers = s.speakers.map { it.copy(isMe = it.id == id) })
    }

    override suspend fun merge(fromId: String, intoId: String) = edit { s ->
        val from = s.speakers.firstOrNull { it.id == fromId }
        val into = s.speakers.firstOrNull { it.id == intoId }
        if (from == null || into == null || fromId == intoId) s
        else s.copy(speakers = s.speakers.filter { it.id != fromId }.map { if (it.id == intoId) mergeProfiles(from, into) else it })
    }

    override suspend fun delete(id: String) = edit { s -> s.copy(speakers = s.speakers.filter { it.id != id }) }

    override suspend fun matchOrCreate(
        clusters: List<ClusterSummary>,
        seenAtEpochMs: Long,
        threshold: Double,
        minNewSpeechMs: Long,
    ): List<String?> {
        val result = arrayOfNulls<String>(clusters.size)
        edit { start ->
            var s = start
            // Große Cluster zuerst: sie prägen neue Profile, kleine werden eher zugeordnet
            for (i in clusters.indices.sortedByDescending { clusters[it].speechMs }) {
                val c = clusters[i]
                val best = s.speakers
                    .map { it to SpeakerMath.cosine(it.embedding.toFloatArray(), c.centroid) }
                    .maxByOrNull { it.second }
                if (best != null && best.second >= threshold) {
                    val updated = absorb(best.first, c, seenAtEpochMs)
                    s = s.copy(speakers = s.speakers.map { if (it.id == updated.id) updated else it })
                    result[i] = updated.id
                } else if (c.speechMs >= minNewSpeechMs) {
                    val p = SpeakerProfile(
                        id = UUID.randomUUID().toString(),
                        number = s.nextNumber,
                        embedding = c.centroid.toList(),
                        sampleCount = c.count,
                        totalSpeechMs = c.speechMs,
                        firstSeenEpochMs = seenAtEpochMs,
                        lastSeenEpochMs = seenAtEpochMs,
                    )
                    s = s.copy(nextNumber = s.nextNumber + 1, speakers = s.speakers + p)
                    result[i] = p.id
                }
            }
            s
        }
        return result.toList()
    }

    // ---------------------------------------------------------------- intern

    private suspend fun ensureLoaded(): Store = state.value ?: mutex.withLock {
        state.value ?: withContext(Dispatchers.IO) { load() }.also { state.value = it }
    }

    private suspend fun edit(transform: (Store) -> Store) {
        mutex.withLock {
            val cur = state.value ?: withContext(Dispatchers.IO) { load() }
            val next = transform(cur)
            if (next != cur) withContext(Dispatchers.IO) { save(next) }
            state.value = next
        }
    }

    private fun load(): Store {
        if (!file.isFile) return Store()
        return try {
            json.decodeFromString(Store.serializer(), file.readText())
        } catch (e: Exception) {
            // Kaputte Datei nicht überschreiben, sondern beiseitelegen.
            Log.e(TAG, "speakers.json nicht lesbar – wird als .broken gesichert", e)
            file.renameTo(File(dir, "speakers.json.broken-${now()}"))
            Store()
        }
    }

    private fun save(s: Store) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner nicht anlegbar: $dir")
        val tmp = File(dir, "speakers.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(Store.serializer(), s).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("speakers.json nicht schreibbar")
        }
    }

    internal companion object {
        private const val TAG = "O2B-Speakers"

        /** Gewicht des Bestandsprofils höchstens so groß, damit sich Profile langsam an Stimmen anpassen. */
        const val MAX_PROFILE_WEIGHT = 50

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun absorb(p: SpeakerProfile, c: ClusterSummary, seenAt: Long): SpeakerProfile = p.copy(
            embedding = SpeakerMath.weightedMean(
                p.embedding.toFloatArray(), minOf(p.sampleCount, MAX_PROFILE_WEIGHT).toDouble(),
                c.centroid, c.count.toDouble(),
            ).toList(),
            sampleCount = p.sampleCount + c.count,
            totalSpeechMs = p.totalSpeechMs + c.speechMs,
            firstSeenEpochMs = minOf(p.firstSeenEpochMs, seenAt),
            lastSeenEpochMs = maxOf(p.lastSeenEpochMs, seenAt),
        )

        fun mergeProfiles(from: SpeakerProfile, into: SpeakerProfile): SpeakerProfile = into.copy(
            name = into.name ?: from.name,
            isMe = into.isMe || from.isMe,
            embedding = SpeakerMath.weightedMean(
                into.embedding.toFloatArray(), into.sampleCount.toDouble(),
                from.embedding.toFloatArray(), from.sampleCount.toDouble(),
            ).toList(),
            sampleCount = into.sampleCount + from.sampleCount,
            totalSpeechMs = into.totalSpeechMs + from.totalSpeechMs,
            firstSeenEpochMs = minOf(into.firstSeenEpochMs, from.firstSeenEpochMs),
            lastSeenEpochMs = maxOf(into.lastSeenEpochMs, from.lastSeenEpochMs),
            aliases = (into.aliases + from.id + from.aliases).distinct(),
        )
    }
}
