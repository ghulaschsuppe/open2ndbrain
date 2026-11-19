package de.open2ndbrain.work

import android.util.Log
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * „Mit diesem Modell neu transkribieren“: Audiodatei (absoluter Pfad) → ASR-Modell-ID. Solche Dateien werden in der
 * Warteschlange vorgezogen und mit dem vorgemerkten Modell verarbeitet; danach wird der Eintrag entfernt.
 */
class TranscriptionOverrides(private val file: File) {
    private val lock = Any()
    private var map: Map<String, String> = load()

    fun modelFor(audio: File): String? = synchronized(lock) { map[audio.absolutePath] }

    fun set(audio: File, modelId: String) = synchronized(lock) {
        map = map + (audio.absolutePath to modelId)
        save()
    }

    fun clear(audio: File) = synchronized(lock) {
        if (audio.absolutePath !in map) return@synchronized
        map = map - audio.absolutePath
        save()
    }

    private fun load(): Map<String, String> = runCatching {
        if (file.isFile) JSON.decodeFromString(SERIALIZER, file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, map))
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w("TranscriptionOverrides", "nicht speicherbar", e)
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
