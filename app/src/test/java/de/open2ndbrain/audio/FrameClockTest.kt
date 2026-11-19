package de.open2ndbrain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

class FrameClockTest {
    @Test
    fun sampleClockIsSmoothAndMonotonicDespiteJitter() {
        val c = FrameClock(16_000)
        var wall = 1_000_000L
        val starts = ArrayList<Long>()
        repeat(500) { i ->
            wall += 20
            val jitter = if (i % 3 == 0) 15 else 0
            starts += c.frameStart(320, wall + jitter)
            assertFalse(c.lastWasJump)
        }
        for (i in 1 until starts.size) {
            val d = starts[i] - starts[i - 1]
            assertTrue("Abstand $d", d in 19..21)
        }
    }

    @Test
    fun driftIsCorrectedSlowly() {
        // Audiotakt 0,5 % zu langsam: Wanduhr läuft je Frame 20,1 ms weiter
        val c = FrameClock(16_000)
        var wall = 0.0
        var last = 0L
        repeat(30_000) { // 10 min
            wall += 20.1
            last = c.frameStart(320, wall.toLong())
            assertFalse(c.lastWasJump)
        }
        // ohne Nachführung läge die Abweichung bei 3 s, mit Nachführung bleibt sie klein
        assertTrue("Abweichung ${wall - 20 - last}", abs(wall - 20 - last) < 100)
    }

    @Test
    fun reanchorsOnForwardJump() {
        val c = FrameClock(16_000)
        assertEquals(10_000L, c.frameStart(320, 10_020))
        assertEquals(10_020L, c.frameStart(320, 10_040))
        val t = c.frameStart(320, 50_060) // Uhr 40 s vorgestellt
        assertTrue(c.lastWasJump)
        assertEquals(50_040L, t)
        assertEquals(50_060L, c.frameStart(320, 50_080))
        assertFalse(c.lastWasJump)
    }

    @Test
    fun smallBackwardJumpIsAbsorbed() {
        val c = FrameClock(16_000)
        var wall = 100_000L
        var prev = c.frameStart(320, wall)
        wall -= 1_500 // Uhr 1,5 s zurück
        repeat(50) {
            wall += 20
            val t = c.frameStart(320, wall)
            assertFalse(c.lastWasJump)
            assertTrue(t > prev) // bleibt monoton
            prev = t
        }
    }

    @Test
    fun largeBackwardJumpNeverCrossesHourStart() {
        val zone = ZoneId.of("Europe/Berlin")
        val hour = LocalDateTime.parse("2026-11-19T15:00").atZone(zone).toInstant().toEpochMilli()
        val c = FrameClock(16_000, hourStart = { RecordingLayout.hourStart(it, zone) })
        c.frameStart(320, hour + 10_020)
        c.frameStart(320, hour + 10_040)
        val t = c.frameStart(320, hour - 60_000) // Uhr 70 s zurück, in die vorige Stunde
        assertTrue(c.lastWasJump)
        assertEquals(hour, t)
        // innerhalb der Stunde ist ein Rücksprung erlaubt
        val c2 = FrameClock(16_000, hourStart = { RecordingLayout.hourStart(it, zone) })
        c2.frameStart(320, hour + 600_020)
        assertEquals(hour + 300_000L, c2.frameStart(320, hour + 300_020))
        assertTrue(c2.lastWasJump)
    }

    @Test
    fun resetAnchorsAgain() {
        val c = FrameClock(16_000)
        c.frameStart(320, 1_020)
        c.reset()
        assertEquals(5_000L, c.frameStart(320, 5_020))
    }
}
