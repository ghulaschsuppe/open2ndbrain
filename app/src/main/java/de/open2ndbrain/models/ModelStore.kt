package de.open2ndbrain.models

import de.open2ndbrain.core.ModelFile
import de.open2ndbrain.core.ModelSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Ablage der Modelle: `<root>/<id>/<Datei>`; laufende Downloads als `<Datei>.part`.
 * Eine Datei ohne `.part` wurde bereits per SHA-256 geprüft (umbenannt wird erst nach der Prüfung).
 * Installiert ist ein Modell erst mit der Markerdatei [MARKER] – sie wird zuletzt und atomar geschrieben.
 */
class ModelStore(val root: File) {

    @Serializable
    data class Marker(val id: String, val files: List<Entry>, val installedAtEpochMs: Long) {
        @Serializable
        data class Entry(val name: String, val sha256: String, val sizeBytes: Long)
    }

    fun dir(spec: ModelSpec) = File(root, spec.id)
    fun finalFile(spec: ModelSpec, file: ModelFile) = File(dir(spec), file.name)
    fun partFile(spec: ModelSpec, file: ModelFile) = File(dir(spec), file.name + PART)
    private fun marker(spec: ModelSpec) = File(dir(spec), MARKER)
    val importDir: File get() = File(root, ".import")

    /** Geprüft = fertige Datei mit erwarteter Größe (wird nur nach erfolgreicher SHA-Prüfung so benannt). */
    fun isVerified(spec: ModelSpec, file: ModelFile): Boolean = finalFile(spec, file).let { it.isFile && it.length() == file.sizeBytes }

    fun verifiedNames(spec: ModelSpec): Set<String> = spec.files.filter { isVerified(spec, it) }.mapTo(HashSet()) { it.name }

    /** Bereits vorhandene Bytes (geprüfte Dateien + Teildateien). */
    fun bytesPresent(spec: ModelSpec): Long = spec.files.sumOf { f ->
        when {
            isVerified(spec, f) -> f.sizeBytes
            else -> partFile(spec, f).takeIf { it.isFile }?.length()?.coerceAtMost(f.sizeBytes) ?: 0L
        }
    }

    fun isInstalled(spec: ModelSpec): Boolean {
        val m = readMarker(spec) ?: return false
        val expected = spec.files.map { Marker.Entry(it.name, it.sha256.lowercase(), it.sizeBytes) }.toSet()
        if (m.id != spec.id || m.files.toSet() != expected) return false
        return spec.files.all { isVerified(spec, it) }
    }

    fun allFilesVerified(spec: ModelSpec) = spec.files.all { isVerified(spec, it) }

    fun writeMarker(spec: ModelSpec, now: Long = System.currentTimeMillis()) {
        check(allFilesVerified(spec)) { "Nicht alle Dateien von ${spec.id} vorhanden" }
        val marker = Marker(spec.id, spec.files.map { Marker.Entry(it.name, it.sha256.lowercase(), it.sizeBytes) }, now)
        val target = marker(spec)
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(JSON.encodeToString(Marker.serializer(), marker).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Marker nicht schreibbar: $target")
        }
    }

    private fun readMarker(spec: ModelSpec): Marker? = marker(spec).takeIf { it.isFile }?.let {
        runCatching { JSON.decodeFromString(Marker.serializer(), it.readText()) }.getOrNull()
    }

    /** Installation zurücknehmen und alle Dateien entfernen (Marker zuerst, damit nie ein halbes Modell gilt). */
    fun delete(spec: ModelSpec) {
        marker(spec).delete()
        dir(spec).deleteRecursively()
    }

    /** Geprüfte Datei an ihren Platz verschieben (gleiches Dateisystem → atomar). */
    fun install(spec: ModelSpec, file: ModelFile, verifiedSource: File) {
        val dir = dir(spec)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Ordner nicht anlegbar: $dir")
        val target = finalFile(spec, file)
        target.delete()
        if (!verifiedSource.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen: ${verifiedSource.name}")
        partFile(spec, file).takeIf { it != verifiedSource }?.delete()
    }

    companion object {
        const val PART = ".part"
        const val MARKER = "installed.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
