package de.open2ndbrain.work

import android.content.Context
import android.os.Process
import android.util.Log
import de.open2ndbrain.R
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelLoadException
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.core.Transcript
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.transcription.SherpaTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Gemeinsame Abarbeitung der Transkriptions-Warteschlange für den Aufnahmedienst und den WorkManager-Worker.
 * Prozessweit gibt es genau einen Runner; der [Mutex] stellt sicher, dass nie zwei Läufe gleichzeitig rechnen
 * (~1 GB RAM je Transcriber). Vorrang hat der Dienst: Er wartet auf einen laufenden Worker, der Worker
 * überspringt seinen Lauf, wenn der Dienst schon rechnet (oder die Warteschlange übernimmt).
 */
class TranscriptionRunner(
    private val deps: Deps,
    /** Eigener Thread mit Hintergrundpriorität; [runInterruptible] unterbricht ihn bei Abbruch. */
    private val compute: CoroutineDispatcher = backgroundDispatcher(),
) {
    enum class Origin { Service, Worker }

    sealed interface Result {
        data class Done(val processed: Int) : Result
        /** Ein anderer Lauf ist aktiv (nur Worker). */
        data object Skipped : Result
        data object ModelMissing : Result
        data object ModelBroken : Result
        data object OutOfMemory : Result
    }

    /** Abhängigkeiten (für Tests austauschbar). */
    interface Deps {
        /**
         * Konfiguration für diese Datei (gewähltes bzw. für „Mit diesem Modell neu transkribieren“ vorgemerktes
         * Modell) oder null, wenn Pflichtmodelle fehlen.
         */
        suspend fun configFor(rec: RecordingFile): TranscriberConfig?
        fun newTranscriber(config: TranscriberConfig): Transcriber
        /** Offene Dateien in Verarbeitungsreihenfolge (Meetings zuerst, dauerhaft fehlerhafte ausgefiltert). */
        suspend fun pending(): List<RecordingFile>
        fun updateStatus(transform: (TranscriptionStatus) -> TranscriptionStatus)
        fun onTranscribed(rec: RecordingFile, transcript: Transcript, loadMs: Long, threads: Int, origin: Origin)
        fun onFailed(rec: RecordingFile, error: Exception)
        fun text(kind: Text): String
    }

    enum class Text { ModelBroken, OutOfMemory }

    private val mutex = Mutex()
    private val holderRef = AtomicReference<Origin?>(null)
    /** Wer gerade rechnet (null = niemand). */
    val holder: Origin? get() = holderRef.get()

    /** Der Dienst-Runner ist aktiv (dann übernimmt er die Warteschlange, WorkManager ist nur Rückfallebene). */
    @Volatile var serviceActive: Boolean = false

    private val forceOnce = AtomicBoolean(false)
    /** Anstöße an den Dienst (neue Datei, „Jetzt transkribieren“, Bedingungen geändert). */
    val triggers = Channel<Unit>(Channel.CONFLATED)

    fun trigger() {
        triggers.trySend(Unit)
    }

    /** „Jetzt transkribieren“: ignoriert „Aus“/„Nur beim Laden“ einmalig, bis die Warteschlange leer ist. */
    fun requestForce() {
        forceOnce.set(true)
        trigger()
    }

    val forceRequested: Boolean get() = forceOnce.get()

    /**
     * Arbeitet die Warteschlange ab, solange [canContinue] true liefert. Abbruch der Coroutine unterbricht den
     * Rechen-Thread (kein halbes Transkript). [onProgress]: Dateiname, Fortschritt 0..1, offene Dateien.
     */
    suspend fun run(
        origin: Origin,
        canContinue: suspend () -> Boolean = { true },
        onProgress: suspend (String, Float, Int) -> Unit = { _, _, _ -> },
    ): Result {
        when (origin) {
            Origin.Worker -> if (!mutex.tryLock()) return Result.Skipped
            Origin.Service -> mutex.lock()
        }
        holderRef.set(origin)
        var transcriber: Transcriber? = null
        var currentConfig: TranscriberConfig? = null
        var processed = 0
        try {
            var first = true
            while (canContinue()) {
                val pending = deps.pending()
                val rec = pending.firstOrNull() ?: break
                val remaining = pending.size
                val config = deps.configFor(rec) ?: return Result.ModelMissing
                // Anderes Modell (Einstellung geändert oder Vergleich mit anderem Modell): alten Transcriber schließen.
                if (transcriber != null && config != currentConfig) {
                    val old = transcriber
                    transcriber = null
                    runCatching { withContext(compute) { old?.close() } }
                    first = true
                }
                currentConfig = config
                deps.updateStatus { it.copy(running = true, currentFile = rec.audio.name, progress = 0f, pendingCount = remaining, waitingReason = null) }
                onProgress(rec.audio.name, 0f, remaining)
                val t = transcriber ?: deps.newTranscriber(config).also { transcriber = it }
                try {
                    val started = System.currentTimeMillis()
                    val transcript = transcribeWithProgress(t, rec, remaining, onProgress)
                    val wall = System.currentTimeMillis() - started
                    val loadMs = if (first) (wall - transcript.processingMs).coerceAtLeast(0) else 0L
                    first = false
                    processed++
                    deps.onTranscribed(rec, transcript, loadMs, config.numThreads, origin)
                    deps.updateStatus {
                        it.copy(
                            lastRealTimeFactor = TranscriptionPlanner.realTimeFactor(transcript.processingMs, transcript.audioDurationMs)
                                ?: it.lastRealTimeFactor,
                            lastError = null,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw CancellationException("unterbrochen")
                } catch (e: ModelLoadException) {
                    Log.e(TAG, "Modell nicht ladbar", e)
                    deps.updateStatus { it.copy(lastError = deps.text(Text.ModelBroken)) }
                    return Result.ModelBroken
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Zu wenig Arbeitsspeicher", e)
                    deps.updateStatus { it.copy(lastError = deps.text(Text.OutOfMemory)) }
                    return Result.OutOfMemory
                } catch (e: Exception) {
                    // IOException, IllegalStateException u. a.: Fehler dieser Datei, nicht des Modells.
                    Log.w(TAG, "Transkription von ${rec.audio.name} fehlgeschlagen", e)
                    deps.onFailed(rec, e)
                    deps.updateStatus { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
                }
            }
            // Warteschlange leer (oder Bedingungen weg): einmaliges „Jetzt“ ist erledigt, wenn nichts mehr offen ist.
            if (deps.pending().isEmpty()) forceOnce.set(false)
            return Result.Done(processed)
        } finally {
            transcriber?.let { t -> runCatching { withContext(compute) { t.close() } } }
            deps.updateStatus { it.copy(running = false, currentFile = null, progress = 0f) }
            holderRef.set(null)
            mutex.unlock()
        }
    }

    private suspend fun transcribeWithProgress(
        t: Transcriber,
        rec: RecordingFile,
        remaining: Int,
        onProgress: suspend (String, Float, Int) -> Unit,
    ): Transcript = coroutineScope {
        val progress = AtomicReference(0f)
        val reporter = launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                val p = progress.get()
                deps.updateStatus { it.copy(progress = p) }
                onProgress(rec.audio.name, p, remaining)
            }
        }
        try {
            runInterruptible(compute) { t.transcribe(rec) { p -> progress.set(p) } }
        } finally {
            reporter.cancel()
        }
    }

    companion object {
        private const val TAG = "TranscriptionRunner"
        private const val PROGRESS_INTERVAL_MS = 1_000L

        /** Ein Thread mit `THREAD_PRIORITY_BACKGROUND` – die Aufnahme behält Vorrang. */
        fun backgroundDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "O2B-Transcribe")
        }.asCoroutineDispatcher()
    }
}

