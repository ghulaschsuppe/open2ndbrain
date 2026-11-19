package de.open2ndbrain.audio

import de.open2ndbrain.core.RecordingSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

class StorageJanitorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun make(day: String, base: String, part: Boolean = false): File {
        val dir = File(tmp.root, day).apply { mkdirs() }
        val p = if (part) ".part" else ""
        SidecarIo.writeAtomic(
            File(dir, "$base.json$p"),
            RecordingSidecar(hourStartEpochMs = RecordingLayout.hourStartFromBaseName(base, ZoneId.systemDefault())!!, bitrate = 16_000, discardSilence = true),
        )
        return File(dir, "$base.ogg$p").apply { writeBytes(ByteArray(10)) }
    }

    @Test
    fun deletesOldestCompletedUntilTargetReached() {
        val a = make("2026/11/17", "2026-11-17_10-00")
        val b = make("2026/11/18", "2026-11-18_10-00")
        val c = make("2026/11/19", "2026-11-19_10-00")
        val orphan = make("2026/11/16", "2026-11-16_10-00", part = true)
        val active = make("2026/11/19", "2026-11-19_11-00", part = true)
        RecordingSignals.markActive(active)
        try {
            // jede gelöschte Aufnahme gibt „400 MB“ frei: 900 → 1300 → 1700 (Ziel 1500)
            val deleted = StorageJanitor.cleanup(
                tmp.root,
                freeBytes = { 900L + 400L * listOf(a, b, c).count { !it.exists() } },
                lowBytes = 1000,
                targetBytes = 1500,
            )
            assertEquals(listOf(a, b), deleted)
            assertTrue(c.exists() && orphan.exists() && active.exists())
            assertTrue(!File(tmp.root, "2026/11/17").exists()) // leerer Ordner entfernt
        } finally {
            RecordingSignals.markInactive(active)
        }
    }

    @Test
    fun nothingHappensWithEnoughSpace() {
        val a = make("2026/11/17", "2026-11-17_10-00")
        assertTrue(StorageJanitor.cleanup(tmp.root, freeBytes = { 5_000 }, lowBytes = 1000, targetBytes = 1500).isEmpty())
        assertTrue(a.exists())
    }
}
