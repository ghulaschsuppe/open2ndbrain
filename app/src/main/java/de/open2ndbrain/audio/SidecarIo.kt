package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingSidecar
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Lesen/Schreiben der Sidecar-JSON. Schreiben ist atomar (temporäre Datei + rename). */
internal object SidecarIo {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(sidecar: RecordingSidecar): String = json.encodeToString(RecordingSidecar.serializer(), sidecar)

    fun decode(text: String): RecordingSidecar = json.decodeFromString(RecordingSidecar.serializer(), text)

    /** Liest ein Sidecar; null bei fehlender oder kaputter Datei. */
    fun readOrNull(file: File): RecordingSidecar? =
        if (!file.isFile) null else runCatching { decode(file.readText()) }.getOrNull()

    /** Schreibt atomar nach [target] (über `<target>.tmp`, fsync, rename). */
    fun writeAtomic(target: File, sidecar: RecordingSidecar) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(encode(sidecar).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Umbenennen fehlgeschlagen: ${tmp.name} → ${target.name}")
        }
    }

    /** Summe der Segmentdauern (= gespeicherte Audiodauer). */
    fun storedMs(sidecar: RecordingSidecar): Long = sidecar.segments.sumOf { it.durationMs }

    /** Gespeicherte Sprachdauer aller Sidecars eines Tagesordners, außer den Basisnamen in [exclude]. */
    fun storedMsInDir(dayDir: File, exclude: Set<String> = emptySet()): Long {
        val files = dayDir.listFiles() ?: return 0
        val byBase = HashMap<String, File>()
        for (f in files) {
            val fn = RecordingLayout.splitFileName(f.name) ?: continue
            if (fn.ext != RecordingLayout.EXT_JSON || fn.base in exclude) continue
            // Fertiges Sidecar hat Vorrang vor dem .part
            if (!fn.part || fn.base !in byBase) byBase[fn.base] = f
        }
        return byBase.values.sumOf { f -> readOrNull(f)?.let(::storedMs) ?: 0L }
    }
}
