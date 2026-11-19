package de.open2ndbrain.markers

import android.util.Log
import de.open2ndbrain.core.BOOKMARK_LOOKBACK_MS
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerRepository
import de.open2ndbrain.core.MarkerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Markierungen als JSON in `<dir>/markers.json` (atomar über `.tmp` + rename, Schreiben unter Mutex).
 * Wird beim Erzeugen synchron geladen – ein laufendes Meeting überlebt so App- und Geräteneustart.
 */
class FileMarkerRepository(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : MarkerRepository {

    @Serializable
    private data class Store(val version: Int = 1, val markers: List<Marker> = emptyList())

    private val file = File(dir, FILE_NAME)
    private val mutex = Mutex()
    private val markers = MutableStateFlow(load())
    private val _active = MutableStateFlow(MarkerLogic.activeMeeting(markers.value))

    override val activeMeeting: StateFlow<Marker?> = _active.asStateFlow()

    /** Neueste zuerst. */
    override fun observeMarkers(): Flow<List<Marker>> = markers.asStateFlow()

    /** Aktueller Stand (synchron). */
    fun current(): List<Marker> = markers.value

    override suspend fun startMeeting(title: String?): Marker = mutex.withLock {
        _active.value?.let { return@withLock it }
        val t = now()
        val m = Marker(id = newId(), type = MarkerType.Meeting, startEpochMs = t, title = title?.trim()?.ifEmpty { null }, createdAtEpochMs = t)
        save(markers.value + m)
        m
    }

    override suspend fun endMeeting(): Marker? = mutex.withLock {
        val active = _active.value ?: return@withLock null
        val ended = active.copy(endEpochMs = now().coerceAtLeast(active.startEpochMs))
        save(markers.value.map { if (it.id == active.id) ended else it })
        ended
    }

    override suspend fun addBookmark(note: String?): Marker = mutex.withLock {
        val t = now()
        val m = Marker(
            id = newId(),
            type = MarkerType.Bookmark,
            startEpochMs = t - BOOKMARK_LOOKBACK_MS,
            endEpochMs = t,
            note = note?.trim()?.ifEmpty { null },
            createdAtEpochMs = t,
        )
        save(markers.value + m)
        m
    }

    override suspend fun update(marker: Marker) = mutex.withLock {
        if (markers.value.none { it.id == marker.id }) return@withLock
        save(markers.value.map { if (it.id == marker.id) marker else it })
    }

    override suspend fun delete(id: String) = mutex.withLock {
        save(markers.value.filterNot { it.id == id })
    }

    private fun save(list: List<Marker>) {
        val sorted = list.sortedByDescending { it.startEpochMs }
        writeAtomic(Store(markers = sorted))
        markers.value = sorted
        _active.value = MarkerLogic.activeMeeting(sorted)
    }

    private fun load(): List<Marker> {
        if (!file.isFile) return emptyList()
        return try {
            JSON.decodeFromString(Store.serializer(), file.readText()).markers.sortedByDescending { it.startEpochMs }
        } catch (e: Exception) {
            // Beschädigt: zur Analyse beiseitelegen statt still zu überschreiben.
            Log.e(TAG, "markers.json nicht lesbar", e)
            file.renameTo(File(dir, "$FILE_NAME.broken-${now()}"))
            emptyList()
        }
    }

    private fun writeAtomic(store: Store) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner nicht anlegbar: $dir")
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JSON.encodeToString(Store.serializer(), store).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("markers.json nicht schreibbar")
        }
    }

    private companion object {
        const val TAG = "Markers"
        const val FILE_NAME = "markers.json"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
