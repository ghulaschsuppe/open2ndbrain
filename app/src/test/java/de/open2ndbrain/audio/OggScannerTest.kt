package de.open2ndbrain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Testdatei: 2 s Sinus, mit ffmpeg/libopus erzeugt (16 kHz mono, 16 kbit/s, 13 Ogg-Seiten). */
class OggScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): ByteArray =
        javaClass.getResourceAsStream("/audio/sine_2s.ogg")!!.use { it.readBytes() }

    private fun fileOf(bytes: ByteArray, name: String = "a.ogg"): File =
        File(tmp.root, name).apply { writeBytes(bytes) }

    @Test
    fun crcMatchesReferenceVector() {
        // CRC-32/CKSUM ohne Abschluss-XOR: ~0x765E7680
        assertEquals(0x89A1897F.toInt(), OggCrc.compute("123456789".toByteArray()))
    }

    @Test
    fun completeFile() {
        val bytes = sample()
        val r = OggScanner.scan(fileOf(bytes))
        assertEquals(bytes.size.toLong(), r.validLength)
        assertEquals(13, r.pageCount)
        assertTrue(r.isOpus)
        assertEquals(312, r.preSkip)
        assertEquals(96312L, r.lastGranule)
        assertEquals(2000L, r.durationMs)
        assertTrue(r.hasAudio)
        assertFalse(r.needsTruncate)
    }

    @Test
    fun truncatedFileIsCutAtLastCompletePage() {
        val f = fileOf(sample().copyOf(3000))
        val r = OggScanner.scan(f)
        assertEquals(2943L, r.validLength)
        assertEquals(57600L, r.lastGranule)
        assertEquals((57600L - 312) / 48, r.durationMs)
        assertTrue(r.needsTruncate)
        OggScanner.truncate(f, r.validLength)
        val r2 = OggScanner.scan(f)
        assertEquals(2943L, f.length())
        assertFalse(r2.needsTruncate)
        assertEquals(r.lastGranule, r2.lastGranule)
    }

    @Test
    fun corruptedPageStopsScan() {
        val bytes = sample()
        bytes[1505 + 100] = (bytes[1505 + 100].toInt() xor 0x55).toByte() // Seite 5 beschädigt
        val r = OggScanner.scan(fileOf(bytes))
        assertEquals(1505L, r.validLength)
        assertEquals(28800L, r.lastGranule)
    }

    @Test
    fun zeroFilledTailIsIgnored() {
        val bytes = sample() + ByteArray(4096)
        val r = OggScanner.scan(fileOf(bytes))
        assertEquals(4992L, r.validLength)
        assertTrue(r.needsTruncate)
    }

    @Test
    fun headerOnlyHasNoAudio() {
        val r = OggScanner.scan(fileOf(sample().copyOf(121)))
        assertEquals(2, r.pageCount)
        assertFalse(r.hasAudio)
        assertFalse(OggScanner.scan(fileOf(ByteArray(0), "empty.ogg")).hasAudio)
        assertFalse(OggScanner.scan(fileOf(ByteArray(50), "junk.ogg")).hasAudio)
    }
}
