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
import java.time.Instant
import java.time.ZoneId

/** Prozessweiter Status der Transkription (Worker schreibt, UI liest). */
class TranscriptionStatusStore : TranscriptionStatusHolder {
    private val _status = MutableStateFlow(TranscriptionStatus())
    override val status: StateFlow<TranscriptionStatus> = _status.asStateFlow()

    fun update(transform: (TranscriptionStatus) -> TranscriptionStatus) = _status.update(transform)
}

/** Warum gerade nicht transkribiert wird (für [TranscriptionStatus.waitingReason]). */
enum class WaitingReason { Off, ModelMissing, WaitingForCharger, BatteryLow, WaitingForSegment }

/** Ergebnis der Prüfung „darf jetzt transkribiert werden?“ (Dienst-Runner und Worker). */
sealed interface RunDecision {
    data object Run : RunDecision
    data class Wait(val reason: WaitingReason) : RunDecision
}

/** Reine Planungsentscheidungen (unit-getestet). */
object TranscriptionPlanner {

    /** Akku gilt als schwach unter diesem Ladestand (und nicht am Ladegerät). */
    const val LOW_BATTERY_PERCENT = 20

    fun batteryLow(percent: Int, charging: Boolean): Boolean = !charging && percent in 0 until LOW_BATTERY_PERCENT

    /**
     * Darf jetzt transkribiert werden? [force] („Jetzt transkribieren“) ignoriert „Aus“ und „Nur beim Laden“ einmalig,
     * nicht aber fehlende Modelle oder schwachen Akku.
     */
    fun runDecision(mode: TranscriptionMode, modelsReady: Boolean, charging: Boolean, batteryPercent: Int, force: Boolean): RunDecision = when {
        !modelsReady -> RunDecision.Wait(WaitingReason.ModelMissing)
        mode == TranscriptionMode.Off && !force -> RunDecision.Wait(WaitingReason.Off)
        mode == TranscriptionMode.ChargingOnly && !charging && !force -> RunDecision.Wait(WaitingReason.WaitingForCharger)
        batteryLow(batteryPercent, charging) -> RunDecision.Wait(WaitingReason.BatteryLow)
        else -> RunDecision.Run
    }

    /**
     * Wartegrund für die Anzeige. [segmentRecording]: Es wird gerade aufgenommen – ist die Warteschlange leer,
     * wartet die Transkription auf den Abschluss des aktuellen Abschnitts.
     */
    fun waitingReason(
        mode: TranscriptionMode,
        modelsReady: Boolean,
        pendingCount: Int,
        running: Boolean,
        charging: Boolean,
        batteryLow: Boolean,
        segmentRecording: Boolean = false,
    ): WaitingReason? = when {
        running -> null
        mode == TranscriptionMode.Off -> WaitingReason.Off
        !modelsReady -> WaitingReason.ModelMissing
        pendingCount == 0 -> if (segmentRecording) WaitingReason.WaitingForSegment else null
        mode == TranscriptionMode.ChargingOnly && !charging -> WaitingReason.WaitingForCharger
        batteryLow && !charging -> WaitingReason.BatteryLow
        else -> null
    }

    /**
     * Nächste Takt-Grenze (epoch ms) nach [now], an der Uhr ausgerichtet: bei 15 min also :00, :15, :30, :45 Ortszeit.
     */
    fun nextBoundary(now: Long, intervalMin: Int, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        val midnight = t.toLocalDate().atStartOfDay(zone)
        val minutes = java.time.Duration.between(midnight, t).toMinutes()
        val nextMin = (minutes / intervalMin + 1) * intervalMin
        val candidate = midnight.plusMinutes(nextMin)
        // Tageswechsel: höchstens bis Mitternacht des Folgetages
        val nextMidnight = t.toLocalDate().plusDays(1).atStartOfDay(zone)
        return minOf(candidate.toInstant().toEpochMilli(), nextMidnight.toInstant().toEpochMilli())
    }

    /** Die Engine wechselt zur vollen Stunde ohnehin die Datei – dort ist kein zusätzlicher Split nötig. */
    fun isFullHour(epochMs: Long, zone: ZoneId): Boolean = Instant.ofEpochMilli(epochMs).atZone(zone).let { it.minute == 0 && it.second == 0 }

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