/** Produktions-Abhängigkeiten des Runners aus dem [AppContainer]. */
class ContainerRunnerDeps(context: Context, private val c: AppContainer) : TranscriptionRunner.Deps {
    private val appContext = context.applicationContext

    override suspend fun configFor(rec: RecordingFile): TranscriberConfig? {
        val s = c.settings.current()
        val installed = AsrSelection.installedIds(c.modelManager.states.value)
        // Vorgemerktes Vergleichsmodell, sonst das gewählte (bzw. installierte Ersatz-) Modell
        val asrId = c.transcriptionOverrides.modelFor(rec.audio)?.takeIf { it in installed }
            ?: AsrSelection.effective(s.asrModelId, installed)
        val spec = ModelCatalog.byId(asrId) ?: return null
        val asrDir = c.modelManager.installedDir(asrId) ?: return null
        val vad = c.modelManager.installedFile(ModelCatalog.SILERO_VAD) ?: return null
        val speaker = if (s.speakerRecognition) c.modelManager.installedFile(ModelCatalog.WESPEAKER_RESNET34) else null
        withContext(Dispatchers.IO) { c.transcriptionFailures.pruneMissing() }
        val threads = s.effectiveThreads()
        return TranscriberConfig(asrDir, vad, speaker, threads, asrModel = spec)
    }

