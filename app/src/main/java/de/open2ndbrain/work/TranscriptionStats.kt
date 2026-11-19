package de.open2ndbrain.work

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Eine transkribierte Datei (für Diagnose: Echtzeitfaktor, Ladezeit, Gerät). */
@Serializable
data class TranscriptionRunStat(
    val atEpochMs: Long,
    val audioFileName: String,
    val audioMs: Long,
    val processingMs: Long,
    /** Ladezeit der Modelle (nur bei der ersten Datei eines Laufs, sonst 0). */
    val loadMs: Long,
    val threads: Int,
    /** "service" oder "worker". */
    val origin: String,
    val device: String,
) {
    val realTimeFactor: Float? get() = TranscriptionPlanner.realTimeFactor(processingMs, audioMs)
}

/** Die letzten [MAX] Läufe in `filesDir/stats/transcription.json`. */
class TranscriptionStats(private val file: File) {
    private val lock = Any()
    private val _runs = MutableStateFlow(load())
    val runs: StateFlow<List<TranscriptionRunStat>> = _runs.asStateFlow()

    fun add(stat: TranscriptionRunStat) = synchronized(lock) {
        val list = (listOf(stat) + _runs.value).take(MAX)
        _runs.value = list
        save(list)
    }

    /** Mittlerer Echtzeitfaktor der letzten Läufe (null ohne Daten). */
    fun averageRealTimeFactor(): Float? = runs.value.mapNotNull { it.realTimeFactor }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    private fun load(): List<TranscriptionRunStat> = runCatching {
        if (file.isFile) JSON.decodeFromString(SERIALIZER, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private fun save(list: List<TranscriptionRunStat>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, list))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w("TranscriptionStats", "nicht speicherbar", e)
        }
    }

    companion object {
        const val MAX = 20
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(TranscriptionRunStat.serializer())

        /** Gerät und SoC (SoC erst ab Android 12 abfragbar). */
        fun deviceDescription(): String {
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) " · SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
            return "${Build.MANUFACTURER} ${Build.MODEL}$soc · ${Runtime.getRuntime().availableProcessors()} Kerne"
        }
    }
}
