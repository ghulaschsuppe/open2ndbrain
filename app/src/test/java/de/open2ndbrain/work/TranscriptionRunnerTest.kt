package de.open2ndbrain.work

import de.open2ndbrain.core.ModelLoadException
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.settings.TranscriptionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranscriptionRunnerTest {

    /** Fake-Abhängigkeiten: Warteschlange im Speicher, Transcriber blockiert optional, bis er freigegeben wird. */
    private class FakeDeps(files: List<String>) : TranscriptionRunner.Deps {
        val queue = files.map { RecordingFile(File("/a/$it"), null, 0, 1000, 1, false) }.toMutableList()
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var status = TranscriptionStatus()
        var gate: CountDownLatch? = null
        var entered: CompletableDeferred<Unit>? = null
        var failWith: ((String) -> Exception?) = { null }
        var created = 0
        var closed = 0

        var modelFor: (String) -> String = { "a" }
        override suspend fun configFor(rec: RecordingFile, mode: TranscriptionRunner.Mode) = TranscriberConfig(File("/m/" + modelFor(rec.audio.name)), File("/v"), null, 2)
        override fun newTranscriber(config: TranscriberConfig): Transcriber {
            created++
            return object : Transcriber {
                override fun transcribe(recording: RecordingFile, onProgress: (Float) -> Unit): Transcript {
                    entered?.complete(Unit)
                    gate?.await(5, TimeUnit.SECONDS)
                    failWith(recording.audio.name)?.let { throw it }
                    onProgress(1f)
                    synchronized(queue) { queue.remove(recording) }
                    return Transcript(audioFileName = recording.audio.name, asrModelId = "x", createdAtEpochMs = 0, audioDurationMs = 1000, processingMs = 200, utterances = emptyList())
                }

                override fun close() {
                    closed++
                }
            }
        }

        val nightQueue = mutableListOf<RecordingFile>()
        override suspend fun pending(mode: TranscriptionRunner.Mode) = synchronized(queue) {
            if (mode == TranscriptionRunner.Mode.Night) nightQueue.toList() else queue.toList()
        }
        override fun updateStatus(transform: (TranscriptionStatus) -> TranscriptionStatus) {
            status = transform(status)
        }
        override fun onTranscribed(rec: RecordingFile, transcript: Transcript, loadMs: Long, threads: Int, origin: TranscriptionRunner.Origin, mode: TranscriptionRunner.Mode) {
            synchronized(queue) { nightQueue.remove(rec) }
            done += rec.audio.name
        }
        override fun onFailed(rec: RecordingFile, error: Exception, mode: TranscriptionRunner.Mode) {
            failed += rec.audio.name
            synchronized(queue) { queue.remove(rec) }
        }
        override fun text(kind: TranscriptionRunner.Text) = kind.name
    }

    @Test
    fun processesQueueInOrderAndCloses() = runBlocking {
        val deps = FakeDeps(listOf("a.ogg", "b.ogg"))
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        val result = r.run(TranscriptionRunner.Origin.Service)
        assertEquals(TranscriptionRunner.Result.Done(2), result)
        assertEquals(listOf("a.ogg", "b.ogg"), deps.done)
        assertEquals(1, deps.created) // Modelle einmal laden
        assertEquals(1, deps.closed)
        assertFalse(deps.status.running)
        assertEquals(0.2f, deps.status.lastRealTimeFactor!!, 1e-6f)
        assertNull(r.holder)
    }

    @Test
    fun workerSkipsWhileServiceRunsAndServiceWaitsForWorker() = runBlocking {
        val deps = FakeDeps(listOf("a.ogg"))
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        deps.gate = CountDownLatch(1)
        deps.entered = CompletableDeferred()
        val service = async(Dispatchers.Default) { r.run(TranscriptionRunner.Origin.Service) }
        withTimeout(5_000) { deps.entered!!.await() }
        assertEquals(TranscriptionRunner.Origin.Service, r.holder)
        // Worker darf nicht parallel rechnen
        assertEquals(TranscriptionRunner.Result.Skipped, r.run(TranscriptionRunner.Origin.Worker))
        deps.gate!!.countDown()
        assertEquals(TranscriptionRunner.Result.Done(1), service.await())

        // Umgekehrt: Dienst wartet, bis der Worker fertig ist
        deps.queue += RecordingFile(File("/a/b.ogg"), null, 0, 1000, 1, false)
        deps.gate = CountDownLatch(1)
        deps.entered = CompletableDeferred()
        val worker = async(Dispatchers.Default) { r.run(TranscriptionRunner.Origin.Worker) }
        withTimeout(5_000) { deps.entered!!.await() }
        val svc = async(Dispatchers.Default) { r.run(TranscriptionRunner.Origin.Service) }
        assertEquals(TranscriptionRunner.Origin.Worker, r.holder)
        deps.gate!!.countDown()
        assertEquals(TranscriptionRunner.Result.Done(1), worker.await())
        assertEquals(TranscriptionRunner.Result.Done(0), svc.await())
    }

    @Test
    fun errorsAreClassified() = runBlocking {
        val deps = FakeDeps(listOf("bad.ogg", "ok.ogg"))
        deps.failWith = { if (it == "bad.ogg") IOException("kaputt") else null }
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        assertEquals(TranscriptionRunner.Result.Done(1), r.run(TranscriptionRunner.Origin.Worker))
        assertEquals(listOf("bad.ogg"), deps.failed)
        assertEquals(listOf("ok.ogg"), deps.done)

        val deps2 = FakeDeps(listOf("x.ogg", "y.ogg"))
        deps2.failWith = { ModelLoadException("Modell") }
        val r2 = TranscriptionRunner(deps2, Dispatchers.IO)
        assertEquals(TranscriptionRunner.Result.ModelBroken, r2.run(TranscriptionRunner.Origin.Service))
        assertTrue(deps2.failed.isEmpty()) // Modellfehler zählt nicht als Fehlversuch der Datei
        assertEquals("ModelBroken", deps2.status.lastError)
        assertEquals(1, deps2.closed)
    }

    @Test
    fun modelChangeReloadsTranscriber() = runBlocking {
        val deps = FakeDeps(listOf("a.ogg", "b.ogg", "c.ogg"))
        deps.modelFor = { if (it == "b.ogg") "other" else "a" }
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        assertEquals(TranscriptionRunner.Result.Done(3), r.run(TranscriptionRunner.Origin.Service))
        // a (Modell a) → b (anderes Modell: neu laden) → c (wieder a: neu laden)
        assertEquals(3, deps.created)
        assertEquals(3, deps.closed)
    }

    @Test
    fun nightModeYieldsToNewRecordings() = runBlocking {
        val deps = FakeDeps(emptyList())
        deps.nightQueue += RecordingFile(File("/a/old1.ogg"), null, 0, 1000, 1, false)
        deps.nightQueue += RecordingFile(File("/a/old2.ogg"), null, 0, 1000, 1, false)
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        assertEquals(TranscriptionRunner.Result.Done(2), r.run(TranscriptionRunner.Origin.Service, mode = TranscriptionRunner.Mode.Night))
        // Wartet eine neue Aufnahme, hat sie Vorrang: Nachtlauf verarbeitet nichts
        deps.nightQueue += RecordingFile(File("/a/old3.ogg"), null, 0, 1000, 1, false)
        deps.queue += RecordingFile(File("/a/new.ogg"), null, 0, 1000, 1, false)
        assertEquals(TranscriptionRunner.Result.Done(0), r.run(TranscriptionRunner.Origin.Service, mode = TranscriptionRunner.Mode.Night))
    }

    @Test
    fun exclusiveWaitsForRunningFileThenBlocksOthers() = runBlocking {
        val deps = FakeDeps(listOf("a.ogg", "b.ogg"))
        deps.gate = CountDownLatch(1)
        deps.entered = CompletableDeferred()
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        val service = async(Dispatchers.Default) { r.run(TranscriptionRunner.Origin.Service) }
        withTimeout(5_000) { deps.entered!!.await() }
        val test = async(Dispatchers.Default) { r.exclusive { r.holder } }
        withTimeout(5_000) { while (!r.exclusivePending) kotlinx.coroutines.delay(10) }
        deps.gate!!.countDown()
        // Der Dienst hört nach der aktuellen Datei auf (b bleibt offen), dann läuft der exklusive Block
        assertEquals(TranscriptionRunner.Result.Done(1), service.await())
        assertEquals(TranscriptionRunner.Origin.MicTest, test.await())
        assertNull(r.holder)
    }

    @Test
    fun forceIsClearedWhenQueueEmpty() = runBlocking {
        val deps = FakeDeps(listOf("a.ogg"))
        val r = TranscriptionRunner(deps, Dispatchers.IO)
        r.requestForce()
        assertTrue(r.forceRequested)
        r.run(TranscriptionRunner.Origin.Service)
        assertFalse(r.forceRequested)
    }

    @Test
    fun runDecisions() {
        fun d(mode: TranscriptionMode = TranscriptionMode.Automatic, ready: Boolean = true, charging: Boolean = false, pct: Int = 80, force: Boolean = false) =
            TranscriptionPlanner.runDecision(mode, ready, charging, pct, force)
        assertEquals(RunDecision.Run, d())
        assertEquals(RunDecision.Wait(WaitingReason.ModelMissing), d(ready = false, force = true))
        assertEquals(RunDecision.Wait(WaitingReason.Off), d(mode = TranscriptionMode.Off))
        assertEquals(RunDecision.Run, d(mode = TranscriptionMode.Off, force = true))
        assertEquals(RunDecision.Wait(WaitingReason.WaitingForCharger), d(mode = TranscriptionMode.ChargingOnly))
        assertEquals(RunDecision.Run, d(mode = TranscriptionMode.ChargingOnly, charging = true))
        assertEquals(RunDecision.Run, d(mode = TranscriptionMode.ChargingOnly, force = true))
        assertEquals(RunDecision.Wait(WaitingReason.BatteryLow), d(pct = 19))
        assertEquals(RunDecision.Wait(WaitingReason.BatteryLow), d(pct = 19, force = true))
        assertEquals(RunDecision.Run, d(pct = 19, charging = true))
        assertEquals(RunDecision.Run, d(pct = 20))
        assertEquals(
            WaitingReason.WaitingForSegment,
            TranscriptionPlanner.waitingReason(TranscriptionMode.Automatic, true, 0, false, false, false, segmentRecording = true),
        )
        assertNull(TranscriptionPlanner.waitingReason(TranscriptionMode.Automatic, true, 0, false, false, false, segmentRecording = false))
    }

    @Test
    fun intervalBoundaries() {
        val zone = ZoneId.of("Europe/Berlin")
        fun at(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 11, 19, h, m, s).atZone(zone).toInstant().toEpochMilli()
        assertEquals(at(14, 15), TranscriptionPlanner.nextBoundary(at(14, 3, 20), 15, zone))
        assertEquals(at(14, 30), TranscriptionPlanner.nextBoundary(at(14, 15), 15, zone)) // genau auf der Grenze → nächste
        assertEquals(at(14, 10), TranscriptionPlanner.nextBoundary(at(14, 0, 1), 10, zone))
        assertEquals(at(15, 0), TranscriptionPlanner.nextBoundary(at(14, 31), 30, zone))
        assertEquals(at(15, 0), TranscriptionPlanner.nextBoundary(at(14, 59, 59), 60, zone))
        val midnight = LocalDateTime.of(2026, 11, 20, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(midnight, TranscriptionPlanner.nextBoundary(at(23, 50), 15, zone))
        assertTrue(TranscriptionPlanner.isFullHour(at(15, 0), zone))
        assertFalse(TranscriptionPlanner.isFullHour(at(15, 15), zone))
        // Sommerzeit-Umstellung (29.3.2026, 02:00 → 03:00): 01:50 + 10 min = 03:00 Sommerzeit
        val dstBefore = LocalDateTime.of(2026, 3, 29, 1, 50).atZone(zone).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 0).atZone(zone).toInstant().toEpochMilli(), TranscriptionPlanner.nextBoundary(dstBefore, 15, zone))
    }

    @Test
    fun statsKeepLast20() {
        val dir = java.nio.file.Files.createTempDirectory("stats").toFile()
        val f = File(dir, "stats/transcription.json")
        val st = TranscriptionStats(f)
        repeat(25) { i -> st.add(TranscriptionRunStat(i.toLong(), "f$i.ogg", 1000, 100, 0, 4, "service", "test")) }
        assertEquals(20, st.runs.value.size)
        assertEquals("f24.ogg", st.runs.value.first().audioFileName)
        assertEquals(0.1f, st.averageRealTimeFactor()!!, 1e-6f)
        assertEquals(20, TranscriptionStats(f).runs.value.size)
        dir.deleteRecursively()
    }

    @Test
    fun autoThreads() {
        assertEquals(1, AppSettings.autoThreads(1))
        assertEquals(2, AppSettings.autoThreads(4))
        assertEquals(3, AppSettings.autoThreads(6))
        assertEquals(4, AppSettings.autoThreads(8))
        assertEquals(4, AppSettings.autoThreads(10))
        assertEquals(6, AppSettings.autoThreads(12))
        assertEquals(4, AppSettings(transcriptionThreads = AppSettings.THREADS_AUTO).effectiveThreads(8))
        assertEquals(2, AppSettings(transcriptionThreads = 2).effectiveThreads(8))
    }
}
