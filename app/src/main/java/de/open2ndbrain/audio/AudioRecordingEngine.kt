package de.open2ndbrain.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import de.open2ndbrain.audio.vad.AudioLevels
import de.open2ndbrain.audio.vad.EnergyVad
import de.open2ndbrain.audio.vad.VoiceActivityDetector
import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.EngineConfig
import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.EngineStatus
import de.open2ndbrain.core.RecordingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Aufnahme-Engine: AudioRecord (16 kHz, mono, PCM16) → Pegel → VAD → Gate → stündliche Opus/OGG-Dateien.
 *
 * Threads je Sitzung (start … stop):
 * - **Capture** (`THREAD_PRIORITY_URGENT_AUDIO`): liest 20-ms-Frames, Pegel, VAD, Gate, Mikrofon-belegt-
 *   Erkennung, Geräteumschaltung. Blockiert nie auf Encoder oder Datenträger. Überwacht den Writer.
 * - **Writer**: Encoder/Muxer, Rotation, Sidecars, Wiederherstellung, `storedMsToday`, Notlöschung,
 *   Watchdog für ein hängendes `AudioRecord.read()`.
 * - **Router** (HandlerThread): [AudioRouter] samt Android-Callbacks (Geräte, Aufnahme-Konfiguration, Routing).
 *
 * Alle öffentlichen Methoden senden nur Befehle und kehren sofort zurück.
 * Fehler in den Threads werden gefangen → Status `Error` + Meldung, Neuversuch mit Backoff 2…30 s.
 */
class AudioRecordingEngine(context: Context) : RecordingEngine {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val lock = Any()
    private var session: Session? = null
    private var lastSession: Session? = null

    override fun start(config: EngineConfig) {
        synchronized(lock) {
            val s = session
            if (s != null) {
                // Idempotent: Config übernehmen, ggf. fortsetzen und Mikrofon sofort neu versuchen.
                s.send(Cmd.Config(config))
                s.send(Cmd.Restart)
                return
            }
            Log.i(TAG, "start: $config")
            val ns = Session(config, lastSession?.writerThread)
            session = ns
            lastSession = ns
            ns.launch()
        }
    }

    override fun pause() {
        synchronized(lock) { session?.send(Cmd.Pause) }
    }

    override fun resume() {
        synchronized(lock) { session?.send(Cmd.Resume) }
    }

    override fun stop() {
        synchronized(lock) {
            val s = session
            if (s == null) {
                if (_state.value.status != EngineStatus.Idle) publishIdle()
                return
            }
            Log.i(TAG, "stop")
            session = null
            s.send(Cmd.Stop) // hängt read(), entblockt der Watchdog im Writer-Thread
        }
    }

    override fun updateConfig(config: EngineConfig) {
        synchronized(lock) { session?.send(Cmd.Config(config)) }
    }

    /** Nicht blockierend; No-op ohne laufende Aufnahme oder in der Pause. */
    override fun splitNow(reason: String) {
        synchronized(lock) { session?.splitNow(reason) }
    }

    private fun publishIdle() {
        _state.update {
            it.copy(
                status = EngineStatus.Idle, level = 0f, speechActive = false, currentFile = null,
                currentInput = null, sessionStartedAt = null, errorMessage = null,
            )
        }
    }

    private sealed interface Cmd {
        data class Config(val config: EngineConfig) : Cmd
        data object Pause : Cmd
        data object Resume : Cmd
        data object Restart : Cmd
        data object Stop : Cmd
    }

    /** Aufträge an den Writer-Thread (Reihenfolge bleibt erhalten). */
    private sealed interface WItem {
        class Audio(val pcm: ShortArray, val epochMs: Long, val segmentStart: Boolean, val input: String?) : WItem
        data class Event(val type: String, val detail: String?, val atMs: Long) : WItem
        data class Split(val type: String, val detail: String?) : WItem
        data class SplitNow(val reason: String) : WItem
        data class Close(val type: String) : WItem
        data class Settings(val settings: HourlyWriter.Settings) : WItem
        data object Quit : WItem
    }

    private enum class Busy { NONE, BUSY, BACKGROUND }

