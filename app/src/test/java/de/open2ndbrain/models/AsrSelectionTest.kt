package de.open2ndbrain.models

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.work.Retranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AsrSelectionTest {
    private val def = ModelCatalog.DEFAULT_ASR_ID
    private val parakeet = ModelCatalog.PARAKEET_V3.id
    private val vad = ModelCatalog.SILERO_VAD.id

    @Test
    fun effectiveModel() {
        // Neuinstallation ohne Modelle → Standard (fehlt)
        assertEquals(def, AsrSelection.effective(null, emptySet()))
        // Bestand: nur Parakeet installiert, nie gewählt → Parakeet läuft weiter
        assertEquals(parakeet, AsrSelection.effective(null, setOf(parakeet)))
        // Standard gewählt, lädt noch → bis dahin Parakeet
        assertEquals(parakeet, AsrSelection.effective(def, setOf(parakeet)))
        assertEquals(def, AsrSelection.effective(def, setOf(parakeet, def)))
        // Gewähltes Modell installiert
        assertEquals(parakeet, AsrSelection.effective(parakeet, setOf(parakeet, def)))
        // Unbekannte ID → Standard
        assertEquals(def, AsrSelection.effective("gibt-es-nicht", setOf(def)))
    }

    @Test
    fun readiness() {
        fun st(vararg ids: String) = ids.associateWith { ModelState.Installed(File("/m/$it")) as ModelState }
        assertTrue(AsrSelection.ready(st(parakeet, vad)))
        assertFalse(AsrSelection.ready(st(parakeet)))
        assertFalse(AsrSelection.ready(st(vad)))
        assertTrue(AsrSelection.ready(st(def, vad)))
    }

    @Test
    fun upgradeSuggestionOnlyForExistingInstalls() {
        assertEquals(def, AsrSelection.upgradeSuggestion(null, false, setOf(parakeet))?.id)
        assertNull(AsrSelection.upgradeSuggestion(null, true, setOf(parakeet))) // ausgeblendet
        assertNull(AsrSelection.upgradeSuggestion(parakeet, false, setOf(parakeet))) // bewusst gewählt
        assertNull(AsrSelection.upgradeSuggestion(null, false, setOf(parakeet, def))) // schon da
        assertNull(AsrSelection.upgradeSuggestion(null, false, emptySet())) // Neuinstallation
    }

    @Test
    fun recommendedSetAndEstimate() {
        val ids = AsrSelection.RECOMMENDED.map { it.id }
        assertEquals(listOf(def, vad, ModelCatalog.WESPEAKER_RESNET34.id), ids)
        assertEquals(30_000L, AsrSelection.estimateMs(100_000, 0.3f, null))
        val spec = ModelCatalog.byId(def)!!
        assertEquals((100_000 * spec.benchmark!!.realTimeFactor * AsrSelection.PHONE_FACTOR).toLong(), AsrSelection.estimateMs(100_000, null, spec))
    }

    @Test
    fun retranscribeCandidates() {
        val day = 24L * 3_600_000
        val now = 10 * day
        val dir = java.nio.file.Files.createTempDirectory("rt").toFile()
        fun rec(name: String, hour: Long, inProgress: Boolean = false) =
            RecordingFile(File(dir, name).apply { writeText("x") }, null, hour, 1000, 1, inProgress)
        val recent = rec("a.ogg", now - day)
        val old = rec("b.ogg", now - 5 * day)
        val running = rec("c.ogg", now - 1000, inProgress = true)
        val noTranscript = rec("d.ogg", now - day)
        val res = Retranscription.candidates(listOf(recent, old, running, noTranscript), days = 3, now = now) { it != noTranscript }
        assertEquals(listOf(recent), res)
        dir.deleteRecursively()
    }
}
