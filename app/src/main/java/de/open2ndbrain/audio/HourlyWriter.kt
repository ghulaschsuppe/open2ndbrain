package de.open2ndbrain.audio

import android.util.Log
import de.open2ndbrain.core.RecordingSidecar
import java.io.File
import java.time.ZoneId

/**
 * Schreibt den (ggf. gegateten) PCM-Strom in stündliche Dateien samt Sidecar.
 *
 * - Datei wird erst beim ersten Frame geöffnet (keine leeren Dateien in stillen Stunden).
 * - Wechsel an der lokalen Stundengrenze anhand der Frame-Zeitstempel; der Eingangsstrom läuft weiter.
 * - Dateizeit läuft lückenlos (PTS = geschriebene Samples), die Abbildung auf die Wanduhr steht in
 *   den Segmenten des Sidecars.
 * - Sidecar als `.json.part`: beim Öffnen, bei Ereignissen, nach Segmentende (gedrosselt) und
 *   spätestens alle [sidecarIntervalMs].
 * - Abschluss: Encoder beenden → Sidecar (complete) als `.json.part` → Audio umbenennen →
 *   Sidecar umbenennen. So ist jeder Absturzzeitpunkt von [PartFileRecovery] behandelbar.
 *
 * Läuft ausschließlich auf dem Writer-Thread der Engine (nicht threadsicher).
 * Fehler beim Kodieren werden als Exception an den Aufrufer weitergereicht (→ [abort]).
 */
