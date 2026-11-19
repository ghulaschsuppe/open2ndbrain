package de.open2ndbrain.transcription

import de.open2ndbrain.core.Transcript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Ablage der Transkripte: `<Ordner>/<Basisname>.transcript.json` neben der Audiodatei
 * (Basisname = Audiodateiname ohne `.ogg`/`.m4a` bzw. `.part`). Schreiben atomar über `.part` + rename.
 */
internal object TranscriptFiles {
    const val SUFFIX = ".transcript.json"
    private const val PART = ".part"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _version = MutableStateFlow(0L)

    /** Steigt nach jedem Schreiben/Löschen eines Transkripts. */
    val version: StateFlow<Long> = _version

    fun notifyChanged() {
        synchronized(this) { _version.value = _version.value + 1 }
    }

    fun baseName(audioName: String): String {
        val n = audioName.removeSuffix(PART)
        val dot = n.lastIndexOf('.')
        return if (dot > 0) n.substring(0, dot) else n
    }

    fun fileFor(audio: File): File = File(audio.parentFile, baseName(audio.name) + SUFFIX)

    fun read(audio: File): Transcript? {
        val f = fileFor(audio)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString(Transcript.serializer(), f.readText()) }.getOrNull()
    }

    private val VERSION_RE = Regex("""^\s*\{\s*"version"\s*:\s*(\d+)""")

    /** Version eines vorhandenen Transkripts, ohne die ganze Datei zu parsen; null = keins/unlesbar. */
    fun readVersion(audio: File): Int? {
        val f = fileFor(audio)
        if (!f.isFile) return null
        val head = runCatching {
            f.bufferedReader().use { r -> CharArray(128).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
        }.getOrNull() ?: return null
        VERSION_RE.find(head)?.let { return it.groupValues[1].toInt() }
        return read(audio)?.version // ungewöhnliches Format: vollständig lesen
    }

    fun write(audio: File, transcript: Transcript) {
        val target = fileFor(audio)
        val part = File(target.parentFile, target.name + PART)
        FileOutputStream(part).use { out ->
            out.write(json.encodeToString(Transcript.serializer(), transcript).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Transkript nicht schreibbar: ${target.name}")
        }
        notifyChanged()
    }

    fun delete(audio: File): Boolean {
        val ok = fileFor(audio).delete()
        notifyChanged()
        return ok
    }
}
