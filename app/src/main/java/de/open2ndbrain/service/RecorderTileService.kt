package de.open2ndbrain.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.ui.StartRecordingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Schnelleinstellungs-Kachel: Pause/Fortsetzen direkt, Start über die unsichtbare [StartRecordingActivity],
 * denn ein Mikrofon-Vordergrunddienst darf nur aus einer sichtbaren Activity gestartet werden.
 */
class RecorderTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var current: DisplayStatus = DisplayStatus.Off
    private var serviceRunning = false

    override fun onStartListening() {
        super.onStartListening()
        val container = (application as O2bApp).container
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                combine(
                    container.recordingEngine.state,
                    container.recorderStatus.status,
                    container.settings.settings,
                ) { e, st, settings ->
                    // Gespeicherte Nutzer-Pause ohne laufenden Dienst (z. B. nach Neustart): „Pausiert“ statt „Aus“.
                    val pausedBySettings = !st.running && settings.recordingDesired &&
                        settings.isPausedAt(System.currentTimeMillis())
                    displayStatus(e, st, pausedBySettings) to st.running
                }
                    .distinctUntilChanged()
                    .collect { (status, running) ->
                        current = status
                        serviceRunning = running
                        render(status)
                    }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (current) {
            // Pausieren geht bewusst auch auf dem Sperrbildschirm ohne Entsperren (Datenschutz: ein Tipp).
            DisplayStatus.Recording, DisplayStatus.MicBusy, DisplayStatus.Starting, DisplayStatus.Error ->
                RecorderService.send(this, RecorderService.ACTION_PAUSE)
            // Läuft der Dienst nicht (Pause nach Neustart), muss er aus einer sichtbaren Activity starten.
            DisplayStatus.Paused -> if (serviceRunning) {
                RecorderService.send(this, RecorderService.ACTION_RESUME)
            } else if (isLocked) {
                unlockAndRun { launchStart() }
            } else {
                launchStart()
            }
            DisplayStatus.Off -> if (isLocked) unlockAndRun { launchStart() } else launchStart()
            DisplayStatus.Stopping -> Unit
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchStart() {
        val intent = StartRecordingActivity.intent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Start aus der Kachel fehlgeschlagen", e)
        }
    }

    private fun render(status: DisplayStatus) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.state = when (status) {
            DisplayStatus.Recording, DisplayStatus.MicBusy, DisplayStatus.Starting, DisplayStatus.Error -> Tile.STATE_ACTIVE
            DisplayStatus.Paused, DisplayStatus.Off -> Tile.STATE_INACTIVE
            DisplayStatus.Stopping -> Tile.STATE_UNAVAILABLE
        }
        tile.subtitle = getString(
            when (status) {
                DisplayStatus.Recording -> R.string.status_recording
                DisplayStatus.MicBusy -> R.string.tile_mic_busy
                DisplayStatus.Starting -> R.string.status_starting
                DisplayStatus.Error -> R.string.status_error
                DisplayStatus.Paused -> R.string.status_paused
                DisplayStatus.Off -> R.string.status_off
                DisplayStatus.Stopping -> R.string.status_stopping
            },
        )
        tile.updateTile()
    }

    companion object {
        private const val TAG = "RecorderTile"

        /** Bittet das System, die Kachel neu zu zeichnen (z. B. nach Statuswechsel im Dienst). */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, RecorderTileService::class.java))
            } catch (e: Exception) {
                // Kachel nicht hinzugefügt oder vom System abgelehnt – unkritisch.
                Log.d(TAG, "requestListeningState: ${e.message}")
            }
        }
    }
}