internal class HourlyWriter(
    private val encoderFactory: AudioFileEncoderFactory,
    private val preferredFormat: () -> AudioFileFormat,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = System::currentTimeMillis,
    private val listener: Listener = Listener { _, _ -> },
    private val sampleRate: Int = 16_000,
    private val sidecarIntervalMs: Long = 60_000,
    private val segmentFlushMinIntervalMs: Long = 10_000,
    private val hourCloseGraceMs: Long = 3_000,
) {
    fun interface Listener {
        /** Datei geöffnet ([closed] = false) bzw. abgeschlossen/verworfen ([closed] = true). */
        fun onFileChanged(file: File?, closed: Boolean)
    }

    data class Settings(val root: File, val bitrateKbps: Int, val discardSilence: Boolean)

    /** Wirkt ab der nächsten Datei. */
    @Volatile
    var settings: Settings? = null

    private class OpenFile(
        val dir: File,
        val base: String,
        val audioPart: File,
        val jsonPart: File,
        val format: AudioFileFormat,
        val encoder: AudioFileEncoder,
        val hourStart: Long,
        val nextHour: Long,
        val settings: Settings,
    ) {
        var samples = 0L
        val segments = ArrayList<RecordingSidecar.Segment>()
        val events = ArrayList<RecordingSidecar.Event>()
        var segStartSamples = 0L
        var segStartEpoch = 0L
        var segInput: String? = null
        var segOpen = false
        var lastSidecarWrite = 0L
        var dirty = false
    }

    private var file: OpenFile? = null
    private val pending = ArrayList<RecordingSidecar.Event>()

    val currentFile: File? get() = file?.audioPart
    val currentBaseName: String? get() = file?.base
    val currentHourStart: Long? get() = file?.hourStart

    /** In der aktuellen Datei gespeicherte Dauer in ms. */
    val currentStoredMs: Long get() = file?.let { it.samples * 1000 / sampleRate } ?: 0

    fun write(pcm: ShortArray, length: Int, epochMs: Long, segmentStart: Boolean, input: String?) {
        var f = file
        if (f != null && (epochMs >= f.nextHour || epochMs < f.hourStart)) {
            close(null)
            f = null
        }
        if (f == null) f = open(epochMs) ?: return
        if (segmentStart || !f.segOpen || input != f.segInput) {
            endSegment(f)
            f.segOpen = true
            f.segStartSamples = f.samples
            f.segStartEpoch = epochMs
            f.segInput = input
        }
        f.encoder.write(pcm, 0, length, f.samples * 1_000_000 / sampleRate)
        f.samples += length
    }

    /** Ereignis ins aktuelle Sidecar; ohne offene Datei vormerken (für die nächste Datei derselben Stunde). */
    fun addEvent(type: String, detail: String? = null, atMs: Long = now()) {
        val ev = RecordingSidecar.Event(atMs, type, detail)
        val f = file
        if (f != null) {
            f.events += ev
            writeSidecar(f, complete = false)
        } else {
            pending += ev
            while (pending.size > MAX_PENDING) pending.removeAt(0)
        }
    }

    /** Aktuelle Datei abschließen; die nächste geschriebene Audiodatei wird eine neue Teildatei. */
    fun split(eventType: String? = null, detail: String? = null) {
        val atMs = now()
        if (eventType != null) {
            file?.events?.add(RecordingSidecar.Event(atMs, eventType, detail))
            pending += RecordingSidecar.Event(atMs, eventType, detail)
        }
        close(null)
    }

    /**
     * Manuelle Teilung (z. B. Meeting-Ende): offene Datei sauber abschließen; der nächste Frame beginnt
     * nahtlos eine neue Teildatei. Ereignis `split` in beiden Dateien. Ohne offene Datei: nichts tun.
     */
    fun splitNow(reason: String) {
        if (file == null) return
        split(EVENT_SPLIT, reason)
    }

    /**
     * Periodisch aufrufen: Encoder-Ausgang leeren, Sidecar sichern und – wenn [idle] (keine Frames mehr
     * in der Warteschlange) – die Datei abschließen, sobald die Stunde seit [hourCloseGraceMs] vorbei ist.
     * So wird die Datei auch nach Stille pünktlich fertig, nicht erst beim nächsten Sprach-Frame.
     */
    fun tick(idle: Boolean = true) {
        val f = file ?: return
        val t = now()
        if (idle && t >= f.nextHour + hourCloseGraceMs) {
            Log.i(TAG, "Stunde vorbei ohne neue Frames – ${f.base} wird abgeschlossen")
            close(null)
            return
        }
        f.encoder.flush()
        if (t - f.lastSidecarWrite >= sidecarIntervalMs ||
            (f.dirty && t - f.lastSidecarWrite >= segmentFlushMinIntervalMs)
        ) {
            writeSidecar(f, complete = false)
        }
    }

    /** Sauber abschließen. */
    fun close(eventType: String?, detail: String? = null) {
        val f = file ?: run {
            if (eventType != null) addEvent(eventType, detail)
            return
        }
        if (eventType != null) f.events += RecordingSidecar.Event(now(), eventType, detail)
        file = null
        endSegment(f)
        val ok = runCatching { f.encoder.finish() }
            .onFailure { Log.e(TAG, "Encoder-Abschluss fehlgeschlagen", it) }
            .getOrDefault(false)
        finalizeFile(f, ok)
    }

    /** Nach einem Fehler: Datei so gut wie möglich retten, ohne auf den Encoder zu warten. */
    fun abort(reason: String) {
        val f = file ?: return
        file = null
        f.events += RecordingSidecar.Event(now(), "error", reason)
        endSegment(f)
        runCatching { f.encoder.abort() }
        finalizeFile(f, ok = false)
    }

    private fun open(epochMs: Long): OpenFile? {
        val s = settings ?: return null
        val z = zone()
        val hourStart = RecordingLayout.hourStart(epochMs, z)
        val nextHour = RecordingLayout.nextHourStart(hourStart, z)
        val dir = RecordingLayout.dayDir(s.root, hourStart, z)
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Ordner nicht anlegbar: $dir")
        val base = RecordingLayout.allocateBaseName(dir, hourStart, z)

        var format = preferredFormat()
        var audioPart = File(dir, "$base.${format.extension}${RecordingLayout.PART}")
        val encoder = try {
            encoderFactory.create(audioPart, format, s.bitrateKbps)
        } catch (t: Throwable) {
            if (format != AudioFileFormat.OPUS_OGG) throw t
            Log.w(TAG, "Opus-Encoder nicht nutzbar, Fallback AAC/M4A", t)
            audioPart.delete()
            format = AudioFileFormat.AAC_M4A
            audioPart = File(dir, "$base.${format.extension}${RecordingLayout.PART}")
            encoderFactory.create(audioPart, format, s.bitrateKbps)
        }
        val jsonPart = File(dir, "$base.${RecordingLayout.EXT_JSON}${RecordingLayout.PART}")
        val f = OpenFile(dir, base, audioPart, jsonPart, format, encoder, hourStart, nextHour, s)
        RecordingSignals.markActive(audioPart, jsonPart)
        pending.filter { it.atEpochMs in hourStart until nextHour }.let { f.events += it }
        pending.clear()
        file = f
        runCatching { writeSidecar(f, complete = false) }
            .onFailure { Log.w(TAG, "Sidecar nicht schreibbar", it) }
        Log.i(TAG, "Neue Datei ${audioPart.path}")
        RecordingSignals.notifyChanged()
        listener.onFileChanged(audioPart, closed = false)
        return f
    }

    private fun endSegment(f: OpenFile) {
        if (!f.segOpen) return
        f.segOpen = false
        val durSamples = f.samples - f.segStartSamples
        if (durSamples <= 0) return
        f.segments += RecordingSidecar.Segment(
            fileOffsetMs = f.segStartSamples * 1000 / sampleRate,
            startEpochMs = f.segStartEpoch,
            durationMs = durSamples * 1000 / sampleRate,
            input = f.segInput,
        )
        f.dirty = true
    }

    private fun sidecarOf(f: OpenFile, complete: Boolean): RecordingSidecar {
        val segs = ArrayList(f.segments)
        if (f.segOpen && f.samples > f.segStartSamples) {
            segs += RecordingSidecar.Segment(
                fileOffsetMs = f.segStartSamples * 1000 / sampleRate,
                startEpochMs = f.segStartEpoch,
                durationMs = (f.samples - f.segStartSamples) * 1000 / sampleRate,
                input = f.segInput,
            )
        }
        return RecordingSidecar(
            hourStartEpochMs = f.hourStart,
            codec = f.format.codec,
            container = f.format.container,
            bitrate = f.settings.bitrateKbps * 1000,
            discardSilence = f.settings.discardSilence,
            segments = segs,
            events = ArrayList(f.events),
            complete = complete,
        )
    }

    private fun writeSidecar(f: OpenFile, complete: Boolean) {
        SidecarIo.writeAtomic(f.jsonPart, sidecarOf(f, complete))
        f.lastSidecarWrite = now()
        f.dirty = false
    }

    private fun finalizeFile(f: OpenFile, ok: Boolean) {
        try {
            if (f.samples == 0L) {
                f.audioPart.delete()
                f.jsonPart.delete()
                Log.i(TAG, "Leere Datei ${f.audioPart.name} verworfen")
                return
            }
            var complete = ok
            if (!ok && f.format == AudioFileFormat.OPUS_OGG && f.audioPart.exists()) {
                // Reparatur wie nach einem Absturz: bis zur letzten gültigen Seite behalten.
                val r = runCatching { OggScanner.scan(f.audioPart) }.getOrNull()
                if (r != null && !r.hasAudio) {
                    f.audioPart.delete()
                    f.jsonPart.delete()
                    Log.w(TAG, "Datei ${f.audioPart.name} enthielt kein gültiges Audio – verworfen")
                    return
                }
                if (r != null && r.needsTruncate) OggScanner.truncate(f.audioPart, r.validLength)
                complete = false
            }
            val audioFinal = File(f.dir, "${f.base}.${f.format.extension}")
            val jsonFinal = File(f.dir, "${f.base}.${RecordingLayout.EXT_JSON}")
            writeSidecar(f, complete)
            if (!f.audioPart.renameTo(audioFinal)) {
                Log.e(TAG, "Umbenennen fehlgeschlagen: ${f.audioPart.name}")
                return // .part bleibt, Wiederherstellung übernimmt beim nächsten Start
            }
            if (!f.jsonPart.renameTo(jsonFinal)) Log.e(TAG, "Umbenennen fehlgeschlagen: ${f.jsonPart.name}")
            Log.i(TAG, "Datei abgeschlossen: ${audioFinal.name} (complete=$complete, ${f.samples * 1000 / sampleRate} ms)")
        } catch (t: Throwable) {
            Log.e(TAG, "Abschluss von ${f.base} fehlgeschlagen", t)
        } finally {
            RecordingSignals.markInactive(f.audioPart, f.jsonPart)
            RecordingSignals.notifyChanged()
            listener.onFileChanged(f.audioPart, closed = true)
        }
    }

    companion object {
        const val EVENT_SPLIT = "split"
        private const val TAG = "O2B-Audio"
        private const val MAX_PENDING = 50
    }
}
