package de.open2ndbrain.core

import android.content.Context
import de.open2ndbrain.audio.AudioRecordingEngine
import de.open2ndbrain.audio.FileRecordingRepository
import de.open2ndbrain.markers.FileMarkerRepository
import de.open2ndbrain.models.FileModelManager
import de.open2ndbrain.models.SystemAsrStatus
import de.open2ndbrain.transcription.SystemRecognizerSupport
import de.open2ndbrain.service.RecorderStatusHolder
import de.open2ndbrain.settings.SettingsRepository
import de.open2ndbrain.speakers.FileSpeakerRepository
import de.open2ndbrain.transcription.FileTranscriptRepository
import de.open2ndbrain.work.ContainerRunnerDeps
import de.open2ndbrain.work.MicTest
import de.open2ndbrain.work.NightQuality
import de.open2ndbrain.work.TranscriptionFailures
import de.open2ndbrain.work.TranscriptionOverrides
import de.open2ndbrain.work.TranscriptionRunner
import de.open2ndbrain.work.TranscriptionStats
import de.open2ndbrain.work.TranscriptionScheduler
import de.open2ndbrain.work.TranscriptionStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Einfache manuelle Dependency Injection – eine Instanz pro Prozess (siehe [de.open2ndbrain.O2bApp]). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Prozessweiter Scope für kurze Aufgaben, die eine Komponente (Kachel, Benachrichtigung) überleben sollen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val audioRoot: File = File(appContext.filesDir, "audio").apply { mkdirs() }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val recordingEngine: RecordingEngine by lazy { AudioRecordingEngine(appContext) }

    val recordings: RecordingRepository by lazy { FileRecordingRepository(audioRoot) }

    val modelsRoot: File = File(appContext.filesDir, "models").apply { mkdirs() }

    val speakers: SpeakerRepository by lazy { FileSpeakerRepository(File(appContext.filesDir, "speakers")) }

    val transcripts: TranscriptRepository by lazy { FileTranscriptRepository(audioRoot, recordings) }

    /** Sprachmodelle (Download/Import/Löschen); [models] ist die Vertragssicht. */
    val modelManager: FileModelManager by lazy {
        FileModelManager(appContext, modelsRoot) { transcriptionScheduler.schedule() }.also { m ->
            // Systemerkennung (ohne Dateien): Verfügbarkeit und Sprachpaket über SystemRecognizerSupport
            m.systemAsr = object : FileModelManager.SystemAsr {
                override suspend fun status(): SystemAsrStatus {
                    val st = SystemRecognizerSupport.check(appContext)
                    return when {
                        st.ready -> SystemAsrStatus.Ready
                        !st.available -> SystemAsrStatus.Unavailable
                        else -> SystemAsrStatus.LanguagePackMissing
                    }
                }

                override suspend fun requestDownload() {
                    SystemRecognizerSupport.requestDownload(appContext)
                }
            }
            m.refreshSystemAsr()
        }
    }
    val models: ModelManager get() = modelManager

    /** Status der Transkription für UI/Benachrichtigung; [transcriptionStatus] ist auch ein [TranscriptionStatusHolder]. */
    val transcriptionStatus: TranscriptionStatusStore by lazy { TranscriptionStatusStore() }

    val transcriptionFailures: TranscriptionFailures by lazy {
        TranscriptionFailures(File(appContext.filesDir, "transcription-failures.json"))
    }

    val transcriptionScheduler: TranscriptionScheduler by lazy { TranscriptionScheduler(appContext, this) }

    /** Gemeinsame Abarbeitung der Warteschlange (Dienst und Worker, nie gleichzeitig). */
    val transcriptionRunner: TranscriptionRunner by lazy { TranscriptionRunner(ContainerRunnerDeps(appContext, this)) }

    /** Nachtqualität: vorhandene Transkripte nachts mit genauerem Modell verbessern. */
    val nightQuality: NightQuality by lazy { NightQuality(appContext, this) }

    /** Mikrofon-Test: alle installierten Modelle mit einem vorgelesenen Text vergleichen. */
    val micTest: MicTest by lazy { MicTest(appContext, this) }

    /** Vorgemerkte Vergleichs-Transkriptionen („Mit diesem Modell neu transkribieren“). */
    val transcriptionOverrides: TranscriptionOverrides by lazy {
        TranscriptionOverrides(File(appContext.filesDir, "transcription-overrides.json"))
    }

    /** Diagnose: letzte 20 Transkriptionsläufe (Echtzeitfaktor, Ladezeit, Gerät). */
    val transcriptionStats: TranscriptionStats by lazy { TranscriptionStats(File(appContext.filesDir, "stats/transcription.json")) }

    /** Meetings und Lesezeichen. */
    val markers: FileMarkerRepository by lazy { FileMarkerRepository(File(appContext.filesDir, "markers")) }

    /** Zustand des Aufnahmedienstes (läuft/pausiert) für UI und Kachel. */
    val recorderStatus: RecorderStatusHolder by lazy { RecorderStatusHolder() }
}
