package de.open2ndbrain.core

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Vertrag zwischen Aufnahme-Engine (Paket `audio`) und App-Hülle (Pakete `service`, `ui`).
 *
 * Die Engine liest dauerhaft das Mikrofon, erkennt Sprache, schreibt stündliche Opus/OGG-Dateien
 * samt JSON-Sidecar und wählt das Mikrofon (intern/Bluetooth). Sie kennt weder Service noch UI.
 *
 * Threading: Alle Methoden sind nicht-blockierend und dürfen vom Main-Thread aufgerufen werden.
 * Die eigentliche Arbeit läuft auf einem eigenen Thread der Engine.
 */
interface RecordingEngine {
    val state: StateFlow<EngineState>

    /** Startet die Aufnahme. Voraussetzung: RECORD_AUDIO ist erteilt, Aufrufer ist ein laufender Mikrofon-Vordergrunddienst. */
    fun start(config: EngineConfig)

    /** Hält die Aufnahme an; die laufende Datei wird geschlossen. Das Mikrofon wird freigegeben. */
    fun pause()

    /** Setzt nach [pause] fort (neue Teildatei innerhalb der Stunde). */
    fun resume()

    /** Beendet die Aufnahme, schließt Dateien sauber ab, gibt alle Ressourcen frei. */
    fun stop()

    /**
     * Schließt die laufende Datei sofort ab und beginnt nahtlos eine neue Teildatei derselben Stunde
     * (z. B. bei Meeting-Ende, damit das Meeting gleich transkribiert werden kann). Sidecar-Ereignis `split` mit [reason].
     * No-op, wenn nicht aufgenommen wird.
     */
    fun splitNow(reason: String) {}

    /** Übernimmt geänderte Einstellungen im laufenden Betrieb (Bitrate wirkt ab der nächsten Datei). */
    fun updateConfig(config: EngineConfig)
}

data class EngineConfig(
    /** Wurzelverzeichnis für Aufnahmen, i. d. R. `context.filesDir/audio`. */
    val outputRoot: File,
    /** Opus-Bitrate in kbit/s. Standard 16, Sparmodus 12. */
    val bitrateKbps: Int = 16,
    /** Nur Sprachabschnitte (+Vor-/Nachlauf) speichern. */
    val discardSilence: Boolean = true,
    val micPreference: MicPreference = MicPreference.Auto,
)

sealed interface MicPreference {
    /** Bluetooth-/kabelgebundenes Headset bevorzugen, sonst internes Mikrofon. */
    data object Auto : MicPreference

    /** Immer das eingebaute Mikrofon (kein SCO, Musik über BT bleibt in voller Qualität). */
    data object BuiltInOnly : MicPreference

    /** Ein bestimmtes Gerät (über Produktname + Typ wiedererkannt, da die IDs sich ändern). */
    data class Specific(val productName: String, val type: InputType) : MicPreference
}

enum class InputType { BuiltIn, BluetoothSco, BluetoothLe, Wired, Usb, Other }

data class AudioInputInfo(
    /** `AudioDeviceInfo.id` – nur innerhalb einer Sitzung gültig. */
    val id: Int,
    val name: String,
    val type: InputType,
)

enum class EngineStatus {
    Idle,
    Recording,
    Paused,
    /** Eine andere App (z. B. Telefonat) belegt das Mikrofon; Engine wartet und setzt automatisch fort. */
    MicBusy,
    Error,
}

data class EngineState(
    val status: EngineStatus = EngineStatus.Idle,
    val currentInput: AudioInputInfo? = null,
    val availableInputs: List<AudioInputInfo> = emptyList(),
    /** Pegel 0..1 (RMS, logarithmisch skaliert) für die Anzeige, ca. 10 Updates/s. */
    val level: Float = 0f,
    /** Erkennt die VAD gerade Sprache? */
    val speechActive: Boolean = false,
    val currentFile: File? = null,
    /** Wanduhrzeit (epoch ms) des Starts der aktuellen Sitzung, null wenn nicht aktiv. */
    val sessionStartedAt: Long? = null,
    /** Heute gespeicherte Sprachdauer in ms (über alle Dateien des aktuellen Tages). */
    val storedMsToday: Long = 0,
    val errorMessage: String? = null,
)
