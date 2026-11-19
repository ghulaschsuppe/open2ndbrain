package de.open2ndbrain.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import de.open2ndbrain.O2bApp
import de.open2ndbrain.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Nach Geräte-Neustart oder App-Update: Aufnahme wiederaufnehmen, falls der Nutzer sie eingeschaltet hatte.
 *
 * - Android 10: Direktstart erlaubt (keine „while-in-use“-Beschränkung für das Mikrofon).
 * - Android 11–13: Ein aus BOOT_COMPLETED gestarteter Dienst liefe zwar, bekäme aber kein Mikrofon (stumm).
 * - Android 14+: Mikrofon-Vordergrunddienste aus BOOT_COMPLETED sind verboten.
 * Ab Android 11 zeigen wir daher „Aufnahme fortsetzen – antippen“; das Antippen startet aus dem Vordergrund.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val app = context.applicationContext as O2bApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = app.container.settings.current()
                val decision = decide(
                    sdk = Build.VERSION.SDK_INT,
                    settings = settings,
                    micGranted = RecorderService.hasMicPermission(app),
                )
                when (decision) {
                    BootDecision.Nothing -> Unit
                    BootDecision.DirectStart -> if (!RecorderService.restoreFromBoot(app)) {
                        RecorderNotifications.showResumePrompt(app)
                    }
                    BootDecision.Prompt -> RecorderNotifications.showResumePrompt(app)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Wiederaufnahme nach Neustart fehlgeschlagen", e)
            } finally {
                pending.finish()
            }
        }
    }

    enum class BootDecision { Nothing, DirectStart, Prompt }

    companion object {
        private val HANDLED_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)

        /** Reine Entscheidungslogik (unit-getestet). */
        fun decide(sdk: Int, settings: AppSettings, micGranted: Boolean): BootDecision = when {
            !settings.recordingDesired || !settings.onboardingDone -> BootDecision.Nothing
            // Unbegrenzt pausiert: nicht still wieder starten, aber an die laufende Aufnahme erinnern.
            settings.pausedUntilEpochMs == AppSettings.PAUSED_INDEFINITELY -> BootDecision.Prompt
            sdk <= Build.VERSION_CODES.Q && micGranted -> BootDecision.DirectStart
            else -> BootDecision.Prompt
        }
    }
}
