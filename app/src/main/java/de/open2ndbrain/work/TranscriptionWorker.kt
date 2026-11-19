package de.open2ndbrain.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.transcription.SherpaTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Arbeitet die Warteschlange `transcripts.pending()` nacheinander ab (Meeting-Dateien zuerst). Die Modelle werden
 * nur geladen, wenn es etwas zu tun gibt, und am Ende immer freigegeben. Abbruch (Bedingungen weg, Nutzer, Timeout)
 * unterbricht den Rechen-Thread; dann wird kein halbes Transkript geschrieben.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as O2bApp).container

    override suspend fun doWork(): Result {
        val c = container
        val status = c.transcriptionStatus
        val settings = c.settings.current()
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (settings.transcriptionMode == TranscriptionMode.Off && !force) return Result.success()

        val asrDir = c.modelManager.installedDir(ModelCatalog.PARAKEET_V3.id)
        val vadFile = c.modelManager.installedFile(ModelCatalog.SILERO_VAD)
        if (asrDir == null || vadFile == null) {
            c.transcriptionScheduler.refreshStatus()
            return Result.success()
        }
        val speakerFile = if (settings.speakerRecognition) c.modelManager.installedFile(ModelCatalog.WESPEAKER_RESNET34) else null
        val config = TranscriberConfig(asrDir, vadFile, speakerFile, settings.transcriptionThreads)

        var transcriber: Transcriber? = null
        try {
            while (!isStopped) {
                val next = nextPending() ?: break
                val (rec, remaining) = next
                status.update { it.copy(running = true, currentFile = rec.audio.name, progress = 0f, pendingCount = remaining, waitingReason = null) }
                updateNotification(rec.audio.name, 0f, remaining)
                val t = transcriber ?: SherpaTranscriber(applicationContext, config, c.speakers).also { transcriber = it }
                try {
                    val transcript = transcribeWithProgress(t, rec, remaining)
                    c.transcriptionFailures.clear(rec.audio)
                    status.update {
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
                } catch (e: IllegalStateException) {
                    // Modell nicht ladbar → ganzen Lauf beenden, Datei nicht als fehlerhaft zählen.
                    Log.e(TAG, "Modell nicht ladbar", e)
                    status.update { it.copy(lastError = applicationContext.getString(R.string.transcription_model_broken)) }
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "Transkription von ${rec.audio.name} fehlgeschlagen", e)
                    c.transcriptionFailures.increment(rec.audio)
                    status.update { it.copy(lastError = e.message) }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "Zu wenig Arbeitsspeicher", e)
                    status.update { it.copy(lastError = applicationContext.getString(R.string.transcription_oom)) }
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Transkription von ${rec.audio.name} fehlgeschlagen", e)
                    c.transcriptionFailures.increment(rec.audio)
                    status.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
                }
            }
        } finally {
            transcriber?.let { t -> runCatching { withContext(Dispatchers.Default) { t.close() } } }
            status.update { it.copy(running = false, currentFile = null, progress = 0f) }
            c.transcriptionScheduler.refreshStatus()
        }
        return Result.success()
    }

    /** Nächste Datei (Meetings zuerst, dauerhaft fehlerhafte übersprungen) und Anzahl insgesamt offener Dateien. */
    private suspend fun nextPending(): Pair<RecordingFile, Int>? {
        val c = container
        val pending = withContext(Dispatchers.IO) { c.transcripts.pending() }
            .filterNot { c.transcriptionFailures.isBlocked(it.audio) }
        val ordered = MarkerLogic.prioritize(pending, c.markers.current(), System.currentTimeMillis())
        return ordered.firstOrNull()?.let { it to ordered.size }
    }

    private suspend fun transcribeWithProgress(t: Transcriber, rec: RecordingFile, remaining: Int) = coroutineScope {
        val progress = AtomicReference(0f)
        val reporter = launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                val p = progress.get()
                container.transcriptionStatus.update { it.copy(progress = p) }
                updateNotification(rec.audio.name, p, remaining)
            }
        }
        try {
            runInterruptible(Dispatchers.Default) { t.transcribe(rec) { p -> progress.set(p) } }
        } finally {
            reporter.cancel()
        }
    }

    private suspend fun updateNotification(fileName: String, progress: Float, remaining: Int) {
        val n = WorkNotifications.progressNotification(
            applicationContext,
            WorkNotifications.CHANNEL_TRANSCRIPTION,
            applicationContext.getString(R.string.transcription_notif_title),
            applicationContext.resources.getQuantityString(R.plurals.transcription_notif_text, remaining, fileName, remaining),
            progress,
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        try {
            setForeground(WorkNotifications.transcriptionForegroundInfo(n))
        } catch (e: IllegalStateException) {
            // Vordergrundstart aus dem Hintergrund nicht erlaubt (Android 12+, ohne Akku-Ausnahme): normal weiter.
            Log.d(TAG, "setForeground nicht möglich: ${e.message}")
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val TAG = "TranscriptionWorker"
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
