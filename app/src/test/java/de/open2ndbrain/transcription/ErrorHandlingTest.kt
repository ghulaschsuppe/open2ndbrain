package de.open2ndbrain.transcription

import de.open2ndbrain.audio.FileRecordingRepository
import de.open2ndbrain.core.ModelLoadException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ErrorHandlingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun partialAudioPolicy() {
        val incomplete = MediaCodecPcmSource.partialPolicy(sidecarComplete = false, expectedMs = 3_600_000)
        assertTrue(incomplete(1_000)) // nach Absturz wiederhergestellt: alles Dekodierte verwenden
        val complete = MediaCodecPcmSource.partialPolicy(sidecarComplete = true, expectedMs = 100_000)
        assertFalse(complete(94_000))
        assertTrue(complete(95_000))
        val noSidecar = MediaCodecPcmSource.partialPolicy(sidecarComplete = null, expectedMs = 0)
        assertFalse(noSidecar(10_000)) // unbekannte Dauer: kein Teil-Audio akzeptieren
    }

    @Test
    fun missingModelFileIsModelLoadException() {
        try {
            requireModelFile(File(tmp.root, "fehlt.onnx"))
            fail("ModelLoadException erwartet")
        } catch (e: ModelLoadException) {
            assertTrue(e.message!!.contains("fehlt.onnx"))
        }
        val empty = File(tmp.root, "leer.onnx").apply { createNewFile() }
        try {
            requireModelFile(empty)
            fail("ModelLoadException erwartet")
        } catch (_: ModelLoadException) {
        }
    }

    @Test
    fun loadModelWrapsNativeErrorsButNotOom() {
        try {
            loadModel("Test") { throw UnsatisfiedLinkError("libx.so") }
            fail()
        } catch (e: ModelLoadException) {
            assertTrue(e.message!!.contains("native"))
        }
        try {
            loadModel("Test") { throw IllegalArgumentException("kaputt") }
            fail()
        } catch (e: ModelLoadException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
        try {
            loadModel("Test") { throw OutOfMemoryError("voll") }
            fail()
        } catch (_: OutOfMemoryError) {
            // unverändert
        }
    }

    @Test
    fun orphanTranscriptPartsAreCleanedUpAfter10Minutes() = runBlocking {
        val dir = File(tmp.root, "2026/11/19").apply { mkdirs() }
        val old = File(dir, "2026-11-19_10-00.transcript.json.part").apply { writeText("{") }
        val fresh = File(dir, "2026-11-19_11-00.transcript.json.part").apply { writeText("{") }
        val keep = File(dir, "2026-11-19_09-00.transcript.json").apply { writeText("{}") }
        val now = System.currentTimeMillis()
        old.setLastModified(now - 11 * 60_000)
        val repo = FileTranscriptRepository(tmp.root, FileRecordingRepository(tmp.root), now = { now })
        assertEquals(emptyList<Any>(), repo.pending())
        assertFalse(old.exists())
        assertTrue(fresh.exists()) // könnte gerade geschrieben werden
        assertTrue(keep.exists())
    }
}
