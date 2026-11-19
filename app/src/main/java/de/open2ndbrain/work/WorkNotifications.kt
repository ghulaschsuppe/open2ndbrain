package de.open2ndbrain.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import de.open2ndbrain.R
import de.open2ndbrain.ui.MainActivity

/** Benachrichtigungen für Modell-Downloads und Transkription (beide als Vordergrund-Worker). */
object WorkNotifications {
    const val CHANNEL_DOWNLOADS = "downloads"
    const val CHANNEL_TRANSCRIPTION = "transcription"
    const val ID_TRANSCRIPTION = 200
    private const val ID_DOWNLOAD_BASE = 100

    fun downloadId(index: Int) = ID_DOWNLOAD_BASE + index

    fun ensureChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_DOWNLOADS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_downloads_name))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_TRANSCRIPTION, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_transcription_name))
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    fun progressNotification(
        context: Context,
        channel: String,
        title: String,
        text: String,
        progress: Float?,
        cancelIntent: PendingIntent?,
    ): Notification {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    30,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (progress == null) b.setProgress(0, 0, true) else b.setProgress(1000, (progress.coerceIn(0f, 1f) * 1000).toInt(), false)
        if (cancelIntent != null) b.addAction(0, context.getString(R.string.action_cancel_work), cancelIntent)
        return b.build()
    }

    /** Typ `dataSync` (Download). */
    fun downloadForegroundInfo(id: Int, notification: Notification): ForegroundInfo =
        ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Ab Android 15 `mediaProcessing`, darunter `dataSync`. */
    fun transcriptionForegroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ForegroundInfo(ID_TRANSCRIPTION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else {
            ForegroundInfo(ID_TRANSCRIPTION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
}
