package de.open2ndbrain.ui

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.ui.settings.ModelRatings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRatingsTest {
    @Test
    fun katalogwerte() {
        val fast = ModelCatalog.DE_FASTCONFORMER.benchmark!!
        assertEquals(4, ModelRatings.accuracy(fast).level) // 19 % Alltag → gut
        assertEquals(5, ModelRatings.speed(fast).level) // 0,03× → sehr schnell
        val canary = ModelCatalog.CANARY_180M_DE.benchmark!!
        assertEquals(5, ModelRatings.accuracy(canary).level)
        assertEquals(1, ModelRatings.speed(canary).level) // 0,35× in Gesprächen → sehr langsam
        val parakeet = ModelCatalog.PARAKEET_V3.benchmark!!
        assertEquals(2, ModelRatings.accuracy(parakeet).level)
        assertEquals(3, ModelRatings.speed(parakeet).level)
    }

    @Test
    fun alleAsrModelleHabenEinstufung() {
        ModelCatalog.asrModels.forEach { m ->
            val b = m.benchmark ?: return@forEach
            assertTrue(ModelRatings.accuracy(b).level in 1..ModelRatings.MAX)
            assertTrue(ModelRatings.speed(b).level in 1..ModelRatings.MAX)
        }
    }
}
