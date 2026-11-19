package de.open2ndbrain.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.EngineStatus
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
        // Das unsichtbare Fenster darf keine Berührungen abfangen, solange es auf den Dienst wartet.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        lifecycleScope.launch {
            // Erst im Zustand RESUMED gilt die App sicher als „im Vordergrund“.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (handled) return@repeatOnLifecycle
                handled = true
                val container = (application as O2bApp).container
                val settings = container.settings.current()
                val started = settings.onboardingDone && RecorderService.start(this@StartRecordingActivity)
                if (started) {
                    // Sichtbar bleiben, bis der Dienst (wieder) im Vordergrund ist. Läuft gerade noch ein STOP
                    // (bis zu 5 s Dateiabschluss), betritt der Dienst den Vordergrund erst danach neu.
                    withTimeoutOrNull(WAIT_MS) { container.recorderStatus.status.first { it.running && !it.stopping } }
                    // Bei Engine-Fehler (z. B. Mikrofon im Hintergrund gesperrt) kurz warten, bis die Engine
                    // aus dem sichtbaren Zustand neu geöffnet hat.
                    if (container.recordingEngine.state.value.status == EngineStatus.Error) {
                        withTimeoutOrNull(ERROR_WAIT_MS) {
                            container.recordingEngine.state.first { it.status != EngineStatus.Error }
                        }
                    }
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
        private const val WAIT_MS = 8_000L
        private const val ERROR_WAIT_MS = 1_500L

        fun intent(context: Context): Intent = Intent(context, StartRecordingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
