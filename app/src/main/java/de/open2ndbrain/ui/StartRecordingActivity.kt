package de.open2ndbrain.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.open2ndbrain.O2bApp
import de.open2ndbrain.service.RecorderService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unsichtbares Trampolin (transparentes Theme): startet den Mikrofon-Vordergrunddienst, während die App
 * sichtbar ist – der einzige erlaubte Weg aus Kachel und „Aufnahme fortsetzen“-Benachrichtigung unter Android 14+.
 * Fehlt die Einrichtung oder die Mikrofon-Berechtigung, öffnet es stattdessen die App.
 */
class StartRecordingActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Erst im Zustand RESUMED gilt die App sicher als „im Vordergrund“.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (handled) return@repeatOnLifecycle
                handled = true
                val container = (application as O2bApp).container
                val settings = container.settings.current()
                val started = settings.onboardingDone && RecorderService.start(this@StartRecordingActivity)
                if (started) {
                    // Kurz warten, bis der Dienst im Vordergrund ist, damit der Start sicher noch als sichtbar gilt.
                    withTimeoutOrNull(WAIT_MS) { container.recorderStatus.status.first { it.running } }
                } else {
                    startActivity(
                        Intent(this@StartRecordingActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                }
                finish()
            }
        }
    }

    companion object {
        private const val WAIT_MS = 2_000L

        fun intent(context: Context): Intent = Intent(context, StartRecordingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
