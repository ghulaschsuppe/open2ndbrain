package de.open2ndbrain.service

import android.content.Context
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.Marker

/**
 * Meeting starten/beenden – gemeinsam für App, Kachel und Benachrichtigung (über den Dienst).
 * Die Aufnahme läuft dabei ungestört weiter; das Meeting ist nur eine Markierung auf der Zeitachse.
 */
object MeetingController {

    suspend fun start(context: Context, title: String? = null): Marker {
        val c = (context.applicationContext as O2bApp).container
        val m = c.markers.startMeeting(title)
        MeetingTileService.requestUpdate(context.applicationContext)
        return m
    }

    /**
     * Beendet das Meeting, schließt die laufende Aufnahmedatei sofort ab (`splitNow`), damit sie gleich in der
     * Transkriptions-Warteschlange steht, und stößt die Transkription an (Meeting-Dateien werden zuerst verarbeitet).
     */
    suspend fun end(context: Context): Marker? {
        val c = (context.applicationContext as O2bApp).container
        val m = c.markers.endMeeting() ?: return null
        c.recordingEngine.splitNow("meeting_end")
        c.transcriptionScheduler.schedule()
        RecorderNotifications.cancelLongMeeting(context.applicationContext)
        MeetingTileService.requestUpdate(context.applicationContext)
        return m
    }
}
