package de.open2ndbrain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameClockTest {
    @Test
    fun sampleClockIsSmoothDespiteJitter() {
        val c = FrameClock(16_000)
        var wall = 1_000_000L
        val starts = ArrayList<Long>()
        repeat(100) { i ->
            wall += 20
            val jitter = if (i % 3 == 0) 15 else 0
            starts += c.frameStart(320, wall + jitter)
            assertFalse(c.lastWasJump)
        }
        for (i in 1 until starts.size) assertEquals(20L, starts[i] - starts[i - 1])
    }

    @Test
    fun reanchorsOnClockJump() {
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
    fun resetAnchorsAgain() {
        val c = FrameClock(16_000)
        c.frameStart(320, 1_020)
        c.reset()
        assertEquals(5_000L, c.frameStart(320, 5_020))
    }
}
