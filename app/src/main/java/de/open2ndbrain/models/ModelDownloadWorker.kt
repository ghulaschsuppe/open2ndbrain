package de.open2ndbrain.models

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.work.WorkNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Lädt alle Dateien eines Modells (Fortsetzung per HTTP-Range), prüft jede per SHA-256 und installiert das Modell
 * mit der Markerdatei. Läuft als Vordergrund-Worker (Typ `dataSync`) mit Fortschrittsbenachrichtigung.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // usableSpace genügt: Modelle liegen im internen Speicher, ein grober Platz-Check vor dem Download reicht.
    @SuppressLint("UsableSpace")
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val spec = ModelCatalog.byId(id) ?: return Result.failure()
        val manager = (applicationContext as O2bApp).container.modelManager
        val store = manager.store
        if (store.isInstalled(spec)) {
            manager.reportInstalled(id)
            return Result.success()
        }
        val notifId = WorkNotifications.downloadId(ModelCatalog.all.indexOf(spec))
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(this.id)
        val latest = AtomicLong(store.bytesPresent(spec))

        suspend fun showProgress(done: Long) {
            val n = WorkNotifications.progressNotification(
                applicationContext,
                WorkNotifications.CHANNEL_DOWNLOADS,
                applicationContext.getString(R.string.download_notif_title, spec.displayName),
                applicationContext.getString(R.string.download_progress, Formatters.bytes(done), Formatters.bytes(spec.sizeBytes)),
                done.toFloat() / spec.sizeBytes.coerceAtLeast(1),
                cancel,
            )
            try {
                setForeground(WorkNotifications.downloadForegroundInfo(notifId, n))
            } catch (e: IllegalStateException) {
                // Vordergrundstart aus dem Hintergrund nicht erlaubt (Android 12+): als normaler Worker weiter.
                Log.d(TAG, "setForeground nicht möglich: ${e.message}")
            }
        }

        return try {
            withContext(Dispatchers.IO) {
                // Benachrichtigung höchstens 1×/s aktualisieren (der Download meldet häufiger).
                val ticker = launch {
                    while (true) {
                        showProgress(latest.get())
                        delay(NOTIFY_INTERVAL_MS)
                    }
                }
                val missing = spec.files.filterNot { store.isVerified(spec, it) }
                val needed = missing.sumOf { it.sizeBytes - store.partFile(spec, it).let { p -> if (p.isFile) p.length() else 0L } }
                if (store.root.usableSpace < needed + SPACE_RESERVE_BYTES) {
                    throw IOException(applicationContext.getString(R.string.download_no_space, Formatters.bytes(needed)))
                }
                for (file in missing) {
                    val part = store.partFile(spec, file)
                    downloader.download(file.url, part, file.sizeBytes) { bytes ->
                        val done = DownloadLogic.overallProgress(spec, store.verifiedNames(spec), file.name, bytes)
                        latest.set(done)
                        manager.reportProgress(id, done, spec.sizeBytes)
                    }
                    manager.reportVerifying(id)
                    val sha = DownloadLogic.sha256Hex(part)
                    if (!sha.equals(file.sha256, ignoreCase = true)) {
                        part.delete()
                        throw ChecksumException(applicationContext.getString(R.string.download_bad_checksum, file.name))
                    }
                    store.install(spec, file, part)
                }
                store.writeMarker(spec)
                ticker.cancel()
            }
            manager.reportInstalled(id)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChecksumException) {
            Log.w(TAG, "Prüfsumme falsch", e)
            manager.reportFailed(id, e.message ?: "")
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: IOException) {
            Log.w(TAG, "Download von $id fehlgeschlagen (Versuch $runAttemptCount)", e)
            val permanent = e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429
            if (!permanent && runAttemptCount < MAX_ATTEMPTS) {
                manager.reportProgress(id, store.bytesPresent(spec), spec.sizeBytes)
                Result.retry()
            } else {
                val msg = e.message ?: applicationContext.getString(R.string.download_failed)
                manager.reportFailed(id, msg)
                Result.failure(workDataOf(KEY_ERROR to msg))
            }
        }
    }

    private class ChecksumException(message: String) : IOException(message)

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ERROR = "error"
        private const val TAG = "ModelDownloadWorker"
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val MAX_ATTEMPTS = 8
        private const val SPACE_RESERVE_BYTES = 200L * 1024 * 1024
        private val downloader = HttpDownloader()
    }
}
