package de.open2ndbrain.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.EngineConfig
import de.open2ndbrain.core.EngineStatus
import de.open2ndbrain.core.RecordingEngine
import de.open2ndbrain.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vordergrunddienst (Typ `microphone`), der die [RecordingEngine] steuert.
 *
 * Regeln (Android 12/14+): Ein Mikrofon-Vordergrunddienst darf nur gestartet werden, solange die App sichtbar ist
 * (Activity, auch die unsichtbare [de.open2ndbrain.ui.StartRecordingActivity]). Deshalb bleibt der Dienst auch
 * während einer Pause im Vordergrund – nur so darf er das Mikrofon danach aus dem Hintergrund wieder öffnen.
 * Alle Befehle laufen nacheinander auf dem Main-Thread (Mutex).
 */
class RecorderService : LifecycleService() {

    private val container: AppContainer get() = (application as O2bApp).container
    private val engine: RecordingEngine get() = container.recordingEngine
    private val statusHolder: RecorderStatusHolder get() = container.recorderStatus

    private val commandMutex = Mutex()
    private val handler = Handler(Looper.getMainLooper())
    private val autoResumeRunnable = Runnable { submit(ACTION_AUTO_RESUME, startId = null) }

    private var inForeground = false
    /** `engine.start()` aufgerufen und noch kein `stop()`. */
    private var engineStarted = false
    /** `engine.pause()` innerhalb der laufenden Engine-Sitzung aufgerufen. */
    private var enginePaused = false
    private var configSent: EngineConfig? = null
    private var lastModel = NotificationModel(DisplayStatus.Starting)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        RecorderNotifications.ensureChannels(this)
        observeSettings()
        observeNotification()
        observeTile()
        observePersistentError()
        runRetentionLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // null-Intent = Neustart durch das System (START_STICKY).
        val action = intent?.action ?: ACTION_RESTORE
        val foregroundRequired = intent?.getBooleanExtra(EXTRA_FOREGROUND_START, false) == true
        // Nach startForegroundService() muss startForeground() sofort und synchron folgen.
        if (foregroundRequired && !enterForeground()) {
            onForegroundDenied(startId)
            return START_NOT_STICKY
        }
        submit(action, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoResumeRunnable)
        if (engineStarted) {
            engine.stop()
            engineStarted = false
        }
        releaseWakeLock()
        inForeground = false
        statusHolder.update { ServiceStatus() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Befehle

    private fun submit(action: String, startId: Int?) {
        lifecycleScope.launch {
            commandMutex.withLock {
                try {
                    handle(action, startId)
                } catch (e: Exception) {
                    Log.e(TAG, "Befehl $action fehlgeschlagen", e)
                }
            }
        }
    }

    private suspend fun handle(action: String, startId: Int?) {
        val settings = container.settings
        when (action) {
            ACTION_START -> {
                settings.setRecordingDesired(true)
                RecorderNotifications.cancelResumePrompt(this)
                // Erneutes start() ist laut Engine idempotent und öffnet das Mikrofon sofort neu – wichtig, wenn
                // es zuvor im Hintergrund gesperrt war und der Nutzer jetzt aus dem Vordergrund startet.
                if (ensureForeground(startId)) startOrResume(startId, restartEngine = true)
            }
            ACTION_RESUME -> {
                settings.setPausedUntil(0)
                if (ensureForeground(startId)) startOrResume(startId)
            }
            ACTION_AUTO_RESUME -> autoResume(startId)
            ACTION_PAUSE -> pause(AppSettings.PAUSED_INDEFINITELY, startId)
            ACTION_PAUSE_15 -> pause(System.currentTimeMillis() + PAUSE_SHORT_MS, startId)
            ACTION_STOP -> stopRecording(startId)
            ACTION_RESTORE -> restore(startId)
            else -> Log.w(TAG, "Unbekannte Aktion $action")
        }
    }

    /** Neustart durch das System oder nach Geräte-Neustart (API 29): Zustand aus den Einstellungen wiederherstellen. */
    private suspend fun restore(startId: Int?) {
        val s = container.settings.current()
        if (!s.recordingDesired) {
            finishService(startId)
            return
        }
        if (!ensureForeground(startId)) return
        val now = System.currentTimeMillis()
        if (s.isPausedAt(now)) {
            enterPausedState(s.pausedUntilEpochMs)
        } else {
            if (s.pausedUntilEpochMs != 0L) container.settings.setPausedUntil(0)
            startOrResume(startId)
        }
    }

    private suspend fun startOrResume(startId: Int?, restartEngine: Boolean = false) {
        cancelAutoResume()
        if (!hasMicPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO fehlt – Aufnahme nicht möglich")
            RecorderNotifications.showResumePrompt(this)
            finishService(startId)
            return
        }
        statusHolder.update { it.copy(running = true, pausedUntil = null, stopping = false) }
        acquireWakeLock()
        when {
            engineStarted && enginePaused -> {
                engine.resume()
                enginePaused = false
            }
            !engineStarted || restartEngine -> {
                val config = container.settings.current().toEngineConfig(container.audioRoot)
                engine.start(config)
                configSent = config
                engineStarted = true
                enginePaused = false
            }
            else -> Unit // läuft bereits
        }
    }

    private suspend fun pause(until: Long, startId: Int?) {
        container.settings.setPausedUntil(until)
        if (!inForeground) {
            // Es läuft keine Aufnahme in diesem Prozess – nur den Wunsch speichern.
            finishService(startId)
            return
        }
        if (engineStarted && !enginePaused) {
            engine.pause()
            enginePaused = true
        }
        enterPausedState(until)
    }

    private fun enterPausedState(until: Long) {
        releaseWakeLock()
        statusHolder.update { it.copy(running = true, pausedUntil = until, stopping = false) }
        if (until == AppSettings.PAUSED_INDEFINITELY) cancelAutoResume() else scheduleAutoResume(until)
    }

    private suspend fun autoResume(startId: Int?) {
        val until = container.settings.current().pausedUntilEpochMs
        // Inzwischen manuell fortgesetzt, gestoppt oder unbegrenzt pausiert → nichts tun.
        if (until == 0L || until == AppSettings.PAUSED_INDEFINITELY) return
        val now = System.currentTimeMillis()
        if (now < until - 1_000) {
            scheduleAutoResume(until)
            return
        }
        container.settings.setPausedUntil(0)
        if (ensureForeground(startId)) startOrResume(startId)
    }

    private suspend fun stopRecording(startId: Int?) {
        container.settings.setRecordingDesired(false)
        cancelAutoResume()
        statusHolder.update { it.copy(stopping = true) }
        if (engineStarted) {
            engine.stop()
            engineStarted = false
            enginePaused = false
            configSent = null
            // Erst beenden, wenn die Engine ihre Dateien abgeschlossen hat (höchstens 5 s warten).
            withTimeoutOrNull(STOP_TIMEOUT_MS) { engine.state.first { it.status == EngineStatus.Idle } }
        }
        releaseWakeLock()
        finishService(startId)
    }

    // ---------------------------------------------------------------- Vordergrund

    /** Geht in den Vordergrund, falls noch nicht geschehen. Bei Verbot: Hinweis-Benachrichtigung und Ende. */
    private fun ensureForeground(startId: Int?): Boolean {
        if (enterForeground()) return true
        onForegroundDenied(startId)
        return false
    }

    private fun enterForeground(): Boolean {
        if (inForeground) return true
        val notification = RecorderNotifications.buildOngoing(this, lastModel)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0 // Android 10 kennt den Typ „microphone“ noch nicht.
        }
        return try {
            ServiceCompat.startForeground(this, RecorderNotifications.ID_RECORDING, notification, type)
            inForeground = true
            statusHolder.update { it.copy(running = true) }
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): Start aus dem Hintergrund.
            Log.w(TAG, "Vordergrundstart nicht erlaubt", e)
            false
        } catch (e: SecurityException) {
            // Android 14+: Mikrofon-Berechtigung gilt im Hintergrund nicht („while-in-use“) oder fehlt.
            Log.w(TAG, "Vordergrundstart verweigert", e)
            false
        }
    }

    private fun onForegroundDenied(startId: Int?) {
        lifecycleScope.launch {
            if (container.settings.current().recordingDesired) RecorderNotifications.showResumePrompt(this@RecorderService)
        }
        finishService(startId)
    }

    private fun finishService(startId: Int?) {
        cancelAutoResume()
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        statusHolder.update { ServiceStatus() }
        // stopSelf(startId) beendet nicht, falls inzwischen ein neuerer Befehl (z. B. START) eingetroffen ist.
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    // ---------------------------------------------------------------- Pause 15 min

    /**
     * Inexakter Alarm (ohne SCHEDULE_EXACT_ALARM) weckt das Gerät auch im Doze; der Handler ist der Normalfall,
     * solange die CPU wach ist. Beide landen in [autoResume], das idempotent ist.
     */
    private fun scheduleAutoResume(until: Long) {
        handler.removeCallbacks(autoResumeRunnable)
        handler.postDelayed(autoResumeRunnable, (until - System.currentTimeMillis()).coerceAtLeast(0))
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, autoResumePendingIntent())
    }

    private fun cancelAutoResume() {
        handler.removeCallbacks(autoResumeRunnable)
        getSystemService(AlarmManager::class.java)?.cancel(autoResumePendingIntent())
    }

    private fun autoResumePendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        RC_AUTO_RESUME,
        intent(this, ACTION_AUTO_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---------------------------------------------------------------- Beobachter

    /** Einstellungsänderungen an die laufende Engine weitergeben (auch während der Pause). */
    private fun observeSettings() {
        lifecycleScope.launch {
            container.settings.engineConfig(container.audioRoot).collect { config ->
                if (engineStarted && config != configSent) {
                    engine.updateConfig(config)
                    configSent = config
                }
            }
        }
    }

    /** Benachrichtigung höchstens ~1×/s und nur bei Änderung des (minutengenauen) Inhalts aktualisieren. */
    private fun observeNotification() {
        val ticker = flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(TICK_MS)
            }
        }
        lifecycleScope.launch {
            combine(engine.state, statusHolder.status, ticker) { e, s, now -> NotificationModel.from(e, s, now) }
                .distinctUntilChanged()
                .conflate()
                .collect { model ->
                    lastModel = model
                    if (inForeground && model.status != DisplayStatus.Off && model.status != DisplayStatus.Stopping) {
                        RecorderNotifications.updateOngoing(
                            this@RecorderService,
                            RecorderNotifications.buildOngoing(this@RecorderService, model),
                        )
                        delay(NOTIFY_MIN_INTERVAL_MS)
                    }
                }
        }
    }

    private fun observeTile() {
        lifecycleScope.launch {
            combine(engine.state, statusHolder.status) { e, s -> displayStatus(e, s) }
                .distinctUntilChanged()
                .collect { RecorderTileService.requestUpdate(this@RecorderService) }
        }
    }

    /**
     * Hält ein Fehler (z. B. „Mikrofon im Hintergrund gesperrt“ unter Android 11–13 oder fehlende Berechtigung)
     * länger an, bitten wir per Benachrichtigung um ein Antippen: Der Start aus dem Vordergrund behebt das meist.
     */
    private fun observePersistentError() {
        lifecycleScope.launch {
            engine.state
                .map { it.status == EngineStatus.Error }
                .distinctUntilChanged()
                .collectLatest { error ->
                    if (error) {
                        delay(ERROR_PROMPT_DELAY_MS)
                        if (inForeground) RecorderNotifications.showResumePrompt(this@RecorderService)
                    } else {
                        RecorderNotifications.cancelResumePrompt(this@RecorderService)
                    }
                }
        }
    }

    /** Aufbewahrung: beim Start und danach höchstens einmal pro 24 h ältere Aufnahmen löschen. */
    private fun runRetentionLoop() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    runRetentionIfDue()
                } catch (e: Exception) {
                    Log.e(TAG, "Aufbewahrung fehlgeschlagen", e)
                }
                delay(RETENTION_CHECK_MS)
            }
        }
    }

    private suspend fun runRetentionIfDue() {
        val s = container.settings.current()
        val now = System.currentTimeMillis()
        val last = s.lastRetentionRunEpochMs
        if (last in 1..now && now - last < RETENTION_INTERVAL_MS) return
        if (s.audioRetentionDays != AppSettings.RETENTION_UNLIMITED) {
            val deleted = withContext(Dispatchers.IO) { container.recordings.deleteOlderThan(s.audioRetentionDays) }
            Log.i(TAG, "Aufbewahrung: $deleted Datei(en) gelöscht")
        }
        container.settings.setLastRetentionRun(now)
    }

    // ---------------------------------------------------------------- Wake-Lock

    // Bewusst ohne Timeout: Die Aufnahme läuft beliebig lange; freigegeben wird bei Pause/Stopp/onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "open2ndbrain:recorder")
            ?.apply { setReferenceCounted(false) }
            ?.also { wakeLock = it }
        if (lock != null && !lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    companion object {
        private const val TAG = "RecorderService"

        const val ACTION_START = "de.open2ndbrain.action.START"
        const val ACTION_PAUSE = "de.open2ndbrain.action.PAUSE"
        const val ACTION_PAUSE_15 = "de.open2ndbrain.action.PAUSE_15"
        const val ACTION_RESUME = "de.open2ndbrain.action.RESUME"
        const val ACTION_STOP = "de.open2ndbrain.action.STOP"
        internal const val ACTION_AUTO_RESUME = "de.open2ndbrain.action.AUTO_RESUME"
        internal const val ACTION_RESTORE = "de.open2ndbrain.action.RESTORE"

        private const val EXTRA_FOREGROUND_START = "de.open2ndbrain.extra.FOREGROUND_START"
        private const val RC_AUTO_RESUME = 20

        const val PAUSE_SHORT_MS = 15 * 60_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val ERROR_PROMPT_DELAY_MS = 10_000L
        private const val TICK_MS = 15_000L
        private const val NOTIFY_MIN_INTERVAL_MS = 1_000L
        private const val RETENTION_CHECK_MS = 60 * 60_000L
        private const val RETENTION_INTERVAL_MS = 24 * 60 * 60_000L

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecorderService::class.java).setAction(action)

        fun hasMicPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /**
         * Startet die Aufnahme als Mikrofon-Vordergrunddienst. **Nur aufrufen, solange eine Activity sichtbar ist**
         * (sonst verbietet Android 12+/14+ den Start). Rückgabe false: Berechtigung fehlt oder Start verboten.
         */
        fun start(context: Context): Boolean = startForegroundWith(context, ACTION_START)

        /** Wiederherstellen nach Geräte-Neustart – nur unter Android 10 aus dem BootReceiver erlaubt. */
        internal fun restoreFromBoot(context: Context): Boolean = startForegroundWith(context, ACTION_RESTORE)

        private fun startForegroundWith(context: Context, action: String): Boolean {
            if (!hasMicPermission(context)) return false
            val intent = intent(context, action).putExtra(EXTRA_FOREGROUND_START, true)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "startForegroundService verweigert", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService verweigert", e)
                false
            }
        }

        /** Befehl an den laufenden Dienst (Pause, Fortsetzen, Stopp). */
        fun send(context: Context, action: String): Boolean = try {
            context.startService(intent(context, action))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Befehl $action konnte nicht zugestellt werden", e)
            false
        }
    }
}
