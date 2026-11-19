package de.open2ndbrain.audio

/**
 * Gate über der VAD: entscheidet, welche Frames gespeichert werden.
 *
 * - [discardSilence] = false: alles wird durchgereicht.
 * - Sonst: Frames laufen durch einen Vorlauf-Ringpuffer ([preRollFrames], ~1 s). Beginnt Sprache,
 *   wird der Vorlauf mit ausgegeben (kein abgeschnittener Wortanfang). Nach Sprachende werden noch
 *   [postRollFrames] (~1,5 s) Nachlauf gespeichert. Beginnt Sprache wieder, bevor der Vorlauf
 *   Frames verloren hat, setzt der neue Block den alten lückenlos fort (kein neues Segment).
 *
 * Der Sink bekommt Arrays, die nur während des Aufrufs gültig sind (er muss kopieren).
 * Reine Logik ohne Android-Abhängigkeit; nicht threadsicher.
 */
internal class SpeechGate(
    private val frameSamples: Int,
    private val preRollFrames: Int = 50,
    private val postRollFrames: Int = 75,
    var discardSilence: Boolean = true,
    private val sink: Sink,
) {
    fun interface Sink {
        /** [segmentStart] = true: Frame folgt nicht lückenlos auf den zuletzt ausgegebenen. */
        fun onFrame(pcm: ShortArray, length: Int, epochMs: Long, segmentStart: Boolean)
    }

    private val ring = Array(preRollFrames.coerceAtLeast(1)) { ShortArray(frameSamples) }
    private val ringLen = IntArray(ring.size)
    private val ringTs = LongArray(ring.size)
    private var ringStart = 0
    private var ringCount = 0

    /** Seit dem letzten ausgegebenen Frame aus dem Ring verdrängte Frames. */
    private var droppedSinceEmit = 0L
    private var hasEmitted = false
    private var nextIsSegmentStart = true
    private var open = false
    private var hangover = 0

    /** Wird gerade gespeichert (Sprache + Nachlauf)? */
    val isOpen: Boolean get() = open || !discardSilence

    fun process(pcm: ShortArray, length: Int, epochMs: Long, speech: Boolean) {
        if (!discardSilence) {
            if (ringCount > 0) flushRing() // Umschalten während Stille: Vorlauf nicht verlieren
            open = false
            emit(pcm, length, epochMs)
            return
        }
        if (speech) {
            if (!open) {
                flushRing()
                open = true
            }
            hangover = postRollFrames
            emit(pcm, length, epochMs)
            return
        }
        if (open) {
            if (hangover > 0) {
                hangover--
                emit(pcm, length, epochMs)
                return
            }
            open = false
        }
        pushRing(pcm, length, epochMs)
    }

    /**
     * Lücke im Eingangsstrom (Pause, Mikrofon belegt, Neustart des AudioRecord, Uhrsprung):
     * der Vorlauf wird verworfen, der nächste ausgegebene Frame beginnt ein neues Segment.
     */
    fun discontinuity() {
        ringCount = 0
        ringStart = 0
        open = false
        hangover = 0
        nextIsSegmentStart = true
    }

    private fun emit(pcm: ShortArray, length: Int, epochMs: Long) {
        val start = nextIsSegmentStart || !hasEmitted || droppedSinceEmit > 0
        sink.onFrame(pcm, length, epochMs, start)
        hasEmitted = true
        nextIsSegmentStart = false
        droppedSinceEmit = 0
    }

    private fun flushRing() {
        for (k in 0 until ringCount) {
            val idx = (ringStart + k) % ring.size
            emit(ring[idx], ringLen[idx], ringTs[idx])
        }
        ringCount = 0
        ringStart = 0
    }

    private fun pushRing(pcm: ShortArray, length: Int, epochMs: Long) {
        val idx: Int
        if (ringCount < ring.size) {
            idx = (ringStart + ringCount) % ring.size
            ringCount++
        } else {
            idx = ringStart
            ringStart = (ringStart + 1) % ring.size
            droppedSinceEmit++
        }
        val n = minOf(length, frameSamples)
        System.arraycopy(pcm, 0, ring[idx], 0, n)
        ringLen[idx] = n
        ringTs[idx] = epochMs
    }
}
