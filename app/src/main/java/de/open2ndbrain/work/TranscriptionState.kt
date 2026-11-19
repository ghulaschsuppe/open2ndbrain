package de.open2ndbrain.work

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.core.TranscriptionStatusHolder
import de.open2ndbrain.settings.TranscriptionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Prozessweiter Status der Transkription (Worker schreibt, UI liest). */
class TranscriptionStatusStore : TranscriptionStatusHolder {
    private val _status = MutableStateFlow(TranscriptionStatus())
    override val status: StateFlow<TranscriptionStatus> = _status.asStateFlow()

    fun update(transform: (TranscriptionStatus) -> TranscriptionStatus) = _status.update(transform)
}

/** Warum gerade nicht transkribiert wird (für [TranscriptionStatus.waitingReason]). */
enum class WaitingReason { Off, ModelMissing, WaitingForCharger, BatteryLow }

/** Reine Planungsentscheidungen (unit-getestet). */
object TranscriptionPlanner {

    fun waitingReason(
        mode: TranscriptionMode,
        modelsReady: Boolean,
        pendingCount: Int,
        running: Boolean,
        charging: Boolean,
        batteryLow: Boolean,
    ): WaitingReason? = when {
        running -> null
        mode == TranscriptionMode.Off -> WaitingReason.Off
        !modelsReady -> WaitingReason.ModelMissing
        pendingCount == 0 -> null
        mode == TranscriptionMode.ChargingOnly && !charging -> WaitingReason.WaitingForCharger
        batteryLow && !charging -> WaitingReason.BatteryLow
        else -> null
    }

    /**
     * Wie ein neuer Lauf eingereiht wird:
     * - Läuft gerade einer und ist schon ein Folgelauf eingereiht → KEEP.
     * - Läuft gerade einer → APPEND_OR_REPLACE (Folgelauf greift neue Dateien auf, ohne den laufenden abzubrechen).
     * - Nur eingereiht → KEEP, bei geänderten Bedingungen (Modus/„jetzt“) REPLACE.
     * - Nichts aktiv → REPLACE.
     */
    fun policy(states: List<WorkInfo.State>, constraintsChanged: Boolean): ExistingWorkPolicy {
        val running = WorkInfo.State.RUNNING in states
        val waiting = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
        return when {
            running && waiting -> ExistingWorkPolicy.KEEP
            running -> ExistingWorkPolicy.APPEND_OR_REPLACE
            waiting -> if (constraintsChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }
    }

    /** Echtzeitfaktor = Rechenzeit / Audiodauer; null bei leerer Datei. */
    fun realTimeFactor(processingMs: Long, audioDurationMs: Long): Float? =
        if (audioDurationMs <= 0) null else processingMs.toFloat() / audioDurationMs
}

/**
 * Fehlversuche je Audiodatei (absoluter Pfad → Anzahl), damit eine dauerhaft fehlerhafte Datei die Warteschlange
 * nicht blockiert. Nach [MAX_FAILURES] wird sie übersprungen, bis der Nutzer „Neu transkribieren“ wählt.
 */
class TranscriptionFailures(private val file: File) {
    private val lock = Any()
    private var counts: Map<String, Int> = load()

    fun isBlocked(audio: File): Boolean = synchronized(lock) { (counts[audio.absolutePath] ?: 0) >= MAX_FAILURES }

    fun count(audio: File): Int = synchronized(lock) { counts[audio.absolutePath] ?: 0 }

    fun increment(audio: File) = synchronized(lock) {
        counts = counts + (audio.absolutePath to (counts[audio.absolutePath] ?: 0) + 1)
        save()
    }

    fun clear(audio: File) = synchronized(lock) {
        if (audio.absolutePath !in counts) return@synchronized
        counts = counts - audio.absolutePath
        save()
    }

    /** Einträge für Audiodateien entfernen, die es nicht mehr gibt (gelöscht, Aufbewahrung). */
    fun pruneMissing(exists: (String) -> Boolean = { File(it).exists() }): Int = synchronized(lock) {
        val keep = counts.filterKeys(exists)
        val removed = counts.size - keep.size
        if (removed > 0) {
            counts = keep
            save()
        }
        removed
    }

    private fun load(): Map<String, Int> = runCatching {
        if (file.isFile) JSON.decodeFromString(SERIALIZER, file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, counts))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w("TranscriptionFailures", "nicht speicherbar", e)
        }
    }

    companion object {
        const val MAX_FAILURES = 3
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = MapSerializer(String.serializer(), Int.serializer())
    }
}
