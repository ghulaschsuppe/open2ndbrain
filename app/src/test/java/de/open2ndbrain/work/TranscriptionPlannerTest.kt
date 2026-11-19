package de.open2ndbrain.work

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo.State
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.settings.SettingsKeys
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.recordings.TranscriptBadge
import de.open2ndbrain.ui.recordings.TranscriptFiles
import de.open2ndbrain.ui.recordings.TranscriptLine
import de.open2ndbrain.ui.recordings.currentLineIndex
import de.open2ndbrain.ui.speakers.SpeakerDirectory
import androidx.datastore.preferences.core.mutablePreferencesOf
import de.open2ndbrain.core.Utterance
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

class TranscriptionPlannerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun waitingReasons() {
        fun r(mode: TranscriptionMode = TranscriptionMode.Automatic, models: Boolean = true, pending: Int = 3, running: Boolean = false, charging: Boolean = false, low: Boolean = false) =
            TranscriptionPlanner.waitingReason(mode, models, pending, running, charging, low)
        assertNull(r(running = true))
        assertEquals(WaitingReason.Off, r(mode = TranscriptionMode.Off))
        assertEquals(WaitingReason.ModelMissing, r(models = false))
        assertNull(r(pending = 0))
        assertEquals(WaitingReason.WaitingForCharger, r(mode = TranscriptionMode.ChargingOnly))
        assertNull(r(mode = TranscriptionMode.ChargingOnly, charging = true))
        assertEquals(WaitingReason.BatteryLow, r(low = true))
        assertNull(r(low = true, charging = true))
        assertNull(r())
    }

    @Test
    fun workPolicy() {
        assertEquals(ExistingWorkPolicy.REPLACE, TranscriptionPlanner.policy(emptyList(), false))
        assertEquals(ExistingWorkPolicy.REPLACE, TranscriptionPlanner.policy(listOf(State.SUCCEEDED), false))
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, TranscriptionPlanner.policy(listOf(State.RUNNING), false))
        assertEquals(ExistingWorkPolicy.KEEP, TranscriptionPlanner.policy(listOf(State.RUNNING, State.BLOCKED), true))
        assertEquals(ExistingWorkPolicy.KEEP, TranscriptionPlanner.policy(listOf(State.ENQUEUED), false))
        assertEquals(ExistingWorkPolicy.REPLACE, TranscriptionPlanner.policy(listOf(State.ENQUEUED), true))
    }

    @Test
    fun realTimeFactorAndFormatting() {
        assertEquals(0.15f, TranscriptionPlanner.realTimeFactor(540_000, 3_600_000)!!, 1e-6f)
        assertNull(TranscriptionPlanner.realTimeFactor(10, 0))
        assertEquals("0,15×", Formatters.realTimeFactor(0.15f))
        assertEquals("42 %", Formatters.percent(0.427f))
        assertEquals("00:23:10", Formatters.stopwatch((23 * 60 + 10) * 1000L))
        assertEquals("01:02:03", Formatters.stopwatch((3600 + 2 * 60 + 3) * 1000L))
        val zone = ZoneId.of("Europe/Berlin")
        val t = LocalDateTime.of(2026, 11, 19, 14, 3, 12).atZone(zone).toInstant().toEpochMilli()
        assertEquals("14:03:12", Formatters.timeWithSeconds(t, zone))
        assertEquals("19.11.2026, 14:03", Formatters.dateTime(t, zone))
        assertEquals("14:03–15:33", Formatters.timeRange(t, t + 90 * 60_000L, zone))
    }

    @Test
    fun failuresBlockAfterThree() {
        val f = TranscriptionFailures(File(tmp.root, "f.json"))
        val audio = File("/a/x.ogg")
        repeat(2) { f.increment(audio) }
        assertFalse(f.isBlocked(audio))
        f.increment(audio)
        assertTrue(f.isBlocked(audio))
        // Persistiert
        assertTrue(TranscriptionFailures(File(tmp.root, "f.json")).isBlocked(audio))
        f.clear(audio)
        assertFalse(TranscriptionFailures(File(tmp.root, "f.json")).isBlocked(audio))
    }

    @Test
    fun failuresPruneMissingFiles() {
        val existing = tmp.newFile("da.ogg")
        val gone = File(tmp.root, "weg.ogg")
        val f = TranscriptionFailures(File(tmp.root, "p.json"))
        f.increment(existing)
        f.increment(gone)
        assertEquals(1, f.pruneMissing())
        assertEquals(1, f.count(existing))
        assertEquals(0, TranscriptionFailures(File(tmp.root, "p.json")).count(gone))
        assertEquals(0, f.pruneMissing())
    }

    @Test
    fun transcriptSettingsDefaults() {
        val s = SettingsKeys.toAppSettings(mutablePreferencesOf())
        assertEquals(TranscriptionMode.Automatic, s.transcriptionMode)
        assertTrue(s.speakerRecognition)
        assertEquals(4, s.transcriptionThreads)
        val custom = SettingsKeys.toAppSettings(
            mutablePreferencesOf(
                SettingsKeys.TRANSCRIPTION_MODE to "ChargingOnly",
                SettingsKeys.TRANSCRIPTION_THREADS to 2,
                SettingsKeys.SPEAKER_RECOGNITION to false,
            ),
        )
        assertEquals(TranscriptionMode.ChargingOnly, custom.transcriptionMode)
        assertEquals(2, custom.transcriptionThreads)
        assertFalse(custom.speakerRecognition)
        assertEquals(4, SettingsKeys.toAppSettings(mutablePreferencesOf(SettingsKeys.TRANSCRIPTION_THREADS to 3)).transcriptionThreads)
        assertEquals(TranscriptionMode.Automatic, SettingsKeys.toAppSettings(mutablePreferencesOf(SettingsKeys.TRANSCRIPTION_MODE to "x")).transcriptionMode)
    }

    @Test
    fun transcriptFileNamesAndBadges() {
        assertEquals("2026-11-19_14-00_b", TranscriptFiles.baseName("2026-11-19_14-00_b.ogg.part"))
        assertEquals(File("/a/2026-11-19_14-00.transcript.json"), TranscriptFiles.transcriptFileFor(File("/a/2026-11-19_14-00.ogg")))
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals(
            LocalDateTime.of(2026, 11, 19, 14, 0).atZone(zone).toInstant().toEpochMilli(),
            TranscriptFiles.hourStartFromBase("2026-11-19_14-00_c", zone),
        )
        assertNull(TranscriptFiles.hourStartFromBase("foto", zone))

        val rec = RecordingFile(File("/a/x.ogg"), null, 0, 0, 0, inProgress = false)
        val idle = TranscriptionStatus()
        assertEquals(TranscriptBadge.Waiting, TranscriptFiles.badge(rec, false, idle, false, false))
        assertEquals(TranscriptBadge.Done, TranscriptFiles.badge(rec, true, idle, false, false))
        assertEquals(TranscriptBadge.Failed, TranscriptFiles.badge(rec, false, idle, true, false))
        assertEquals(TranscriptBadge.None, TranscriptFiles.badge(rec, false, idle, false, true))
        assertEquals(TranscriptBadge.Transcribing, TranscriptFiles.badge(rec, false, TranscriptionStatus(running = true, currentFile = "x.ogg"), false, false))
        assertEquals(TranscriptBadge.None, TranscriptFiles.badge(rec.copy(inProgress = true), false, idle, false, false))
    }

    @Test
    fun orphanTranscripts() {
        val dir = tmp.newFolder("2026", "11", "19")
        File(dir, "2026-11-19_14-00.transcript.json").writeText(
            """{"audioFileName":"2026-11-19_14-00.ogg","asrModelId":"p","createdAtEpochMs":1,"audioDurationMs":5000,"processingMs":1,"utterances":[]}""",
        )
        File(dir, "2026-11-19_15-00.transcript.json").writeText("kaputt")
        val zone = ZoneId.of("Europe/Berlin")
        val files = TranscriptFiles.listAll(tmp.root)
        assertEquals(2, files.size)
        val orphans = TranscriptFiles.orphans(files, knownBases = emptySet(), zone = zone)
        assertEquals(listOf("2026-11-19_14-00.ogg"), orphans.map { it.audio.name })
        assertEquals(5000L, orphans.single().durationMs)
        assertTrue(TranscriptFiles.orphans(files, setOf("2026-11-19_14-00"), zone).isEmpty())
    }

    @Test
    fun currentUtterance() {
        val a = File("/a/a.ogg")
        val lines = listOf(TranscriptLine(a, Utterance(0, 2000, 0, "x")), TranscriptLine(a, Utterance(2000, 1000, 0, "y")))
        assertEquals(0, currentLineIndex(lines, a, 1999))
        assertEquals(1, currentLineIndex(lines, a, 2500))
        assertEquals(-1, currentLineIndex(lines, a, 3000))
        assertEquals(-1, currentLineIndex(lines, File("/a/b.ogg"), 100))
        assertEquals(-1, currentLineIndex(lines, null, 100))
    }

    @Test
    fun speakerColors() {
        assertEquals(0, SpeakerDirectory.colorIndex(1))
        assertEquals(7, SpeakerDirectory.colorIndex(8))
        assertEquals(0, SpeakerDirectory.colorIndex(9))
        assertEquals(7, SpeakerDirectory.colorIndex(0))
    }
}
