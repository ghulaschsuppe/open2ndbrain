package de.open2ndbrain.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.MarkerType
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Alles, was die Liste braucht. */
data class RecordingsData(
    val days: List<DayGroup>,
    /** Meetings je Tag (Beginn), neueste zuerst. */
    val meetings: Map<LocalDate, List<Marker>>,
    val badges: Map<String, TranscriptBadge>,
    /** Pfade von Einträgen, deren Audio gelöscht ist (nur Transkript vorhanden). */
    val audioDeleted: Set<String>,
    /** Anzahl Lesezeichen je Audiodatei (Pfad). */
    val bookmarks: Map<String, Int>,
)

/** Eine Äußerung samt Datei (für dateiübergreifende Meeting-Ansichten). */
data class TranscriptLine(val audio: File, val utterance: de.open2ndbrain.core.Utterance)

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as O2bApp).container
    private val zone get() = ZoneId.systemDefault()

    /** Transkripte ändern sich an Dateigrenzen der Transkription → nur dann neu einlesen (nicht bei jedem Fortschritt). */
    private val transcriptionBoundary = container.transcriptionStatus.status
        .map { it.running to it.currentFile }
        .distinctUntilChanged()

    val markers: StateFlow<List<Marker>> = container.markers.observeMarkers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val scan: Flow<Pair<List<RecordingFile>, Set<String>>> =
        combine(container.recordings.observeRecordings(), transcriptionBoundary) { recs, _ -> recs }
            .map { recs ->
                val transcriptFiles = TranscriptFiles.listAll(container.audioRoot)
                val transcriptBases = transcriptFiles.mapTo(HashSet()) { it.name.removeSuffix(TranscriptFiles.SUFFIX) }
                val known = recs.mapTo(HashSet()) { TranscriptFiles.baseName(it.audio.name) }
                val orphans = TranscriptFiles.orphans(transcriptFiles, known, zone)
                (recs + orphans) to transcriptBases
            }
            .flowOn(Dispatchers.IO)

    /** null = wird geladen. */
    val data: StateFlow<RecordingsData?> = combine(
        scan,
        container.transcriptionStatus.status,
        markers,
        container.settings.settings.map { it.transcriptionMode == TranscriptionMode.Off }.distinctUntilChanged(),
    ) { (all, transcriptBases), status, markerList, off ->
        val deleted = all.filter { it.sizeBytes == 0L && !it.audio.exists() }.mapTo(HashSet()) { it.audio.path }
        val meetings = markerList.filter { it.type == MarkerType.Meeting }
            .groupBy { RecordingGrouping.localDate(it.startEpochMs, zone) }
        RecordingsData(
            days = RecordingGrouping.groupByDay(all, zone).let { days ->
                // Tage, an denen nur ein Meeting (noch ohne Datei) existiert, ebenfalls zeigen.
                val extra = meetings.keys.filter { d -> days.none { it.date == d } }.map { DayGroup(it, emptyList(), 0, 0) }
                (days + extra).sortedByDescending { it.date }
            },
            meetings = meetings,
            badges = all.associate { rec ->
                rec.audio.path to TranscriptFiles.badge(
                    rec,
                    TranscriptFiles.baseName(rec.audio.name) in transcriptBases,
                    status,
                    container.transcriptionFailures.isBlocked(rec.audio),
                    off,
                )
            },
            audioDeleted = deleted,
            bookmarks = all.associate { it.audio.path to MarkerLogic.bookmarksIn(it, markerList).size }.filterValues { it > 0 },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val speakers: StateFlow<SpeakerDirectory> = container.speakers.observeSpeakers()
        .map { SpeakerDirectory(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeakerDirectory.EMPTY)

    val transcriptionStatus: StateFlow<TranscriptionStatus> = container.transcriptionStatus.status
    val chargingOnly: StateFlow<Boolean> = container.settings.settings.map { it.transcriptionMode == TranscriptionMode.ChargingOnly }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val player = RecordingPlayer(viewModelScope)

    // ---------------------------------------------------------------- Detail: Aufnahme

    private val _selected = MutableStateFlow<RecordingFile?>(null)
    val selected: StateFlow<RecordingFile?> = _selected

    val selectedTranscript: StateFlow<Transcript?> = _selected
        .flatMapLatest { rec -> rec?.let { container.transcripts.observeTranscript(it.audio) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(recording: RecordingFile) {
        _selected.value = recording
    }

    fun closeDetail() {
        _selected.value = null
    }

    // ---------------------------------------------------------------- Detail: Meeting

    private val _selectedMeetingId = MutableStateFlow<String?>(null)
    val selectedMeeting: StateFlow<Marker?> = combine(_selectedMeetingId, markers) { id, list -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Äußerungen aller Transkripte im Meeting-Zeitraum (dateiübergreifend). */
    val meetingLines: StateFlow<List<TranscriptLine>?> = combine(selectedMeeting, transcriptionBoundary) { m, _ -> m }
        .map { m -> m?.let { loadMeetingLines(it) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openMeeting(marker: Marker) {
        _selectedMeetingId.value = marker.id
    }

    fun closeMeeting() {
        _selectedMeetingId.value = null
    }

    private fun loadMeetingLines(meeting: Marker): List<TranscriptLine> {
        val range = MarkerLogic.markerRange(meeting, System.currentTimeMillis())
        // Nur Tagesordner im Zeitraum durchsuchen (Ordner YYYY/MM/DD).
        val first = RecordingGrouping.localDate(range.first, zone)
        val last = RecordingGrouping.localDate(range.last, zone)
        val dirs = generateSequence(first) { d -> d.plusDays(1).takeIf { !it.isAfter(last) } }
            .map { File(container.audioRoot, "%04d/%02d/%02d".format(it.year, it.monthValue, it.dayOfMonth)) }
        val transcripts = dirs.flatMap { dir ->
            dir.listFiles { f -> f.name.endsWith(TranscriptFiles.SUFFIX) }.orEmpty().asSequence()
                .mapNotNull { tf -> TranscriptFiles.decode(tf)?.let { tf.parentFile!! to it } }
        }.toList()
        val dirByName = transcripts.associate { (dir, t) -> t.audioFileName to dir }
        return MarkerLogic.utterancesIn(range, transcripts.map { it.second })
            .map { TranscriptLine(File(dirByName[it.audioFileName], it.audioFileName), it.utterance) }
    }

    /** Dateien im Meeting-Zeitraum, die noch kein Transkript haben (für den Hinweis in der Meeting-Ansicht). */
    fun meetingPendingFiles(meeting: Marker, all: RecordingsData): Int {
        val now = System.currentTimeMillis()
        return all.days.flatMap { it.recordings }.count { rec ->
            MarkerLogic.overlaps(meeting, rec, now) && all.badges[rec.audio.path].let { it == TranscriptBadge.Waiting || it == TranscriptBadge.Transcribing }
        }
    }

    // ---------------------------------------------------------------- Aktionen

    fun play(audio: File, startMs: Long? = null) {
        if (audio.exists()) player.play(audio, startMs)
    }

    fun delete(recording: RecordingFile) {
        if (recording.inProgress) return
        if (player.state.value.file == recording.audio) player.close()
        if (_selected.value?.audio == recording.audio) _selected.value = null
        viewModelScope.launch(Dispatchers.IO) { container.recordings.delete(recording) }
    }

    /** Transkript verwerfen und neu einreihen (setzt auch den Fehlerzähler zurück). */
    fun retranscribe(recording: RecordingFile) {
        viewModelScope.launch(Dispatchers.IO) {
            container.transcripts.delete(recording.audio)
            container.transcriptionFailures.clear(recording.audio)
            container.transcriptionScheduler.schedule()
        }
    }

    /** „Jetzt transkribieren“: ignoriert einmalig die Bedingung „Nur beim Laden“. */
    fun transcribeNow() = container.transcriptionScheduler.schedule(ignoreCharging = true)

    fun renameMarker(marker: Marker, title: String) = viewModelScope.launch {
        val t = title.trim().ifEmpty { null }
        container.markers.update(if (marker.type == MarkerType.Meeting) marker.copy(title = t) else marker.copy(note = t))
    }

    fun deleteMarker(marker: Marker) = viewModelScope.launch {
        if (_selectedMeetingId.value == marker.id) _selectedMeetingId.value = null
        container.markers.delete(marker.id)
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
