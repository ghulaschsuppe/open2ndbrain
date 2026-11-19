package de.open2ndbrain.audio

import android.util.Log
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.RecordingRepository
import de.open2ndbrain.core.RecordingSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Aufnahmen auf dem Datenträger (`<root>/YYYY/MM/DD/…`).
 *
 * [observeRecordings] lädt alle [refreshMs] neu und sofort, wenn die Engine über [RecordingSignals]
 * eine Änderung meldet. Sidecars werden nach Pfad + Änderungszeit zwischengespeichert.
 */
class FileRecordingRepository(
    private val root: File,
    private val refreshMs: Long = 5_000,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = System::currentTimeMillis,
) : RecordingRepository {

    private data class CacheEntry(val modified: Long, val length: Long, val sidecar: RecordingSidecar?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun observeRecordings(): Flow<List<RecordingFile>> = flow {
        while (true) {
            val seen = RecordingSignals.version.value
            emit(scan())
            withTimeoutOrNull(refreshMs) { RecordingSignals.version.first { it != seen } }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Einmaliges Einlesen, neueste zuerst. */
    fun scan(): List<RecordingFile> {
        if (!root.isDirectory) return emptyList()
        val result = ArrayList<Pair<RecordingFile, Int>>()
        val seenPaths = HashSet<String>()
        root.walkTopDown().filter { it.isDirectory }.forEach { dir ->
            val files = dir.listFiles() ?: return@forEach
            val names = files.mapTo(HashSet()) { it.name }
            for (f in files) {
                val fn = RecordingLayout.splitFileName(f.name) ?: continue
                if (fn.ext !in RecordingLayout.AUDIO_EXTENSIONS || !f.isFile) continue
                // Während des Umbenennens können kurz beide existieren: die fertige Datei gewinnt.
                if (fn.part && "${fn.base}.${fn.ext}" in names) continue
                val parsed = RecordingLayout.parseBaseName(fn.base)
                val sidecarFile = listOf(
                    File(dir, "${fn.base}.${RecordingLayout.EXT_JSON}"),
                    File(dir, "${fn.base}.${RecordingLayout.EXT_JSON}${RecordingLayout.PART}"),
                ).firstOrNull { it.name in names }
                val sidecar = sidecarFile?.let { readCached(it, seenPaths) }
                val hourStart = sidecar?.hourStartEpochMs
                    ?: RecordingLayout.hourStartFromBaseName(fn.base, zone())
                    ?: f.lastModified()
                val rf = RecordingFile(
                    audio = f,
                    sidecar = sidecar,
                    hourStartEpochMs = hourStart,
                    durationMs = sidecar?.let(SidecarIo::storedMs) ?: 0L,
                    sizeBytes = f.length(),
                    inProgress = fn.part,
                )
                result += rf to (parsed?.partIndex ?: 0)
            }
        }
        cache.keys.retainAll(seenPaths)
        return result
            .sortedWith(compareByDescending<Pair<RecordingFile, Int>> { it.first.hourStartEpochMs }.thenByDescending { it.second })
            .map { it.first }
    }

    private fun readCached(file: File, seen: MutableSet<String>): RecordingSidecar? {
        val key = file.absolutePath
        seen += key
        val mod = file.lastModified()
        val len = file.length()
        cache[key]?.let { if (it.modified == mod && it.length == len) return it.sidecar }
        val sc = SidecarIo.readOrNull(file)
        cache[key] = CacheEntry(mod, len, sc)
        return sc
    }

    override suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        if (!root.isDirectory) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    override suspend fun delete(recording: RecordingFile) = withContext(Dispatchers.IO) {
        deleteFiles(recording.audio)
        RecordingSignals.notifyChanged()
    }

    /** Löscht Audio + Sidecar(s) eines Basisnamens; gerade geschriebene Dateien bleiben unangetastet. */
    private fun deleteFiles(audio: File): Boolean {
        val dir = audio.parentFile ?: return false
        val fn = RecordingLayout.splitFileName(audio.name) ?: return false
        val related = RecordingLayout.relatedFiles(dir, fn.base)
        if (related.any { RecordingSignals.isActive(it) }) {
            Log.w(TAG, "${audio.name} wird gerade geschrieben – nicht gelöscht")
            return false
        }
        var ok = true
        for (f in related) if (f.exists() && !f.delete()) ok = false
        pruneEmptyDirs(dir)
        return ok
    }

    override suspend fun deleteOlderThan(days: Int): Int = withContext(Dispatchers.IO) {
        val cutoff = now() - days.coerceAtLeast(0) * 24L * 3_600_000L
        var count = 0
        for (rf in scan()) {
            if (rf.inProgress || rf.hourStartEpochMs >= cutoff) continue
            if (deleteFiles(rf.audio)) count++
        }
        if (count > 0) {
            Log.i(TAG, "$count Aufnahme(n) älter als $days Tage gelöscht")
            RecordingSignals.notifyChanged()
        }
        count
    }

    /** Leere Tages-/Monats-/Jahresordner unterhalb des Roots entfernen. */
    private fun pruneEmptyDirs(start: File) {
        var d: File? = start
        val rootPath = root.absoluteFile
        while (d != null && d.absoluteFile != rootPath && d.absolutePath.startsWith(rootPath.path)) {
            val children = d.list() ?: break
            if (children.isNotEmpty() || !d.delete()) break
            d = d.parentFile
        }
    }

    private companion object {
        const val TAG = "O2B-Audio"
    }
}
