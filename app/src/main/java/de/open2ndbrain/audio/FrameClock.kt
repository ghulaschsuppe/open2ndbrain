package de.open2ndbrain.audio

/**
 * Ordnet gelesenen Audio-Frames eine Wanduhrzeit zu.
 *
 * - Grundlage ist die Sample-Uhr (gleichmäßig, monoton).
 * - Kleine Abweichungen zur Wanduhr (Drift, Lese-Jitter, Rücksprünge < [maxBackJumpMs]) werden
 *   sanft nachgeführt: höchstens [maxSlewPerFrameMs] je Frame, Zeitstempel bleiben monoton.
 * - Größere Sprünge (vor > [maxForwardJumpMs], zurück > [maxBackJumpMs]) → neu verankern;
 *   [lastWasJump] meldet das, damit ein neues Segment beginnt.
 * - Ein Rücksprung führt nie vor den Beginn der Stunde des letzten Zeitstempels ([hourStart]),
 *   damit keine bereits abgeschlossene Stunde erneut geöffnet wird.
 */
internal class FrameClock(
    private val sampleRate: Int = 16_000,
    private val maxForwardJumpMs: Long = 1_000,
    private val maxBackJumpMs: Long = 2_000,
    private val maxSlewPerFrameMs: Double = 0.5,
    private val slewGain: Double = 0.01,
    private val hourStart: (Long) -> Long = { Long.MIN_VALUE },
) {
    private var anchorWallMs = 0.0
    private var samplesSinceAnchor = 0L
    private var anchored = false
    private var lastStart = Long.MIN_VALUE

    var lastWasJump = false
        private set

    /**
     * Liefert die Startzeit (epoch ms) eines gerade gelesenen Frames mit [samples] Samples.
     * [nowMs] = Wanduhr unmittelbar nach dem Lesen (= Ende des Frames).
     */
    fun frameStart(samples: Int, nowMs: Long): Long {
        val durMs = samples * 1000.0 / sampleRate
        lastWasJump = false
        if (!anchored) {
            anchor(nowMs - durMs)
        } else {
            val expectedEnd = anchorWallMs + (samplesSinceAnchor + samples) * 1000.0 / sampleRate
            val err = nowMs - expectedEnd // > 0: Wanduhr läuft voraus
            when {
                err > maxForwardJumpMs -> {
                    lastWasJump = true
                    anchor(nowMs - durMs)
                }
                err < -maxBackJumpMs -> {
                    lastWasJump = true
                    val floor = if (lastStart == Long.MIN_VALUE) Long.MIN_VALUE else hourStart(lastStart)
                    anchor(maxOf(nowMs - durMs, floor.toDouble()))
                }
                else -> anchorWallMs += (err * slewGain).coerceIn(-maxSlewPerFrameMs, maxSlewPerFrameMs)
            }
        }
        val start = (anchorWallMs + samplesSinceAnchor * 1000.0 / sampleRate).toLong()
        samplesSinceAnchor += samples
        lastStart = start
        return start
    }

    /** Neuer Strom (z. B. AudioRecord neu geöffnet): beim nächsten Frame neu verankern. */
    fun reset() {
        anchored = false
        lastWasJump = false
    }

    private fun anchor(wallMs: Double) {
        anchorWallMs = wallMs
        samplesSinceAnchor = 0
        anchored = true
    }
}
