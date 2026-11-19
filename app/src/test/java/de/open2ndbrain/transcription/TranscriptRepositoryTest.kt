package de.open2ndbrain.transcription

import de.open2ndbrain.audio.FileRecordingRepository
import de.open2ndbrain.audio.RecordingLayout
import de.open2ndbrain.audio.RecordingSignals
import de.open2ndbrain.audio.SidecarIo
import de.open2ndbrain.core.RecordingSidecar
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class TranscriptRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.systemDefault()

    private fun make(base: String, part: Boolean = false): File {
        val p = RecordingLayout.parseBaseName(base)!!
        val hour = p.localHour.atZone(zone).toInstant().toEpochMilli()
        val dir = RecordingLayout.dayDir(tmp.root, hour, zone).apply { mkdirs() }
        val suffix = if (part) ".part" else ""
        SidecarIo.writeAtomic(
            File(dir, "$base.json$suffix"),
            RecordingSidecar(hourStartEpochMs = hour, bitrate = 16_000, discardSilence = true, complete = !part),
        )
        return File(dir, "$base.ogg$suffix").apply { writeBytes(ByteArray(100)) }
    }

    private fun transcript(audio: File, version: Int = Transcript.CURRENT_VERSION) = Transcript(
        version = version,
        audioFileName = audio.name,
        asrModelId = "asr",
        createdAtEpochMs = 1,
        audioDurationMs = 1_000,
        processingMs = 100,
        utterances = listOf(Utterance(0, 500, 1_000, "Hallo.", "s1")),
    )

    private fun repos(): Pair<FileRecordingRepository, FileTranscriptRepository> {
        val rec = FileRecordingRepository(tmp.root, zone = { zone })
        return rec to FileTranscriptRepository(tmp.root, rec, refreshMs = 60_000)
    }

    @Test
    fun pendingIsOldestFirstWithoutPartsAndOutdatedVersions() = runBlocking {
        val a = make("2026-11-18_09-00")
        val b = make("2026-11-19_14-00")
        val b2 = make("2026-11-19_14-00_b")
        val c = make("2026-11-19_15-00")
        val active = make("2026-11-19_16-00", part = true)
        RecordingSignals.markActive(active)
        try {
            val (_, t) = repos()
            TranscriptFiles.write(b, transcript(b))
            TranscriptFiles.write(c, transcript(c, version = 0)) // veraltet → erneut
            assertEquals(listOf(a, b2, c), t.pending().map { it.audio })
            assertEquals(Transcript.CURRENT_VERSION, TranscriptFiles.readVersion(b))
            assertNull(TranscriptFiles.readVersion(a))
        } finally {
            RecordingSignals.markInactive(active)
        }
    }

    @Test
    fun writeReadDeleteAndObserve() = runBlocking {
        val a = make("2026-11-19_14-00")
        val (_, t) = repos()
        val ch = Channel<Transcript?>(10)
        val job = launch(Dispatchers.IO) { t.observeTranscript(a).collect { ch.send(it) } }
        assertNull(withTimeout(5_000) { ch.receive() })
        TranscriptFiles.write(a, transcript(a))
        assertEquals("Hallo.", withTimeout(5_000) { ch.receive() }!!.utterances.single().text)
        assertEquals(File(a.parentFile, "2026-11-19_14-00.transcript.json"), TranscriptFiles.fileFor(a))
        assertFalse(File(a.parentFile, "2026-11-19_14-00.transcript.json.part").exists())
        t.delete(a)
        assertNull(withTimeout(5_000) { ch.receive() })
        job.cancel()
    }

    @Test
    fun transcriptSurvivesAudioDeletionAndBlocksNameReuse() = runBlocking {
        val a = make("2026-11-19_14-00")
        val (rec, t) = repos()
        TranscriptFiles.write(a, transcript(a))
        rec.delete(rec.scan().single())
        assertFalse(a.exists())
        assertEquals("Hallo.", TranscriptFiles.read(a)!!.utterances.single().text) // weiterhin lesbar
        assertTrue(t.pending().isEmpty())
        // Retention/Notlöschung lassen das Transkript ebenfalls stehen; der Name bleibt belegt
        val hour = LocalDateTime.parse("2026-11-19T14:00").atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-11-19_14-00_b", RecordingLayout.allocateBaseName(a.parentFile!!, hour, zone))
        // Sidecar-Summe liest das Transkript nicht als Sidecar
        assertEquals(0L, SidecarIo.storedMsInDir(a.parentFile!!))
    }

    @Test
    fun baseNameHandlesPartAndExtensions() {
        assertEquals("2026-11-19_14-00_b", TranscriptFiles.baseName("2026-11-19_14-00_b.ogg"))
        assertEquals("2026-11-19_14-00", TranscriptFiles.baseName("2026-11-19_14-00.m4a.part"))
    }
}
