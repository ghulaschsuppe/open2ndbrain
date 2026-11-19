package de.open2ndbrain.speakers

import android.util.Log
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.core.SpeakerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Sprecherprofile, gespeichert in zwei Dateien unter [dir]:
 * - `speakers.json`: die Profile (klein; UI-Änderungen wie Umbenennen/Zusammenführen schreiben nur diese Datei),
 * - `speaker-contributions.json`: Beiträge je Audiodatei (für idempotentes Neu-Transkribieren).
 *
 * Konsistenz: [apply] schreibt zuerst die Beiträge (mit neuer Generation und dem vorherigen Eintrag der Datei),
 * danach die Profile mit derselben Generation. Stürzt die App dazwischen ab, erkennt das Laden die ungleichen
 * Generationen und setzt den Beitrag dieser Datei zurück. Zusammenführen/Löschen ändern die Beiträge nicht –
 * sie werden beim Anwenden über Aliasse aufgelöst bzw. übersprungen.
 *
 * Alle Änderungen laufen unter einem Mutex; Speichern und Übernehmen in den Speicher sind nicht abbrechbar.
 * Lesefehler: kaputter Inhalt → Datei wird als `.broken-…` beiseitegelegt; IOException → wird weitergereicht,
 * der Zustand bleibt ungeladen und wird beim nächsten Zugriff erneut gelesen.
 */
