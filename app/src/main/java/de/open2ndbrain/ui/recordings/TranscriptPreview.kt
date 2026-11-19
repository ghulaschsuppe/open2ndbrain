package de.open2ndbrain.ui.recordings

import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import de.open2ndbrain.core.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Kurzfassung eines Transkripts für die Zeitleiste: erste Zeilen und beteiligte Sprecher. */
@Immutable
data class TranscriptPreview(
    val text: String,
    /** Sprecher-IDs nach Redeanteil, häufigste zuerst. */
    val speakerIds: List<String>,
    val utteranceCount: Int,
) {
    companion object {
        private const val MAX_CHARS = 180

        fun of(t: Transcript): TranscriptPreview {
            val text = buildString {
                for (u in t.utterances) {
                    if (length >= MAX_CHARS) break
                    if (isNotEmpty()) append(' ')
                    append(u.text.trim())
                }
            }.let { if (it.length > MAX_CHARS) it.take(MAX_CHARS).trimEnd() + "…" else it }
            val speakers = t.utterances.mapNotNull { u -> u.speakerId?.let { it to u.durationMs } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, d) -> d.sum() }
                .entries.sortedByDescending { it.value }
                .map { it.key }
            return TranscriptPreview(text, speakers, t.utterances.size)
        }
    }
}

/**
 * Lädt Vorschauen nur für sichtbare Einträge (LazyColumn) und merkt sie sich je Datei und Änderungszeit.
 * So bleibt die Liste flüssig, ohne dass das ViewModel alle Transkripte einlesen muss.
 */
object TranscriptPreviewCache {
    private val cache = LruCache<String, Pair<Long, TranscriptPreview?>>(512)

    fun peek(audio: File): TranscriptPreview? {
        val f = TranscriptFiles.transcriptFileFor(audio)
        val hit = cache.get(f.path) ?: return null
        return hit.second.takeIf { hit.first == f.lastModified() }
    }

    fun load(audio: File): TranscriptPreview? {
        val f = TranscriptFiles.transcriptFileFor(audio)
        val modified = f.lastModified()
        cache.get(f.path)?.let { (m, p) -> if (m == modified) return p }
        val preview = if (modified == 0L) null else TranscriptFiles.decode(f)?.let(TranscriptPreview::of)
        cache.put(f.path, modified to preview)
        return preview
    }
}

@Composable
fun rememberTranscriptPreview(audio: File, hasTranscript: Boolean): TranscriptPreview? =
    produceState(initialValue = if (hasTranscript) TranscriptPreviewCache.peek(audio) else null, audio, hasTranscript) {
        value = if (hasTranscript) withContext(Dispatchers.IO) { TranscriptPreviewCache.load(audio) } else null
    }.value
