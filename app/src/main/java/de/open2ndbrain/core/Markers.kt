package de.open2ndbrain.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Markierungen auf der Zeitachse – unabhängig von den Audiodateien (Wanduhrzeit), die Aufnahme läuft dabei
 * ungestört weiter. Gespeichert in `filesDir/markers/markers.json`.
 *
 * - [MarkerType.Meeting]: Zeitraum mit Start und Ende (z. B. Besprechung). Wird bevorzugt transkribiert und
 *   erscheint als eigener Eintrag in Liste/Zeitleiste; ab Phase 4 mit Zusammenfassung.
 * - [MarkerType.Bookmark]: Zeitpunkt „Das war wichtig“; [Marker.startEpochMs] liegt [BOOKMARK_LOOKBACK_MS] vor dem Tippen,
 *   damit der gerade gehörte Satz mit dabei ist.
 */
enum class MarkerType { Meeting, Bookmark }

@Serializable
data class Marker(
    val id: String,
    val type: MarkerType,
    val startEpochMs: Long,
    /** null = Meeting läuft noch. Bei Bookmarks = Zeitpunkt des Tippens. */
    val endEpochMs: Long? = null,
    val title: String? = null,
    val note: String? = null,
    val createdAtEpochMs: Long,
)

const val BOOKMARK_LOOKBACK_MS = 30_000L

interface MarkerRepository {
    fun observeMarkers(): Flow<List<Marker>>

    /** Das gerade laufende Meeting (höchstens eins), sonst null. */
    val activeMeeting: StateFlow<Marker?>

    /** Startet ein Meeting ab jetzt. Läuft schon eins, wird dieses zurückgegeben. */
    suspend fun startMeeting(title: String? = null): Marker

    /** Beendet das laufende Meeting (no-op, wenn keins läuft). */
    suspend fun endMeeting(): Marker?

    suspend fun addBookmark(note: String? = null): Marker

    suspend fun update(marker: Marker)

    suspend fun delete(id: String)
}
