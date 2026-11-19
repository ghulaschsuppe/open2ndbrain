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
    const val ID_STORAGE_FULL = 3

    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"

    private const val RC_OPEN = 10
    private const val RC_PAUSE = 11
    private const val RC_PAUSE_15 = 12
    private const val RC_RESUME = 13
    private const val RC_START = 15
    private const val RC_MEETING = 16
    private const val RC_LONG_MEETING = 17
    const val ID_LONG_MEETING = 4

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

        // Android zeigt höchstens 3 Aktionen: [Meeting] [Pause 15 min] [Pause | Fortsetzen].
        // „Stopp“ gibt es nur noch in der App – die unbefristete Pause deckt den Datenschutz ab.
        if (model.status != DisplayStatus.Off && model.status != DisplayStatus.Stopping) {
            if (model.meetingActive) {
                builder.addAction(0, context.getString(R.string.action_meeting_end), serviceIntent(context, RecorderService.ACTION_MEETING_END, RC_MEETING))
            } else {
                builder.addAction(0, context.getString(R.string.action_meeting_start), serviceIntent(context, RecorderService.ACTION_MEETING_START, RC_MEETING))
            }
            builder.addAction(0, context.getString(R.string.action_pause_15), serviceIntent(context, RecorderService.ACTION_PAUSE_15, RC_PAUSE_15))
            if (model.status == DisplayStatus.Paused) {
                builder.addAction(0, context.getString(R.string.action_resume), serviceIntent(context, RecorderService.ACTION_RESUME, RC_RESUME))
            } else {
                builder.addAction(0, context.getString(R.string.action_pause), serviceIntent(context, RecorderService.ACTION_PAUSE, RC_PAUSE))
            }
        }
        return builder.build()
    }

    /** Titel und Text, z. B. „Nimmt auf · 3 h 12 min · Pixel Buds“. */
    fun texts(context: Context, model: NotificationModel): Pair<String, String> {
        val base = baseTexts(context, model)
        if (!model.meetingActive) return base
        // Während eines Meetings steht das Meeting oben, der Aufnahmestatus darunter.
        val meeting = listOfNotNull(
            context.getString(R.string.meeting_running),
            Formatters.elapsedMinutes(model.meetingMinutes ?: 0),
            model.meetingTitle,
        ).joinToString(" · ")
        return meeting to base.first
    }

    private fun baseTexts(context: Context, model: NotificationModel): Pair<String, String> {
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
            DisplayStatus.Error -> if (isStorageFull(model.errorMessage)) {
                context.getString(R.string.status_storage_full) to context.getString(R.string.storage_full_hint)
            } else {
                context.getString(R.string.status_error) to (model.errorMessage ?: context.getString(R.string.notif_error_text))
            }
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

    /** „Speicher voll“: Antippen öffnet die App (Aufnahmen löschen / Aufbewahrung verkürzen). */
    @SuppressLint("MissingPermission") // durch canPost() geprüft
    fun showStorageFull(context: Context) {
        ensureChannels(context)
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_recorder)
            .setContentTitle(context.getString(R.string.status_storage_full))
            .setContentText(context.getString(R.string.storage_full_hint))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.storage_full_hint)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_STORAGE_FULL, notification)
    }

    fun cancelStorageFull(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STORAGE_FULL)

    /** „Meeting noch aktiv?“ nach 8 h (das Meeting wird nicht automatisch beendet). */
    @SuppressLint("MissingPermission") // durch canPost() geprüft
    fun showLongMeeting(context: Context, title: String?) {
        ensureChannels(context)
        if (!canPost(context)) return
        val text = context.getString(R.string.meeting_long_text, title ?: context.getString(R.string.meeting_untitled))
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_meeting)
            .setContentTitle(context.getString(R.string.meeting_long_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_meeting_end), serviceIntent(context, RecorderService.ACTION_MEETING_END, RC_LONG_MEETING))
            .build()
        NotificationManagerCompat.from(context).notify(ID_LONG_MEETING, notification)
    }

    fun cancelLongMeeting(context: Context) = NotificationManagerCompat.from(context).cancel(ID_LONG_MEETING)

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