    override fun newTranscriber(config: TranscriberConfig): Transcriber = SherpaTranscriber(appContext, config, c.speakers)

    override suspend fun pending(): List<RecordingFile> {
        val pending = withContext(Dispatchers.IO) { c.transcripts.pending() }
            .filterNot { c.transcriptionFailures.isBlocked(it.audio) }
        val ordered = MarkerLogic.prioritize(pending, c.markers.current(), System.currentTimeMillis())
        // Vom Nutzer angestoßene Vergleiche („Mit diesem Modell neu transkribieren“) zuerst
        val (overridden, rest) = ordered.partition { c.transcriptionOverrides.modelFor(it.audio) != null }
        return overridden + rest
    }

    override fun updateStatus(transform: (TranscriptionStatus) -> TranscriptionStatus) = c.transcriptionStatus.update(transform)

    override fun onTranscribed(rec: RecordingFile, transcript: Transcript, loadMs: Long, threads: Int, origin: TranscriptionRunner.Origin) {
        c.transcriptionFailures.clear(rec.audio)
        c.transcriptionOverrides.clear(rec.audio)
        val stat = TranscriptionRunStat(
            atEpochMs = System.currentTimeMillis(),
            audioFileName = transcript.audioFileName,
            audioMs = transcript.audioDurationMs,
            processingMs = transcript.processingMs,
            loadMs = loadMs,
            threads = threads,
            origin = origin.name.lowercase(),
            device = TranscriptionStats.deviceDescription(),
            asrModelId = transcript.asrModelId,
        )
        c.transcriptionStats.add(stat)
        Log.i(
            "TranscriptionRunner",
            "${stat.audioFileName}: RTF ${stat.realTimeFactor}, Audio ${stat.audioMs} ms, Rechenzeit ${stat.processingMs} ms, " +
                "Laden ${stat.loadMs} ms, Threads $threads, Modell ${stat.asrModelId}, ${stat.origin}, ${stat.device}",
        )
    }

    override fun onFailed(rec: RecordingFile, error: Exception) {
        // Fehlversuch zählen – außer die Datei wurde inzwischen gelöscht.
        if (rec.audio.exists()) c.transcriptionFailures.increment(rec.audio) else c.transcriptionFailures.clear(rec.audio)
    }

    override fun text(kind: TranscriptionRunner.Text): String = appContext.getString(
        when (kind) {
            TranscriptionRunner.Text.ModelBroken -> R.string.transcription_model_broken
            TranscriptionRunner.Text.OutOfMemory -> R.string.transcription_oom
        },
    )
}
