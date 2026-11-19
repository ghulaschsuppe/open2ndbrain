package de.open2ndbrain.audio

import kotlin.math.abs

/**
 * Ordnet gelesenen Audio-Frames eine Wanduhrzeit zu.
 *
 * Innerhalb eines Stroms zählt die Sample-Uhr (gleichmäßig, keine Rundungssprünge). Weicht sie
 * mehr als [maxDriftMs] von der Wanduhr ab (Drift, NTP-Korrektur, manuell verstellte Uhr, Stau),
 * wird neu verankert; [lastWasJump] meldet das, damit das Gate ein neues Segment beginnt.
 */
internal class FrameClock(
    private val sampleRate: Int = 16_000,
    private val maxDriftMs: Long = 1_000,
) {
    private var anchorWallMs = 0L
    private var samplesSinceAnchor = 0L
    private var anchored = false

    var lastWasJump = false
        private set

    /**
     * Liefert die Startzeit (epoch ms) eines gerade gelesenen Frames mit [samples] Samples.
     * [nowMs] = Wanduhr unmittelbar nach dem Lesen (= Ende des Frames).
     */
    fun frameStart(samples: Int, nowMs: Long): Long {
        val durMs = samples * 1000L / sampleRate
        if (!anchored) {
            anchor(nowMs - durMs)
            lastWasJump = false
        } else {
            val expectedEnd = anchorWallMs + (samplesSinceAnchor + samples) * 1000L / sampleRate
            lastWasJump = abs(expectedEnd - nowMs) > maxDriftMs
            if (lastWasJump) anchor(nowMs - durMs)
        }
        val start = anchorWallMs + samplesSinceAnchor * 1000L / sampleRate
        samplesSinceAnchor += samples
        return start
    }

    /** Neuer Strom (z. B. AudioRecord neu geöffnet): beim nächsten Frame neu verankern. */
    fun reset() {
        anchored = false
        lastWasJump = false
    }

    private fun anchor(wallMs: Long) {
        anchorWallMs = wallMs
        samplesSinceAnchor = 0
        anchored = true
    }
}
