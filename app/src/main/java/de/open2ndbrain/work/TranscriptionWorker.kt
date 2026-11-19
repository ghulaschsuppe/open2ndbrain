package de.open2ndbrain.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R

/**
 * Rückfallebene, wenn der Aufnahmedienst nicht läuft: arbeitet die Warteschlange über den gemeinsamen
 * [TranscriptionRunner] ab (gleiche Logik wie im Dienst, nie zwei Läufe gleichzeitig). Läuft der Dienst-Runner,
 * übernimmt dieser – der Worker endet dann sofort.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as O2bApp).container

    override suspend fun doWork(): Result {
        val c = container
        val runner = c.transcriptionRunner
        if (runner.serviceActive) {
            runner.trigger()
            return Result.success()
        }
        val force = inputData.getBoolean(KEY_FORCE, false)
        val s = c.settings.current()
        val battery = c.transcriptionScheduler.battery.value
        val decision = TranscriptionPlanner.runDecision(
            s.transcriptionMode, modelsReady = true, charging = battery.charging, batteryPercent = battery.percent, force = force,
        )
        if (decision is RunDecision.Wait) {
            // Expedited-Aufträge haben keine Lade-/Akku-Bedingungen: dann mit Bedingungen neu einreihen.
            if (decision.reason == WaitingReason.WaitingForCharger || decision.reason == WaitingReason.BatteryLow) {
                c.transcriptionScheduler.schedule(constrained = true)
            }
            c.transcriptionScheduler.refreshStatus()
            return Result.success()
        }
        val result = runner.run(
            TranscriptionRunner.Origin.Worker,
            canContinue = { !isStopped && !runner.serviceActive },
            onProgress = { file, p, remaining -> updateNotification(file, p, remaining) },
        )
        if (result is TranscriptionRunner.Result.ModelMissing || result is TranscriptionRunner.Result.Done) {
            c.transcriptionScheduler.refreshStatus()
        }
        return Result.success()
    }

    /** Für Expedited-Aufträge unter Android 11 und älter nötig. */
    override suspend fun getForegroundInfo(): ForegroundInfo = WorkNotifications.transcriptionForegroundInfo(notification(null, 0f, 0))

    private fun notification(fileName: String?, progress: Float, remaining: Int) = WorkNotifications.progressNotification(
        applicationContext,
        WorkNotifications.CHANNEL_TRANSCRIPTION,
        applicationContext.getString(R.string.transcription_notif_title),
        if (fileName == null) "" else applicationContext.resources.getQuantityString(R.plurals.transcription_notif_text, remaining, fileName, remaining),
        if (fileName == null) null else progress,
        WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
    )

    private suspend fun updateNotification(fileName: String, progress: Float, remaining: Int) {
        try {
            setForeground(WorkNotifications.transcriptionForegroundInfo(notification(fileName, progress, remaining)))
        } catch (e: IllegalStateException) {
            // Vordergrundstart aus dem Hintergrund nicht erlaubt (Android 12+, ohne Akku-Ausnahme): normal weiter.
            Log.d(TAG, "setForeground nicht möglich: ${e.message}")
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val TAG = "TranscriptionWorker"
    }
}
