package de.open2ndbrain.ui

import de.open2ndbrain.ui.mictest.MicTestGrades
import org.junit.Assert.assertEquals
import org.junit.Test

class MicTestGradesTest {
    @Test
    fun noten() {
        assertEquals(1, MicTestGrades.grade(0.0))
        assertEquals(1, MicTestGrades.grade(5.0))
        assertEquals(2, MicTestGrades.grade(8.4))
        assertEquals(3, MicTestGrades.grade(19.0))
        assertEquals(4, MicTestGrades.grade(27.0))
        assertEquals(5, MicTestGrades.grade(60.0))
        assertEquals(0.75f, MicTestGrades.accuracy(25.0), 0.001f)
        assertEquals(0f, MicTestGrades.accuracy(140.0), 0.001f)
    }
}
