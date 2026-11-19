package de.open2ndbrain.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.open2ndbrain.R
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.MainActivity
import de.open2ndbrain.ui.StartRecordingActivity

/** Benachrichtigungskanäle und -inhalte für Dienst, Neustart und Kachel. */
object RecorderNotifications {
    const val ID_RECORDING = 1
    const val ID_RESUME_PROMPT = 2

    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"

    private const val RC_OPEN = 10
    private const val RC_PAUSE = 11
    private const val RC_PAUSE_15 = 12
    private const val RC_RESUME = 13
    private const val RC_STOP = 14
    private const val RC_START = 15

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_RECORDING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_recording_name))
                    .setDescription(context.getString(R.string.channel_recording_desc))
                    .setShowBadge(false)
                    .setVibrationEnabled(false)
                    .setSound(null, null)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ALERTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_alerts_name))
                    .setDescription(context.getString(R.string.channel_alerts_desc))
                    .build(),
            ),
        )
    }

    /** Dauerhafte Benachrichtigung des Vordergrunddienstes. */
    fun buildOngoing(context: Context, model: NotificationModel): Notification {
        ensureChannels(context)
        val (title, text) = texts(context, model)
        val builder = NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Auf dem Sperrbildschirm sichtbar, damit „Pause“ immer mit einem Tipp erreichbar ist.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))

        when (model.status) {
            DisplayStatus.Recording, DisplayStatus.MicBusy, DisplayStatus.Starting, DisplayStatus.Error -> {
                builder.addAction(0, context.getString(R.string.action_pause_15), serviceIntent(context, RecorderService.ACTION_PAUSE_15, RC_PAUSE_15))
                builder.addAction(0, context.getString(R.string.action_pause), serviceIntent(context, RecorderService.ACTION_PAUSE, RC_PAUSE))
                builder.addAction(0, context.getString(R.string.action_stop), serviceIntent(context, RecorderService.ACTION_STOP, RC_STOP))
            }
            DisplayStatus.Paused -> {
                builder.addAction(0, context.getString(R.string.action_resume), serviceIntent(context, RecorderService.ACTION_RESUME, RC_RESUME))
                builder.addAction(0, context.getString(R.string.action_stop), serviceIntent(context, RecorderService.ACTION_STOP, RC_STOP))
            }
            DisplayStatus.Off, DisplayStatus.Stopping -> Unit
        }
        return builder.build()
    }

    /** Titel und Text, z. B. „Nimmt auf · 3 h 12 min · Pixel Buds“. */
    fun texts(context: Context, model: NotificationModel): Pair<String, String> {
        val today = context.getString(R.string.notif_stored_today, Formatters.elapsedMinutes(model.storedMinutesToday))
        return when (model.status) {
            DisplayStatus.Recording -> {
                val parts = listOfNotNull(
                    context.getString(R.string.status_recording),
                    model.elapsedMinutes?.let(Formatters::elapsedMinutes),
                    model.inputName,
                )
                parts.joinToString(" · ") to today
            }
            DisplayStatus.MicBusy -> context.getString(R.string.status_mic_busy) to context.getString(R.string.notif_mic_busy_text)
            DisplayStatus.Paused -> pausedTitle(context, model.pausedUntil) to context.getString(R.string.notif_paused_text)
            DisplayStatus.Error -> context.getString(R.string.status_error) to
                (model.errorMessage ?: context.getString(R.string.notif_error_text))
            DisplayStatus.Starting -> context.getString(R.string.status_starting) to today
            DisplayStatus.Stopping -> context.getString(R.string.status_stopping) to ""
            DisplayStatus.Off -> context.getString(R.string.status_off) to ""
        }
    }

    fun pausedTitle(context: Context, pausedUntil: Long?): String =
        if (pausedUntil == null || pausedUntil == AppSettings.PAUSED_INDEFINITELY) {
            context.getString(R.string.status_paused)
        } else {
            context.getString(R.string.status_paused_until, Formatters.time(pausedUntil))
        }

    /** Aktualisiert die laufende Dienst-Benachrichtigung (nur wenn Benachrichtigungen erlaubt sind). */
    @SuppressLint("MissingPermission") // durch canPost() geprüft
    fun updateOngoing(context: Context, notification: Notification) {
        if (canPost(context)) {
            NotificationManagerCompat.from(context).notify(ID_RECORDING, notification)
        }
    }

    /**
     * „Aufnahme fortsetzen – antippen“: nach Neustart oder wenn Android den Start aus dem Hintergrund verbietet.
     * Das Antippen öffnet eine (unsichtbare) Activity, die den Dienst aus dem Vordergrund startet.
     */
    @SuppressLint("MissingPermission") // durch canPost() geprüft
    fun showResumePrompt(context: Context) {
        ensureChannels(context)
        if (!canPost(context)) return
        val pi = PendingIntent.getActivity(
            context,
            RC_START,
            StartRecordingActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(context.getString(R.string.notif_resume_title))
            .setContentText(context.getString(R.string.notif_resume_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, context.getString(R.string.action_resume), pi)
            .build()
        NotificationManagerCompat.from(context).notify(ID_RESUME_PROMPT, notification)
    }

    fun cancelResumePrompt(context: Context) = NotificationManagerCompat.from(context).cancel(ID_RESUME_PROMPT)

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        RC_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Aktionen an den bereits laufenden Dienst (normaler startService, kein neuer Vordergrundstart). */
    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        RecorderService.intent(context, action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