class FileSpeakerRepository(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : SpeakerRepository, SpeakerMatcher {

    /** Beitrag einer Datei zu einem Profil. */
    @Serializable
    internal data class Contribution(val profileId: String, val count: Int, val speechMs: Long, val atEpochMs: Long)

    @Serializable
    internal data class ProfileStore(
        val version: Int = 2,
        /** Nächste Nummer für „Sprecher N“ (wird nach Löschen nicht wiederverwendet). */
        val nextNumber: Int = 1,
        val speakers: List<SpeakerProfile> = emptyList(),
        /** Generation der zuletzt passend geschriebenen Beitragsdatei. */
        val contributionsGeneration: Long = 0,
        val lastCleanupEpochMs: Long = 0,
        /** Nur zum Lesen alter Dateien (Version 1 hatte die Beiträge hier); wird nicht mehr geschrieben. */
        val contributions: Map<String, List<Contribution>>? = null,
    )

    @Serializable
    internal data class LastChange(val audioFileName: String, val previous: List<Contribution>?)

    @Serializable
    internal data class ContributionStore(
        val version: Int = 1,
        val generation: Long = 0,
        /** Audiodateiname → Beiträge; ältere als [CONTRIBUTION_KEEP_MS] werden verworfen. */
        val contributions: Map<String, List<Contribution>> = emptyMap(),
        /** Vorheriger Stand der zuletzt geänderten Datei (für das Zurücksetzen nach Absturz). */
        val lastChange: LastChange? = null,
    )

    private data class State(val profiles: ProfileStore, val contribs: ContributionStore)

    private val profilesFile = File(dir, "speakers.json")
    private val contribsFile = File(dir, "speaker-contributions.json")
    private val mutex = Mutex()
    private val state = MutableStateFlow<State?>(null)

    override fun observeSpeakers(): Flow<List<SpeakerProfile>> = flow {
        ensureLoaded()
        emitAll(state.asSpeakers())
    }

    private fun MutableStateFlow<State?>.asSpeakers(): Flow<List<SpeakerProfile>> = flow {
        collect { s -> if (s != null) emit(s.profiles.speakers.sortedBy { it.number }) }
    }

    override suspend fun resolve(speakerId: String): SpeakerProfile? =
        ensureLoaded().profiles.speakers.firstOrNull { it.id == speakerId || speakerId in it.aliases }

    override suspend fun rename(id: String, name: String?) = editProfiles { s ->
        s.copy(speakers = s.speakers.map { if (it.id == id) it.copy(name = name?.trim()?.ifEmpty { null }) else it })
    }

    override suspend fun setMe(id: String) = editProfiles { s ->
        if (s.speakers.none { it.id == id }) s
        else s.copy(speakers = s.speakers.map { it.copy(isMe = it.id == id) })
    }

    override suspend fun merge(fromId: String, intoId: String) = editProfiles { s ->
        val from = s.speakers.firstOrNull { it.id == fromId }
        val into = s.speakers.firstOrNull { it.id == intoId }
        if (from == null || into == null || fromId == intoId) s
        else s.copy(speakers = s.speakers.filter { it.id != fromId }.map { if (it.id == intoId) mergeProfiles(from, into) else it })
    }

    override suspend fun delete(id: String) = editProfiles { s -> s.copy(speakers = s.speakers.filter { it.id != id }) }

    /**
     * Start-Cluster für das Clustering: benannte Profile, „Ich“, Profile mit ≥ [SEED_MIN_SPEECH_MS] Sprache oder in
     * den letzten [SEED_RECENT_MS] gehört; höchstens [MAX_SEEDS] (benannte/„Ich“ zuerst, dann nach Sprechzeit).
     */
    override suspend fun knownProfiles(): List<Pair<String, FloatArray>> =
        selectSeeds(ensureLoaded().profiles.speakers, now()).map { it.id to it.embedding.toFloatArray() }

    override suspend fun apply(update: SpeakerUpdate, audioFileName: String, seenAtEpochMs: Long) = edit { st ->
        val s = st.profiles
        val t = now()
        val old = st.contribs.contributions[audioFileName]
        // Nichts zu tun (kein alter Beitrag, keine Sprecher, kein Aufräumen fällig) → nichts schreiben
        if (old == null && update.entries.isEmpty() && t - s.lastCleanupEpochMs < CLEANUP_INTERVAL_MS) return@edit st
        val speakers = s.speakers.toMutableList()
        fun indexOf(id: String) = speakers.indexOfFirst { it.id == id || id in it.aliases }

        // Früheren Beitrag derselben Datei abziehen (nur Statistik; das Embedding enthält ihn bereits)
        val alreadyContributed = HashSet<String>()
        for (c in old.orEmpty()) {
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
            contributions += Contribution(id, c.count, c.speechMs, t) // Zeitpunkt der Transkription (fürs Aufräumen)
        }
        for (i in speakers.indices) {
            val p = speakers[i]
            if (p.sampleCount < 1 || p.totalSpeechMs < 0) {
                speakers[i] = p.copy(sampleCount = p.sampleCount.coerceAtLeast(1), totalSpeechMs = p.totalSpeechMs.coerceAtLeast(0))
            }
        }
        var profiles = s.copy(nextNumber = next, speakers = speakers)
        if (t - s.lastCleanupEpochMs >= CLEANUP_INTERVAL_MS) {
            val touched = contributions.mapTo(HashSet()) { it.profileId }
            profiles = profiles.copy(speakers = cleanup(profiles.speakers, t, keep = touched), lastCleanupEpochMs = t)
        }
        val cutoff = t - CONTRIBUTION_KEEP_MS
        val all = (st.contribs.contributions - audioFileName).filterValues { l -> l.any { it.atEpochMs >= cutoff } }
            .let { if (contributions.isEmpty()) it else it + (audioFileName to contributions) }
        val gen = st.contribs.generation + 1
        State(
            profiles.copy(contributionsGeneration = gen),
            ContributionStore(generation = gen, contributions = all, lastChange = LastChange(audioFileName, old)),
        )
    }

    // ---------------------------------------------------------------- intern

    private suspend fun ensureLoaded(): State = state.value ?: mutex.withLock {
        state.value ?: withContext(Dispatchers.IO) { load() }.also { state.value = it }
    }

    private suspend fun editProfiles(transform: (ProfileStore) -> ProfileStore) = edit { it.copy(profiles = transform(it.profiles)) }

    /** Ändern + Speichern + Übernehmen untrennbar (auch bei Abbruch der aufrufenden Coroutine). */
    private suspend fun edit(transform: (State) -> State) {
        mutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                val cur = state.value ?: load()
                val next = transform(cur)
                // Reihenfolge: Beiträge zuerst, Profile danach (siehe Klassenbeschreibung)
                if (next.contribs != cur.contribs) save(contribsFile, ContributionStore.serializer(), next.contribs)
                if (next.profiles != cur.profiles) save(profilesFile, ProfileStore.serializer(), next.profiles)
                state.value = next
            }
        }
    }

    private fun load(): State {
        if (!dir.isDirectory) return State(ProfileStore(), ContributionStore())
        var profiles = read(profilesFile, ProfileStore.serializer()) ?: ProfileStore()
        var contribs = read(contribsFile, ContributionStore.serializer())
        val legacy = profiles.contributions
        if (contribs == null && legacy != null) {
            // Umstieg von Version 1: Beiträge lagen in speakers.json
            contribs = ContributionStore(generation = profiles.contributionsGeneration, contributions = legacy)
        }
        profiles = profiles.copy(contributions = null)
        contribs = contribs ?: ContributionStore(generation = profiles.contributionsGeneration)
        if (contribs.generation != profiles.contributionsGeneration) {
            val lc = contribs.lastChange
            contribs = if (contribs.generation == profiles.contributionsGeneration + 1 && lc != null) {
                // Absturz zwischen Beiträgen und Profilen: Beitrag dieser Datei zurücksetzen
                Log.w(TAG, "Unvollständige Sprecher-Aktualisierung (${lc.audioFileName}) wird zurückgesetzt")
                val restored = if (lc.previous == null) contribs.contributions - lc.audioFileName
                else contribs.contributions + (lc.audioFileName to lc.previous)
                contribs.copy(generation = profiles.contributionsGeneration, contributions = restored, lastChange = null)
            } else {
                Log.w(TAG, "Generationen passen nicht (${contribs.generation}/${profiles.contributionsGeneration}) – übernommen")
                contribs.copy(generation = profiles.contributionsGeneration)
            }
        }
        return State(profiles, contribs)
    }

    /** null = Datei fehlt oder war kaputt (dann beiseitegelegt). IOException wird weitergereicht. */
    private fun <T> read(f: File, serializer: KSerializer<T>): T? {
        if (!f.isFile) return null
        val text = f.readText() // IOException → nach oben, Zustand bleibt ungeladen
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            setAside(f, e)
        } catch (e: IllegalArgumentException) {
            setAside(f, e)
        }
    }

    private fun setAside(f: File, e: Exception): Nothing? {
        // Kaputte Datei nicht überschreiben, sondern beiseitelegen.
        Log.e(TAG, "${f.name} nicht lesbar – wird als .broken gesichert", e)
        f.renameTo(File(dir, "${f.name}.broken-${now()}"))
        return null
    }

    private fun <T> save(f: File, serializer: KSerializer<T>, value: T) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner nicht anlegbar: $dir")
        val tmp = File(dir, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("${f.name} nicht schreibbar")
        }
    }

    internal companion object {
        private const val TAG = "O2B-Speakers"

        /** Gewicht des Bestandsprofils höchstens so groß, damit sich Profile langsam an Stimmen anpassen. */
        const val MAX_PROFILE_WEIGHT = 50

        /** Wie lange Beiträge je Datei gemerkt werden (Neu-Transkribieren älterer Dateien zählt sonst doppelt). */
        const val CONTRIBUTION_KEEP_MS = 180L * 24 * 3_600_000

        const val SEED_MIN_SPEECH_MS = 60_000L
        const val SEED_RECENT_MS = 30L * 24 * 3_600_000
        const val MAX_SEEDS = 200

        /** Aufräumen: unbenannt, nicht „Ich“, < 60 s Sprache und so lange nicht gehört → löschen. */
        const val CLEANUP_UNSEEN_MS = 90L * 24 * 3_600_000
        const val CLEANUP_MAX_SPEECH_MS = 60_000L
        const val CLEANUP_INTERVAL_MS = 24L * 3_600_000

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun selectSeeds(all: List<SpeakerProfile>, now: Long): List<SpeakerProfile> = all
            .filter { it.name != null || it.isMe || it.totalSpeechMs >= SEED_MIN_SPEECH_MS || it.lastSeenEpochMs >= now - SEED_RECENT_MS }
            .sortedWith(compareByDescending<SpeakerProfile> { it.name != null || it.isMe }.thenByDescending { it.totalSpeechMs })
            .take(MAX_SEEDS)

        fun cleanup(all: List<SpeakerProfile>, now: Long, keep: Set<String> = emptySet()): List<SpeakerProfile> = all.filterNot {
            it.id !in keep && it.name == null && !it.isMe && it.totalSpeechMs < CLEANUP_MAX_SPEECH_MS && it.lastSeenEpochMs < now - CLEANUP_UNSEEN_MS
        }.also { kept -> if (kept.size < all.size) Log.i(TAG, "${all.size - kept.size} alte, kurze Sprecherprofile entfernt") }

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
