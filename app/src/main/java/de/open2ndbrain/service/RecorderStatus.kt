package de.open2ndbrain.service

import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Zustand des [RecorderService] (ergänzt den [EngineState] um das, was nur der Dienst weiß). */
data class ServiceStatus(
    /** Dienst läuft im Vordergrund (Benachrichtigung sichtbar). */
    val running: Boolean = false,
    /** null = nicht pausiert; [Long.MAX_VALUE] = bis auf Weiteres; sonst Ende der Pause (epoch ms). */
    val pausedUntil: Long? = null,
    /** Der Dienst wird gerade beendet (Dateien werden abgeschlossen). */
    val stopping: Boolean = false,
)

/** Prozessweiter Halter für den Dienst-Zustand; UI und Kachel lesen, nur der Dienst schreibt. */
class RecorderStatusHolder {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    internal fun update(transform: (ServiceStatus) -> ServiceStatus) = _status.update(transform)
}

/** Vereinfachter Gesamtstatus für Benachrichtigung, Kachel und UI. */
enum class DisplayStatus { Off, Starting, Recording, Paused, MicBusy, Error, Stopping }

/**
 * @param pausedBySettings Nutzer hat pausiert (gespeichert), der Dienst läuft aber nicht – z. B. nach einem
 *   Neustart unter Android 14+, wo der Dienst erst nach einem Antippen wieder starten darf. Dann „Pausiert“ statt „Aus“.
 */
fun displayStatus(engine: EngineState, service: ServiceStatus, pausedBySettings: Boolean = false): DisplayStatus = when {
    service.stopping -> DisplayStatus.Stopping
    !service.running -> if (pausedBySettings) DisplayStatus.Paused else DisplayStatus.Off
    service.pausedUntil != null -> DisplayStatus.Paused
    else -> when (engine.status) {
        EngineStatus.Recording -> DisplayStatus.Recording
        EngineStatus.Paused -> DisplayStatus.Paused
        EngineStatus.MicBusy -> DisplayStatus.MicBusy
        EngineStatus.Error -> DisplayStatus.Error
        EngineStatus.Idle -> DisplayStatus.Starting
    }
}

/**
 * Inhalt der Dienst-Benachrichtigung, bewusst grob (Minuten), damit sich das Modell selten ändert
 * und die Benachrichtigung nur bei echten Änderungen neu gesetzt wird.
 */
data class NotificationModel(
    val status: DisplayStatus,
    /** Laufzeit der Sitzung in ganzen Minuten (nur bei Aufnahme). */
    val elapsedMinutes: Long? = null,
    val inputName: String? = null,
    /** Heute gespeicherte Sprache in ganzen Minuten. */
    val storedMinutesToday: Long = 0,
    val pausedUntil: Long? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun from(engine: EngineState, service: ServiceStatus, now: Long): NotificationModel {
            val status = displayStatus(engine, service)
            val active = status == DisplayStatus.Recording || status == DisplayStatus.MicBusy
            val elapsed = if (active) engine.sessionStartedAt?.let { ((now - it).coerceAtLeast(0)) / 60_000 } else null
            return NotificationModel(
                status = status,
                elapsedMinutes = elapsed,
                inputName = if (active) engine.currentInput?.name else null,
                storedMinutesToday = engine.storedMsToday / 60_000,
                pausedUntil = if (status == DisplayStatus.Paused) service.pausedUntil else null,
                errorMessage = if (status == DisplayStatus.Error) engine.errorMessage else null,
            )
        }
    }
}

/** Meldung der Engine bei vollem Speicher (Präfix; Antippen von „Fortsetzen“ hilft dann nicht). */
const val ENGINE_MSG_STORAGE_FULL = de.open2ndbrain.audio.AudioRecordingEngine.MSG_NO_SPACE

fun isStorageFull(errorMessage: String?): Boolean =
    errorMessage?.trim()?.startsWith(ENGINE_MSG_STORAGE_FULL, ignoreCase = true) == true
