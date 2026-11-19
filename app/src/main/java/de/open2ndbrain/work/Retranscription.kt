package de.open2ndbrain.work

import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.Marker
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.ui.recordings.TranscriptFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Neu transkribieren: einzelne Aufnahme/Meeting mit einem bestimmten Modell (Vergleich) oder alles der letzten Tage. */
class Retranscription(private val c: AppContainer) {

    /** Schätzung für einen Massen-Neulauf. */
    data class Estimate(val files: Int, val audioMs: Long, val estimatedMs: Long)

    /**
     * Transkript löschen und die Datei vorziehen; mit [modelId] wird genau dieses Modell verwendet (Vergleich).
     * Als Nutzeraktion ignoriert es „Nur beim Laden“ einmalig.
     */
    suspend fun one(recording: RecordingFile, modelId: String? = null) {
        withContext(Dispatchers.IO) { reset(recording, modelId) }
        c.transcriptionScheduler.schedule(ignoreCharging = true)
    }

    /** Alle Dateien eines Meetings mit [modelId] neu transkribieren. */
    suspend fun meeting(meeting: Marker, modelId: String? = null): Int {
        val now = System.currentTimeMillis()
        val files = c.recordings.observeRecordings().first().filter { !it.inProgress && MarkerLogic.overlaps(meeting, it, now) }
        withContext(Dispatchers.IO) { files.forEach { reset(it, modelId) } }
        if (files.isNotEmpty()) c.transcriptionScheduler.schedule(ignoreCharging = true)
        return files.size
    }

    suspend fun estimateRecent(days: Int, modelId: String? = null): Estimate {
        val files = withContext(Dispatchers.IO) { candidates(c.recordings.observeRecordings().first(), days, System.currentTimeMillis()) }
        val audioMs = files.sumOf { it.durationMs }
        val id = modelId ?: AsrSelection.effective(c.settings.current().asrModelId, AsrSelection.installedIds(c.modelManager.states.value))
        val est = AsrSelection.estimateMs(audioMs, c.transcriptionStats.averageRealTimeFactor(id), ModelCatalog.byId(id))
        return Estimate(files.size, audioMs, est)
    }

    /** Alle Transkripte der letzten [days] Tage verwerfen; sie werden in normaler Reihenfolge neu erstellt. */
    suspend fun recent(days: Int): Int {
        val files = withContext(Dispatchers.IO) {
            candidates(c.recordings.observeRecordings().first(), days, System.currentTimeMillis()).onEach { reset(it, null) }
        }
        if (files.isNotEmpty()) c.transcriptionScheduler.schedule()
        return files.size
    }

    private suspend fun reset(rec: RecordingFile, modelId: String?) {
        if (modelId != null) c.transcriptionOverrides.set(rec.audio, modelId) else c.transcriptionOverrides.clear(rec.audio)
        c.transcriptionFailures.clear(rec.audio)
        c.transcripts.delete(rec.audio)
    }

    companion object {
        /** Abgeschlossene Aufnahmen mit vorhandenem Audio und Transkript der letzten [days] Tage. */
        fun candidates(all: List<RecordingFile>, days: Int, now: Long, hasTranscript: (RecordingFile) -> Boolean = {
            TranscriptFiles.transcriptFileFor(it.audio).isFile
        }): List<RecordingFile> {
            val cutoff = now - days * 24L * 3_600_000L
            return all.filter { !it.inProgress && it.hourStartEpochMs >= cutoff && it.audio.isFile && hasTranscript(it) }
        }
    }
}
