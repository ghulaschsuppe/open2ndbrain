package de.open2ndbrain.models

import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelFile
import de.open2ndbrain.core.ModelKind
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.models.DownloadLogic.ImportMatch
import de.open2ndbrain.models.DownloadLogic.ResponseAction
import de.open2ndbrain.models.DownloadLogic.ResumePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.URI

class DownloadLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun contentRange() {
        assertEquals(DownloadLogic.ContentRange(100, 999, 1000), DownloadLogic.parseContentRange("bytes 100-999/1000"))
        assertEquals(DownloadLogic.ContentRange(0, 9, null), DownloadLogic.parseContentRange("bytes 0-9/*"))
        assertNull(DownloadLogic.parseContentRange("bytes */1000"))
        assertNull(DownloadLogic.parseContentRange("bytes 10-5/100"))
        assertNull(DownloadLogic.parseContentRange(null))
    }

    @Test
    fun resumePlan() {
        assertEquals(ResumePlan.Fetch(0, false), DownloadLogic.planResume(0, 1000))
        assertEquals(ResumePlan.Fetch(400, false), DownloadLogic.planResume(400, 1000))
        assertEquals(ResumePlan.VerifyOnly, DownloadLogic.planResume(1000, 1000))
        assertEquals(ResumePlan.Fetch(0, true), DownloadLogic.planResume(1200, 1000))
    }

    @Test
    fun responseInterpretation() {
        val ok = DownloadLogic.ContentRange(400, 999, 1000)
        assertEquals(ResponseAction.Append, DownloadLogic.interpretResponse(206, 400, ok, 1000))
        // Server ignoriert Range → ganze Datei von vorn schreiben
        assertEquals(ResponseAction.Overwrite, DownloadLogic.interpretResponse(200, 400, null, 1000))
        // Falsches Startbyte → verwerfen
        assertEquals(ResponseAction.RestartFromZero, DownloadLogic.interpretResponse(206, 400, DownloadLogic.ContentRange(0, 999, 1000), 1000))
        // Andere Gesamtgröße → andere Datei auf dem Server
        assertEquals(ResponseAction.Fail(206), DownloadLogic.interpretResponse(206, 400, DownloadLogic.ContentRange(400, 1999, 2000), 1000))
        assertEquals(ResponseAction.AlreadyComplete, DownloadLogic.interpretResponse(416, 1000, null, 1000))
        assertEquals(ResponseAction.RestartFromZero, DownloadLogic.interpretResponse(416, 400, null, 1000))
        assertEquals(ResponseAction.Fail(404), DownloadLogic.interpretResponse(404, 0, null, 1000))
    }

    @Test
    fun redirectsOnlyHttps() {
        val base = URI("https://huggingface.co/x/resolve/main/encoder.onnx")
        assertEquals(URI("https://cdn-lfs.hf.co/a/b?x=1"), DownloadLogic.resolveRedirect(base, "https://cdn-lfs.hf.co/a/b?x=1"))
        assertEquals(URI("https://huggingface.co/other"), DownloadLogic.resolveRedirect(base, "/other"))
        assertThrows { DownloadLogic.resolveRedirect(base, "http://evil.example/file") }
        assertThrows { DownloadLogic.resolveRedirect(base, null) }
        assertTrue(DownloadLogic.isRedirect(302) && DownloadLogic.isRedirect(307) && !DownloadLogic.isRedirect(200))
    }

    @Test
    fun sha256() {
        val f = tmp.newFile("abc.txt").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DownloadLogic.sha256Hex(f))
    }

    @Test
    fun importMatching() {
        val f = ModelCatalog.SILERO_VAD.files[0]
        assertEquals(ImportMatch.Matched(ModelCatalog.SILERO_VAD, f), DownloadLogic.matchImport("umbenannt.bin", f.sha256.uppercase(), ModelCatalog.all))
        assertEquals(ImportMatch.WrongChecksum(ModelCatalog.SILERO_VAD, f), DownloadLogic.matchImport("silero_vad.onnx", "00", ModelCatalog.all))
        assertEquals(ImportMatch.Unknown, DownloadLogic.matchImport("foto.jpg", "00", ModelCatalog.all))
    }

    @Test
    fun overallProgress() {
        val spec = testSpec()
        assertEquals(0L, DownloadLogic.overallProgress(spec, emptySet(), null, 0))
        assertEquals(100L + 30, DownloadLogic.overallProgress(spec, setOf("a"), "b", 30))
        assertEquals(150L, DownloadLogic.overallProgress(spec, setOf("a"), "b", 999))
    }

    @Test
    fun storeInstallAndMarker() {
        val store = ModelStore(tmp.newFolder("models"))
        val spec = testSpec()
        assertFalse(store.isInstalled(spec))
        // Teildatei zählt als vorhanden, aber nicht als geprüft
        store.dir(spec).mkdirs()
        store.partFile(spec, spec.files[0]).writeBytes(ByteArray(40))
        assertEquals(40L, store.bytesPresent(spec))
        assertFalse(store.isVerified(spec, spec.files[0]))
        // Geprüfte Dateien installieren → Marker → installiert
        spec.files.forEach { mf ->
            val src = tmp.newFile("src-${mf.name}").apply { writeBytes(ByteArray(mf.sizeBytes.toInt())) }
            store.install(spec, mf, src)
        }
        assertFalse(store.partFile(spec, spec.files[0]).exists())
        assertFalse(store.isInstalled(spec)) // Marker fehlt noch
        store.writeMarker(spec)
        assertTrue(store.isInstalled(spec))
        // Geänderter Katalog (andere Prüfsumme) → nicht mehr installiert
        assertFalse(store.isInstalled(spec.copy(files = spec.files.map { it.copy(sha256 = "ff") })))
        store.delete(spec)
        assertFalse(store.isInstalled(spec))
        assertFalse(store.dir(spec).exists())
    }

    private fun testSpec() = ModelSpec(
        id = "test", displayName = "Test", kind = ModelKind.Vad,
        files = listOf(ModelFile("a", "https://x/a", "aa", 100), ModelFile("b", "https://x/b", "bb", 50)),
        description = "", languages = "", license = "", attribution = "", required = false,
    )

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            return
        }
        throw AssertionError("IOException erwartet")
    }
}
