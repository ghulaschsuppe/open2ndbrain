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

/**
 * Sprecherprofile in `<dir>/speakers.json` (atomar über `.tmp` + rename). Alle Änderungen laufen unter
 * einem Mutex; [observeSpeakers] liefert den aktuellen Stand als Flow. Implementiert zusätzlich
 * [SpeakerMatcher] für den Transcriber.
 */
class FileSpeakerRepository(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : SpeakerRepository, SpeakerMatcher {

    /** Beitrag einer Datei zu einem Profil (für idempotentes Neu-Transkribieren). */
    @Serializable
    internal data class Contribution(val profileId: String, val count: Int, val speechMs: Long, val atEpochMs: Long)

    @Serializable
    internal data class Store(
        val version: Int = 1,
        /** Nächste Nummer für „Sprecher N“ (wird nach Löschen nicht wiederverwendet). */
        val nextNumber: Int = 1,
        val speakers: List<SpeakerProfile> = emptyList(),
        /** Audiodateiname → Beiträge; ältere als [CONTRIBUTION_KEEP_MS] werden verworfen. */
        val contributions: Map<String, List<Contribution>> = emptyMap(),
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
        else s.copy(
            speakers = s.speakers.filter { it.id != fromId }.map { if (it.id == intoId) mergeProfiles(from, into) else it },
            contributions = s.contributions.mapValues { (_, list) ->
                list.map { if (it.profileId == fromId) it.copy(profileId = intoId) else it }
                    .groupBy { it.profileId }
                    .map { (pid, l) -> Contribution(pid, l.sumOf { it.count }, l.sumOf { it.speechMs }, l.maxOf { it.atEpochMs }) }
            },
        )
    }

    override suspend fun delete(id: String) = edit { s ->
        s.copy(
            speakers = s.speakers.filter { it.id != id },
            contributions = s.contributions.mapValues { (_, l) -> l.filter { it.profileId != id } }.filterValues { it.isNotEmpty() },
        )
    }

    override suspend fun knownProfiles(): List<Pair<String, FloatArray>> =
        ensureLoaded().speakers.map { it.id to it.embedding.toFloatArray() }

    override suspend fun apply(update: SpeakerUpdate, audioFileName: String, seenAtEpochMs: Long) = edit { s ->
        val speakers = s.speakers.toMutableList()
        fun indexOf(id: String) = speakers.indexOfFirst { it.id == id || id in it.aliases }

        // Früheren Beitrag derselben Datei abziehen (nur Statistik; das Embedding enthält ihn bereits)
        val old = s.contributions[audioFileName].orEmpty()
        val alreadyContributed = HashSet<String>()
        for (c in old) {
            val i = indexOf(c.profileId)
            if (i < 0) continue
            val p = speakers[i]
            // erst am Ende begrenzen, damit Abziehen + erneutes Addieren exakt ist
            speakers[i] = p.copy(sampleCount = p.sampleCount - c.count, totalSpeechMs = p.totalSpeechMs - c.speechMs)
            alreadyContributed += p.id
        }

        var next = s.nextNumber
        val contributions = ArrayList<Contribution>()
        for (e in update.entries) {
            val c = e.cluster
            val i = indexOf(e.profileId)
            val id: String
            if (i >= 0) {
                val p = speakers[i]
                speakers[i] = if (p.id in alreadyContributed) {
                    p.copy(
                        sampleCount = p.sampleCount + c.count,
                        totalSpeechMs = p.totalSpeechMs + c.speechMs,
                        lastSeenEpochMs = maxOf(p.lastSeenEpochMs, seenAtEpochMs),
                    )
                } else {
                    absorb(p, c, seenAtEpochMs)
                }
                id = p.id
            } else if (e.isNew) {
                speakers += SpeakerProfile(
                    id = e.profileId,
                    number = next++,
                    embedding = c.centroid.toList(),
                    sampleCount = c.count,
                    totalSpeechMs = c.speechMs,
                    firstSeenEpochMs = seenAtEpochMs,
                    lastSeenEpochMs = seenAtEpochMs,
                )
                id = e.profileId
            } else {
                continue // inzwischen gelöscht
            }
            contributions += Contribution(id, c.count, c.speechMs, now()) // Zeitpunkt der Transkription (für das Aufräumen)
        }
        for (i in speakers.indices) {
            val p = speakers[i]
            if (p.sampleCount < 1 || p.totalSpeechMs < 0) {
                speakers[i] = p.copy(sampleCount = p.sampleCount.coerceAtLeast(1), totalSpeechMs = p.totalSpeechMs.coerceAtLeast(0))
            }
        }
        val cutoff = now() - CONTRIBUTION_KEEP_MS
        val all = (s.contributions - audioFileName + (audioFileName to contributions))
            .filterValues { l -> l.isNotEmpty() && l.any { it.atEpochMs >= cutoff } }
        s.copy(nextNumber = next, speakers = speakers, contributions = all)
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

        /** Wie lange Beiträge je Datei gemerkt werden (Neu-Transkribieren älterer Dateien zählt sonst doppelt). */
        const val CONTRIBUTION_KEEP_MS = 180L * 24 * 3_600_000

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
