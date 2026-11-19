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
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.StartRecordingActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Kachel „Meeting“: Start/Ende. Läuft beim Start keine Aufnahme (oder ist sie pausiert), wird sie über die
 * unsichtbare [StartRecordingActivity] (fort)gesetzt – nur so darf der Mikrofon-Dienst unter Android 14+ starten.
 */
class MeetingTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var meetingActive = false
    private var recordingActive = false

    override fun onStartListening() {
        super.onStartListening()
        val c = (application as O2bApp).container
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch {
                combine(c.markers.activeMeeting, c.recorderStatus.status) { m, st -> m to (st.running && st.pausedUntil == null && !st.stopping) }
                    .distinctUntilChanged()
                    .collect { (meeting, recording) ->
                        meetingActive = meeting != null
                        recordingActive = recording
                        val tile = qsTile ?: return@collect
                        tile.label = getString(R.string.meeting_tile_label)
                        tile.state = if (meeting != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                        tile.subtitle = if (meeting != null) {
                            getString(R.string.meeting_since, Formatters.time(meeting.startEpochMs))
                        } else {
                            getString(R.string.meeting_tile_off)
                        }
                        tile.updateTile()
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
        val appScope = (application as O2bApp).container.appScope
        if (meetingActive) {
            appScope.launch { MeetingController.end(this@MeetingTileService) }
            return
        }
        // Aktivität synchron im Klick starten (danach ist der Start aus der Kachel ggf. nicht mehr erlaubt).
        if (!recordingActive) {
            if (isLocked) unlockAndRun { launchStart() } else launchStart()
        }
        appScope.launch { MeetingController.start(this@MeetingTileService) }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchStart() {
        val intent = StartRecordingActivity.intent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Start aus der Kachel fehlgeschlagen", e)
        }
    }

    companion object {
        private const val TAG = "MeetingTile"

        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, MeetingTileService::class.java))
            } catch (e: Exception) {
                Log.d(TAG, "requestListeningState: ${e.message}")
            }
        }
    }
}
