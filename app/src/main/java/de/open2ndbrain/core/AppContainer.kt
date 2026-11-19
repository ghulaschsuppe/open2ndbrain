package de.open2ndbrain.core

import android.content.Context
import de.open2ndbrain.audio.AudioRecordingEngine
import de.open2ndbrain.audio.FileRecordingRepository
import de.open2ndbrain.service.RecorderStatusHolder
import de.open2ndbrain.settings.SettingsRepository
import de.open2ndbrain.speakers.FileSpeakerRepository
import de.open2ndbrain.transcription.FileTranscriptRepository
import java.io.File

/** Einfache manuelle Dependency Injection – eine Instanz pro Prozess (siehe [de.open2ndbrain.O2bApp]). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val audioRoot: File = File(appContext.filesDir, "audio").apply { mkdirs() }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val recordingEngine: RecordingEngine by lazy { AudioRecordingEngine(appContext) }

    val recordings: RecordingRepository by lazy { FileRecordingRepository(audioRoot) }

    val modelsRoot: File = File(appContext.filesDir, "models").apply { mkdirs() }

    val speakers: SpeakerRepository by lazy { FileSpeakerRepository(File(appContext.filesDir, "speakers")) }

    val transcripts: TranscriptRepository by lazy { FileTranscriptRepository(audioRoot, recordings) }

    // ModelManager, TranscriptionStatusHolder und der Transcriber-Aufbau werden vom App-Agenten ergänzt.

    /** Zustand des Aufnahmedienstes (läuft/pausiert) für UI und Kachel. */
    val recorderStatus: RecorderStatusHolder by lazy { RecorderStatusHolder() }
}