    /** Eine Sitzung von start() bis stop(). */
    private inner class Session(initial: EngineConfig, private val previousWriter: Thread?) {
        private val cmds = LinkedBlockingQueue<Cmd>()
        private val writerQueue = LinkedBlockingQueue<WItem>()
        private val startedAt = System.currentTimeMillis()

        @Volatile private var config = initial
        @Volatile private var writerError: String? = null
        @Volatile private var pendingPreferred: Array<AudioDeviceInfo?>? = null
        @Volatile private var activeRecord: AudioRecord? = null
        @Volatile private var lastFrameAt = 0L
        @Volatile private var paused = false
        @Volatile private var busyCheckRequested = false
        @Volatile private var routeCheckRequested = false

        @Volatile var writerThread: Thread = Thread({ writerLoop(restart = false) }, "O2B-AudioWriter")
            private set
        private val captureThread = Thread({ captureLoop() }, "O2B-AudioCapture")
        private val routerThread = HandlerThread("O2B-AudioRouter")
        private lateinit var router: AudioRouter
        private lateinit var cbHandler: Handler

        private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                busyCheckRequested = true
            }
        }
        private val routingListener = AudioRouting.OnRoutingChangedListener { routeCheckRequested = true }

        fun send(cmd: Cmd) {
            cmds.add(cmd)
        }

        /** Direkt in die Writer-Warteschlange: Reihenfolge zu den Frames bleibt erhalten, kein Gate-Reset. */
        fun splitNow(reason: String) {
            if (paused) return
            Log.i(TAG, "splitNow: $reason")
            writerQueue.add(WItem.SplitNow(reason))
        }

        fun launch() {
            RecordingSignals.sessionStarted()
            update { it.copy(status = EngineStatus.Recording, sessionStartedAt = startedAt, errorMessage = null) }
            routerThread.start()
            cbHandler = Handler(routerThread.looper)
            router = AudioRouter(appContext, cbHandler, object : AudioRouter.Listener {
                override fun onInputsChanged(inputs: List<AudioInputInfo>) {
                    update { it.copy(availableInputs = inputs) }
                }

                override fun onPreferredDeviceChanged(device: AudioDeviceInfo?) {
                    Log.i(TAG, "Bevorzugtes Mikrofon: ${device?.let { AudioRouter.displayName(it) } ?: "Standard"}")
                    pendingPreferred = arrayOf(device)
                }
            })
            router.start(config.micPreference, active = true)
            runCatching { audioManager.registerAudioRecordingCallback(recordingCallback, cbHandler) }
                .onFailure { Log.w(TAG, "AudioRecordingCallback nicht registrierbar", it) }
            writerQueue.add(WItem.Settings(settingsOf(config)))
            writerThread.start()
            captureThread.start()
        }

        private fun isCurrent() = synchronized(lock) { session === this }

        /** Zustand nur ändern, solange diese Sitzung die aktuelle ist. */
        private fun update(f: (EngineState) -> EngineState) {
            if (isCurrent()) _state.update(f)
        }

        /** Hängt read() (Treiber liefert nichts mehr), löst stop() am AudioRecord die Blockade. */
        private fun unblockIfStalled() {
            val rec = activeRecord ?: return
            if (SystemClock.elapsedRealtime() - lastFrameAt > STALL_MS) {
                Log.w(TAG, "Aufnahme hängt – AudioRecord wird angehalten")
                runCatching { rec.stop() }
            }
        }

        // ---------------------------------------------------------------- Capture-Thread

        private var record: AudioRecord? = null
        private var preferredDevice: AudioDeviceInfo? = null
        private var stopRequested = false
        private var retryAt = 0L
        private var failures = 0
        private var openedAt = 0L
        private var captureError: String? = null
        private var openFailedBusy = false
        private var busy = Busy.NONE
        private var busySince = 0L
        private var silencedSince = 0L
        private var freeSince = 0L
        private var zeroMs = 0L
        private var lastBusyCheck = 0L
        private var lastRouteCheck = 0L
        private var lastStatusPublish = 0L
        private var lastWriterCheck = 0L
        private var lastInput: AudioInputInfo? = null
        private var inputName: String? = null
        private var levelMax = 0.0
        private var levelFrames = 0
        private var publishedLevel = -1f
        private var publishedSpeech = false
        private var lastSpeech = false
        private var gapPending = false
        private var lastBusyReopen = 0L

        private val frame = ShortArray(FRAME_SAMPLES)
        private val clock = FrameClock(SAMPLE_RATE, hourStart = { RecordingLayout.hourStart(it, ZoneId.systemDefault()) })
        private val vad: VoiceActivityDetector = EnergyVad(SAMPLE_RATE)
        private val gate = SpeechGate(FRAME_SAMPLES, PRE_ROLL_FRAMES, POST_ROLL_FRAMES, initial.discardSilence) { pcm, len, ts, segStart ->
            if (writerQueue.size >= MAX_QUEUED_ITEMS) {
                gapPending = true // Writer kommt nicht hinterher: Frame verwerfen, Lücke markieren
            } else {
                writerQueue.add(WItem.Audio(pcm.copyOf(len), ts, segStart || gapPending, inputName))
                gapPending = false
            }
        }

        private fun captureLoop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            Log.i(TAG, "Capture-Thread gestartet")
            while (!stopRequested) {
                try {
                    captureStep()
                } catch (t: Throwable) {
                    Log.e(TAG, "Fehler im Aufnahme-Thread", t)
                    closeRecord()
                    captureError = "Aufnahmefehler: ${t.message ?: t.javaClass.simpleName}"
                    scheduleRetry()
                    publishStatus()
                }
            }
            closeRecord()
            runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
            writerQueue.add(WItem.Close("stopped"))
            writerQueue.add(WItem.Quit)
            ensureWriterAlive() // sonst würden Close/Quit nie verarbeitet
            runCatching { router.stop() }
            routerThread.quitSafely()
            Log.i(TAG, "Capture-Thread beendet")
        }

        private fun captureStep() {
            // Befehle
            while (true) handle(cmds.poll() ?: break)
            if (stopRequested) return

            val now0 = SystemClock.elapsedRealtime()
            if (now0 - lastWriterCheck >= WRITER_CHECK_MS) {
                lastWriterCheck = now0
                ensureWriterAlive()
            }

            if (paused) {
                handle(cmds.poll(500, TimeUnit.MILLISECONDS) ?: return)
                return
            }
            var rec = record
            if (rec == null) {
                val wait = retryAt - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    publishStatus()
                    cmds.poll(minOf(wait, 500), TimeUnit.MILLISECONDS)?.let(::handle)
                    return
                }
                rec = try {
                    openRecord()
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord nicht verfügbar: ${t.message}")
                    openFailedBusy = isCallMode()
                    captureError = if (t is SecurityException) "Berechtigung RECORD_AUDIO fehlt"
                    else "Mikrofon nicht verfügbar: ${t.message ?: t.javaClass.simpleName}"
                    scheduleRetry()
                    publishStatus()
                    return
                }
                record = rec
                activeRecord = rec
                lastFrameAt = SystemClock.elapsedRealtime()
                captureError = null
                openFailedBusy = false
                openedAt = SystemClock.elapsedRealtime()
                clock.reset()
                gate.discontinuity()
                vad.reset()
                // silencedSince/zeroMs bleiben erhalten: Neuöffnen während „belegt“ soll nicht flackern.
                lastRouteCheck = 0
                busyCheckRequested = true
                runCatching { rec.addOnRoutingChangedListener(routingListener, cbHandler) }
                Log.i(TAG, "AudioRecord geöffnet (Quelle ${rec.audioSource}, Session ${rec.audioSessionId})")
            }

            pendingPreferred?.let { holder ->
                pendingPreferred = null
                preferredDevice = holder[0]
                runCatching { rec.setPreferredDevice(preferredDevice) }
                    .onFailure { Log.w(TAG, "setPreferredDevice fehlgeschlagen", it) }
                routeCheckRequested = true
            }

            val n = rec.read(frame, 0, FRAME_SAMPLES, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                Log.w(TAG, "AudioRecord.read = $n → neu öffnen")
                closeRecord()
                captureError = if (n == 0) null else "Lesefehler ($n)"
                scheduleRetry()
                publishStatus()
                return
            }
            val nowMs = System.currentTimeMillis()
            val nowRt = SystemClock.elapsedRealtime()
            lastFrameAt = nowRt
            if (failures > 0 && nowRt - openedAt > STABLE_MS) failures = 0
            val ts = clock.frameStart(n, nowMs)
            val boundary = clock.lastWasJump
            if (boundary) Log.i(TAG, "Zeitsprung erkannt – neues Segment")

            // Pegel ~10×/s berechnen, aber nur bei merklicher Änderung veröffentlichen
            val rms = AudioLevels.rms(frame, 0, n)
            if (rms > levelMax) levelMax = rms
            if (++levelFrames >= LEVEL_EVERY_FRAMES) {
                val level = AudioLevels.displayLevel(levelMax)
                levelMax = 0.0
                levelFrames = 0
                if (abs(level - publishedLevel) > LEVEL_DELTA || lastSpeech != publishedSpeech) {
                    publishedLevel = level
                    publishedSpeech = lastSpeech
                    val speech = lastSpeech
                    update { it.copy(level = level, speechActive = speech) }
                }
            }

            // Mikrofon belegt? (Callback-gesteuert, Polling nur als Rückfall bzw. in Übergängen)
            var allZero = true
            for (i in 0 until n) if (frame[i].toInt() != 0) { allZero = false; break }
            val prevZeroMs = zeroMs
            zeroMs = if (allZero) zeroMs + n * 1000L / SAMPLE_RATE else 0
            val zeroTransition = (busy == Busy.NONE && prevZeroMs < ZERO_SIGNAL_MS && zeroMs >= ZERO_SIGNAL_MS) ||
                (busy != Busy.NONE && prevZeroMs > 0 && zeroMs == 0L)
            val transitional = (silencedSince != 0L && busy != Busy.BACKGROUND) || freeSince != 0L
            if (busyCheckRequested || zeroTransition || nowRt - lastBusyCheck >= FALLBACK_CHECK_MS ||
                (transitional && nowRt - lastBusyCheck >= BUSY_CHECK_MS)
            ) {
                busyCheckRequested = false
                lastBusyCheck = nowRt
                checkBusy(rec, nowRt)
            }
            if (routeCheckRequested || lastRouteCheck == 0L || nowRt - lastRouteCheck >= FALLBACK_CHECK_MS) {
                routeCheckRequested = false
                checkRoute(rec, nowRt)
            }
            if (nowRt - lastStatusPublish >= STATUS_PUBLISH_MS) {
                lastStatusPublish = nowRt
                publishStatus()
            }
            if (busy != Busy.NONE) {
                lastSpeech = false
                // Regelmäßig neu öffnen – manche Geräte heben die Stummschaltung sonst nicht auf.
                // In der ersten Minute alle 10 s, danach alle 60 s (Akku bei dauerhafter Sperre).
                val interval = if (nowRt - busySince < BUSY_LONG_AFTER_MS) BUSY_REOPEN_MS else BUSY_REOPEN_LONG_MS
                if (nowRt - lastBusyReopen >= interval) {
                    lastBusyReopen = nowRt
                    closeRecord()
                    retryAt = nowRt + QUICK_RETRY_MS
                }
                return
            }

            lastSpeech = vad.accept(frame, 0, n)
            gate.process(frame, n, ts, lastSpeech, boundary)
        }

        private fun handle(cmd: Cmd) {
            when (cmd) {
                Cmd.Pause -> if (!paused) {
                    paused = true
                    closeRecord()
                    gate.discontinuity()
                    writerQueue.add(WItem.Close("paused"))
                    router.setActive(false)
                    lastSpeech = false
                    busy = Busy.NONE
                    zeroMs = 0
                    silencedSince = 0
                    freeSince = 0
                    update { it.copy(status = EngineStatus.Paused, level = 0f, speechActive = false, errorMessage = null) }
                    Log.i(TAG, "pausiert")
                }
                Cmd.Resume -> if (paused) {
                    paused = false
                    retryAt = 0
                    failures = 0
                    writerQueue.add(WItem.Event("resumed", null, System.currentTimeMillis()))
                    router.setActive(true)
                    publishStatus()
                    Log.i(TAG, "fortgesetzt")
                }
                Cmd.Restart -> {
                    if (paused) handle(Cmd.Resume)
                    if (record == null || busy != Busy.NONE || captureError != null) {
                        closeRecord()
                        retryAt = 0
                        failures = 0
                        lastBusyReopen = SystemClock.elapsedRealtime()
                    }
                    router.reevaluate()
                }
                is Cmd.Config -> applyConfig(cmd.config)
                Cmd.Stop -> stopRequested = true
            }
        }

        private fun applyConfig(c: EngineConfig) {
            val old = config
            config = c
            gate.discardSilence = c.discardSilence
            if (old.outputRoot != c.outputRoot || old.bitrateKbps != c.bitrateKbps ||
                old.discardSilence != c.discardSilence
            ) {
                writerQueue.add(WItem.Settings(settingsOf(c)))
                writerQueue.add(
                    WItem.Split(
                        "config_changed",
                        "bitrate=${c.bitrateKbps}kbps discardSilence=${c.discardSilence}",
                    ),
                )
            }
            if (old.micPreference != c.micPreference) router.setPreference(c.micPreference)
        }

        /** Erster Neuversuch nach 200 ms (z. B. Routing-Wechsel), danach 2 s, 4 s … 30 s. */
        private fun scheduleRetry() {
            val delay = if (failures == 0) QUICK_RETRY_MS
            else (BACKOFF_START_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(BACKOFF_MAX_MS)
            failures++
            retryAt = SystemClock.elapsedRealtime() + delay
        }

        private fun closeRecord() {
            val rec = record ?: return
            record = null
            activeRecord = null
            runCatching { rec.removeOnRoutingChangedListener(routingListener) }
            runCatching { rec.stop() }
            runCatching { rec.release() }
            gate.discontinuity()
            publishedLevel = 0f
            publishedSpeech = false
            update { it.copy(level = 0f, speechActive = false) }
        }

        /** Writer-Thread abgestürzt? → Fehler melden und neu starten (offene Datei wird gerettet). */
        private fun ensureWriterAlive() {
            val t = writerThread
            if (t.isAlive || t.state == Thread.State.NEW) return
            Log.e(TAG, "Writer-Thread ausgefallen – Neustart")
            writerError = MSG_WRITER_DIED
            if (!paused) update { it.copy(status = EngineStatus.Error, errorMessage = MSG_WRITER_DIED) }
            val nt = Thread({ writerLoop(restart = true) }, "O2B-AudioWriter")
            writerThread = nt
            nt.start()
        }

        private fun isCallMode(): Boolean = when (audioManager.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_RINGTONE,
            -> true
            else -> false
        }

        /** Stummschaltung durch das System (Telefonat, andere App, Hintergrundsperre) oder Nullsignal. */
        private fun checkBusy(rec: AudioRecord, nowRt: Long) {
            val silenced = runCatching { rec.activeRecordingConfiguration?.isClientSilenced == true }
                .getOrDefault(false)
            if (silenced) {
                if (silencedSince == 0L) silencedSince = nowRt
            } else {
                silencedSince = 0
            }
            val newBusy = when {
                silenced -> {
                    val others = runCatching {
                        audioManager.activeRecordingConfigurations.count { it.clientAudioSessionId != rec.audioSessionId }
                    }.getOrDefault(0)
                    if (!isCallMode() && others == 0 && nowRt - silencedSince >= BACKGROUND_SILENCED_MS) {
                        Busy.BACKGROUND
                    } else {
                        Busy.BUSY
                    }
                }
                zeroMs >= ZERO_SIGNAL_MS -> Busy.BUSY
                else -> Busy.NONE
            }
            if (newBusy == Busy.NONE && busy != Busy.NONE) {
                // Hysterese: erst nach 1 s ohne Stummschaltung/Nullsignal als frei melden.
                if (freeSince == 0L) freeSince = nowRt
                if (nowRt - freeSince < FREE_CONFIRM_MS) return
            }
            freeSince = 0
            if (newBusy == busy) return
            val nowMs = System.currentTimeMillis()
            if (busy == Busy.NONE) {
                val detail = when {
                    newBusy == Busy.BACKGROUND -> "background_restricted"
                    silenced -> "silenced"
                    else -> "zero_signal"
                }
                Log.i(TAG, "Mikrofon belegt ($detail)")
                writerQueue.add(WItem.Event("mic_busy", detail, nowMs))
                gate.discontinuity()
                vad.reset()
                lastBusyReopen = nowRt
                busySince = nowRt
            } else if (newBusy == Busy.NONE) {
                Log.i(TAG, "Mikrofon wieder frei")
                writerQueue.add(WItem.Event("mic_free", null, nowMs))
            }
            busy = newBusy
            publishStatus()
        }

        /**
         * Tatsächlich geroutetes Mikrofon verfolgen. Ein Wechsel ergibt nur ein Ereignis `mic_changed`;
         * der Writer beginnt ein neues Segment (anderer `input`), die Datei läuft weiter.
         */
        private fun checkRoute(rec: AudioRecord, nowRt: Long) {
            lastRouteCheck = nowRt
            val dev = runCatching { rec.routedDevice }.getOrNull() ?: return
            val info = AudioRouter.toInfo(dev)
            val prev = lastInput
            if (prev != null && prev.type == info.type && prev.name == info.name) return
            lastInput = info
            inputName = info.name
            update { it.copy(currentInput = info) }
            val nowMs = System.currentTimeMillis()
            if (prev != null) {
                Log.i(TAG, "Mikrofon gewechselt: ${prev.name} → ${info.name}")
                writerQueue.add(WItem.Event("mic_changed", "${prev.name} → ${info.name}", nowMs))
            } else {
                writerQueue.add(WItem.Event("input", info.name, nowMs))
            }
        }

        private fun publishStatus() {
            val (status, msg) = when {
                paused -> EngineStatus.Paused to null
                captureError != null && openFailedBusy -> EngineStatus.MicBusy to null
                captureError != null -> EngineStatus.Error to captureError
                busy == Busy.BACKGROUND -> EngineStatus.Error to MSG_BACKGROUND
                busy == Busy.BUSY -> EngineStatus.MicBusy to null
                writerError != null -> EngineStatus.Error to writerError
                else -> EngineStatus.Recording to null
            }
            val cur = _state.value
            if (cur.status != status || cur.errorMessage != msg) {
                update { it.copy(status = status, errorMessage = msg) }
            }
        }

        private fun openRecord(): AudioRecord {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("RECORD_AUDIO fehlt")
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IllegalStateException("16 kHz mono nicht unterstützt ($minBuf)")
            val bufSize = maxOf(minBuf * 4, SAMPLE_RATE * 2 * RECORD_BUFFER_SECONDS)
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()
            var lastError: Throwable? = null
            for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                var rec: AudioRecord? = null
                try {
                    rec = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSize)
                        .build()
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("AudioRecord nicht initialisiert (Quelle $source)")
                    }
                    preferredDevice?.let { rec.setPreferredDevice(it) }
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        throw IllegalStateException("startRecording fehlgeschlagen – Mikrofon belegt?")
                    }
                    return rec
                } catch (e: SecurityException) {
                    rec?.release()
                    throw e
                } catch (t: Throwable) {
                    rec?.release()
                    lastError = t
                    Log.w(TAG, "Quelle $source nicht nutzbar: ${t.message}")
                }
            }
            throw lastError ?: IllegalStateException("AudioRecord nicht verfügbar")
        }

        // ---------------------------------------------------------------- Writer-Thread

        private var storedDay: LocalDate? = null
        private var storedBase = 0L
        @Volatile private var storedDirty = true
        private var lastStoredPublish = 0L
        private var writerRetryAt = 0L
        private var writerBackoff = BACKOFF_START_MS
        private var writerGap = false
        private var nextStorageCheck = 0L

        private val writer = HourlyWriter(
            encoderFactory = MediaCodecAudioEncoder.FACTORY,
            preferredFormat = {
                if (MediaCodecAudioEncoder.isSupported(AudioFileFormat.OPUS_OGG)) AudioFileFormat.OPUS_OGG
                else AudioFileFormat.AAC_M4A
            },
            listener = { file, closed ->
                storedDirty = true
                update { it.copy(currentFile = if (closed) null else file) }
            },
        )

        private fun writerLoop(restart: Boolean) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            try {
                if (restart) {
                    // Zustand des abgestürzten Writers: offene Datei so gut wie möglich retten.
                    runCatching { writer.abort("writer_restart") }
                } else {
                    previousWriter?.let { if (it.isAlive) it.join(PREVIOUS_JOIN_MS) }
                    runCatching {
                        val n = RecordingSignals.recoverForEngine(config.outputRoot)
                        if (n > 0) {
                            Log.i(TAG, "$n verwaiste Teildatei(en) wiederhergestellt")
                            RecordingSignals.notifyChanged()
                        }
                    }.onFailure { Log.e(TAG, "Wiederherstellung fehlgeschlagen", it) }
                }

                while (true) {
                    try {
                        val item = writerQueue.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
                        if (item === WItem.Quit) break
                        if (item != null) process(item)
                        housekeeping()
                    } catch (t: Throwable) {
                        // Nie den Writer verlieren: protokollieren und weitermachen.
                        Log.e(TAG, "Unerwarteter Fehler im Writer-Thread", t)
                        runCatching { Thread.sleep(100) }
                    }
                }
                runCatching { writer.close("stopped") }
                RecordingSignals.sessionEnded()
                Log.i(TAG, "Writer-Thread beendet")
                synchronized(lock) {
                    if (session == null) publishIdle()
                }
            } catch (t: Throwable) {
                // Nur bei schwerem Fehler (z. B. außerhalb der Schleife); die Capture-Seite startet neu.
                Log.e(TAG, "Writer-Thread unerwartet beendet", t)
            }
        }

        private fun process(item: WItem) {
            try {
                when (item) {
                    is WItem.Audio -> {
                        if (SystemClock.elapsedRealtime() < writerRetryAt) {
                            writerGap = true
                            return
                        }
                        writer.write(item.pcm, item.pcm.size, item.epochMs, item.segmentStart || writerGap, item.input)
                        writerGap = false
                        // Fehler gilt erst nach erfolgreichem Schreiben als behoben.
                        if (writerError != null) {
                            writerError = null
                            writerBackoff = BACKOFF_START_MS
                        }
                    }
                    is WItem.Event -> writer.addEvent(item.type, item.detail, item.atMs)
                    is WItem.Split -> writer.split(item.type, item.detail)
                    is WItem.SplitNow -> writer.splitNow(item.reason)
                    is WItem.Close -> writer.close(item.type)
                    is WItem.Settings -> writer.settings = item.settings
                    WItem.Quit -> Unit
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Fehler beim Schreiben", t)
                val noSpace = isNoSpace(t, config.outputRoot)
                val msg = if (noSpace) MSG_NO_SPACE else "Schreibfehler: ${t.message ?: t.javaClass.simpleName}"
                runCatching { writer.abort(msg) }
                writerError = msg
                writerGap = true
                writerRetryAt = SystemClock.elapsedRealtime() + writerBackoff
                writerBackoff = (writerBackoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                if (noSpace) nextStorageCheck = 0 // Notlöschung sofort prüfen
                // Während der Pause bleibt der Status „Paused“; der Fehler zeigt sich nach dem Fortsetzen.
                if (!paused) update { it.copy(status = EngineStatus.Error, errorMessage = msg) }
            }
        }

        private fun housekeeping() {
            runCatching { writer.tick(idle = writerQueue.isEmpty()) }
                .onFailure { Log.w(TAG, "Writer-Tick fehlgeschlagen", it) }
            val nowRt = SystemClock.elapsedRealtime()
            if (nowRt - lastStoredPublish >= STORED_PUBLISH_MS || storedDirty) {
                lastStoredPublish = nowRt
                runCatching { publishStored() }.onFailure { Log.w(TAG, "storedMsToday fehlgeschlagen", it) }
            }
            if (nowRt >= nextStorageCheck) {
                nextStorageCheck = nowRt + STORAGE_CHECK_MS
                runCatching { emergencyCleanup() }.onFailure { Log.w(TAG, "Notlöschung fehlgeschlagen", it) }
            }
            // Watchdog: liefert der Treiber keine Daten mehr, read() entblocken (auch nach stop()).
            if (!paused && activeRecord != null && nowRt - lastFrameAt > STALL_MS) {
                unblockIfStalled()
                lastFrameAt = nowRt
            }
        }

        @SuppressLint("UsableSpace") // bewusst konservativ
        private fun emergencyCleanup() {
            val root = config.outputRoot
            val deleted = StorageJanitor.cleanup(root)
            if (deleted.isEmpty()) return
            val detail = "${deleted.size} älteste Aufnahme(n) gelöscht, frei ${root.usableSpace / StorageJanitor.MB} MB"
            writer.addEvent("storage_cleanup", detail)
            storedDirty = true
        }

        private fun publishStored() {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            if (storedDirty || today != storedDay) {
                storedDay = today
                storedDirty = false
                storedBase = SidecarIo.storedMsInDir(
                    RecordingLayout.dayDir(config.outputRoot, today),
                    exclude = setOfNotNull(writer.currentBaseName),
                )
            }
            val current = writer.currentHourStart
                ?.takeIf { RecordingLayout.localDate(it, zone) == today }
                ?.let { writer.currentStoredMs } ?: 0L
            val total = storedBase + current
            if (_state.value.storedMsToday != total) update { it.copy(storedMsToday = total) }
        }
    }

    private fun settingsOf(c: EngineConfig) = HourlyWriter.Settings(c.outputRoot, c.bitrateKbps, c.discardSilence)

    companion object {
        private const val TAG = "O2B-Audio"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = SAMPLE_RATE / 50 // 20 ms
        private const val PRE_ROLL_FRAMES = 50 // 1 s
        private const val POST_ROLL_FRAMES = 75 // 1,5 s
        private const val RECORD_BUFFER_SECONDS = 2
        private const val LEVEL_EVERY_FRAMES = 5 // 100 ms
        private const val LEVEL_DELTA = 0.02f
        private const val BUSY_CHECK_MS = 250L
        private const val FALLBACK_CHECK_MS = 5_000L
        private const val STATUS_PUBLISH_MS = 250L
        private const val WRITER_CHECK_MS = 1_000L
        private const val ZERO_SIGNAL_MS = 3_000L
        private const val BACKGROUND_SILENCED_MS = 3_000L
        private const val BUSY_REOPEN_MS = 10_000L
        private const val BUSY_REOPEN_LONG_MS = 60_000L
        private const val BUSY_LONG_AFTER_MS = 60_000L
        private const val FREE_CONFIRM_MS = 1_000L
        private const val BACKOFF_START_MS = 2_000L
        private const val QUICK_RETRY_MS = 200L
        private const val STABLE_MS = 5_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val STALL_MS = 5_000L
        private const val MAX_QUEUED_ITEMS = 3_000 // 60 s Audio
        private const val WRITER_POLL_MS = 500L
        private const val STORED_PUBLISH_MS = 1_000L
        private const val STORAGE_CHECK_MS = 60_000L
        private const val PREVIOUS_JOIN_MS = 5_000L
        const val MSG_BACKGROUND = "Mikrofon im Hintergrund gesperrt"
        const val MSG_NO_SPACE = "Speicher voll"
        const val MSG_WRITER_DIED = "Schreib-Thread ausgefallen – neu gestartet"

        /** ENOSPC in der Ursachenkette oder (fast) kein freier Platz mehr? */
        @SuppressLint("UsableSpace")
        internal fun isNoSpace(t: Throwable, root: File?): Boolean {
            var c: Throwable? = t
            var depth = 0
            while (c != null && depth++ < 10) {
                if (c is ErrnoException && c.errno == OsConstants.ENOSPC) return true
                val m = c.message.orEmpty()
                if ("ENOSPC" in m || "No space left" in m) return true
                c = c.cause
            }
            return root != null && runCatching { root.isDirectory && root.usableSpace < 8 * 1024 * 1024 }.getOrDefault(false)
        }
    }
}
