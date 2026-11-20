package de.open2ndbrain.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.open2ndbrain.core.AsrEngine
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelManager
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Ergebnis eines Datei-Imports. */
data class ImportResult(val displayName: String, val outcome: Outcome, val modelName: String? = null) {
    enum class Outcome { Imported, AlreadyPresent, WrongChecksum, Unknown, Error }
}

sealed interface ImportState {
    data class Running(val displayName: String, val index: Int, val count: Int) : ImportState
    data class Done(val results: List<ImportResult>) : ImportState
}

/**
 * [ModelManager] auf Dateisystem-Basis. Downloads laufen in [ModelDownloadWorker] (WorkManager), der Zustand
 * wird beim Start aus dem Dateisystem rekonstruiert und während eines Downloads vom Worker aktualisiert.
 */
/** Zustand der experimentellen Systemerkennung. */
enum class SystemAsrStatus { Unknown, Unavailable, LanguagePackMissing, Ready }

class FileModelManager(
    context: Context,
    root: File,
    private val onInstalled: (String) -> Unit = {},
) : ModelManager {
    private val appContext = context.applicationContext
    val store = ModelStore(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workManager by lazy { WorkManager.getInstance(appContext) }

    /**
     * Systemerkennung (ohne Dateien): „installiert“ heißt verfügbar + deutsches Sprachpaket vorhanden.
     * Wird vom Container mit `SystemRecognizerSupport` des ML-Agenten belegt; bis dahin „nicht verfügbar“.
     */
    interface SystemAsr {
        suspend fun status(): SystemAsrStatus
        /** Deutsches Sprachpaket anfordern (das System zeigt den Fortschritt). */
        suspend fun requestDownload()
    }

    /**
     * Grobe Standardprüfung, bis `SystemRecognizerSupport` (ML) eingehängt ist: vor Android 13 oder ohne
     * On-Device-Erkennung „nicht verfügbar“, sonst „unbekannt“ (Sprachpaket nicht geprüft → nicht nutzbar).
     */
    @Volatile
    var systemAsr: SystemAsr = object : SystemAsr {
        override suspend fun status(): SystemAsrStatus = when {
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU -> SystemAsrStatus.Unavailable
            runCatching { android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false) ->
                SystemAsrStatus.Unknown
            else -> SystemAsrStatus.Unavailable
        }

        override suspend fun requestDownload() = Unit
    }

    private val _systemStatus = MutableStateFlow(SystemAsrStatus.Unknown)
    /** Zustand der Systemerkennung für die Anzeige; [refreshSystemAsr] aktualisiert ihn. */
    val systemStatus: StateFlow<SystemAsrStatus> = _systemStatus.asStateFlow()

    /** Systemerkennung neu prüfen (z. B. beim Öffnen der Modellseite, nach dem Sprachpaket-Download). */
    fun refreshSystemAsr() {
        scope.launch {
            _systemStatus.value = runCatching { systemAsr.status() }.getOrDefault(SystemAsrStatus.Unavailable)
            refresh(ModelCatalog.ANDROID_SYSTEM.id)
        }
    }

    private val fsStates = MutableStateFlow(readFileSystem())
    /** Laufzeit-Zustände (Download/Prüfung/Fehler) überlagern den Dateisystem-Zustand. */
    private val runtime = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    override val states: StateFlow<Map<String, ModelState>> = combine(fsStates, runtime) { fs, rt ->
        ModelCatalog.all.associate { spec ->
            val f = fs[spec.id] ?: ModelState.NotInstalled
            spec.id to if (f is ModelState.Installed) f else rt[spec.id] ?: f
        }
    }.stateIn(scope, SharingStarted.Eagerly, fsStates.value)

    private val _presentBytes = MutableStateFlow(ModelCatalog.all.associate { it.id to store.bytesPresent(it) })
    /** Bereits geladene Bytes je Modell (auch nach abgebrochenem Download, für „Fortsetzen“). */
    val presentBytes: StateFlow<Map<String, Long>> = _presentBytes.asStateFlow()

    private val _waitingForNetwork = MutableStateFlow<Set<String>>(emptySet())
    /** Download eingeplant, wartet aber auf (WLAN-)Netz. */
    val waitingForNetwork: StateFlow<Set<String>> = _waitingForNetwork.asStateFlow()

    private val _importState = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _importState.asStateFlow()

    init {
        ModelCatalog.all.forEach { spec -> scope.launch { observeWork(spec) } }
    }

    override fun download(id: String) = download(id, unmeteredOnly = true)

    /** Startet bzw. setzt den Download fort. [unmeteredOnly]: nur über WLAN/unbegrenzte Netze. */
    fun download(id: String, unmeteredOnly: Boolean) {
        val spec = ModelCatalog.byId(id) ?: return
        if (isSystemModel(spec)) {
            // Keine Dateien: das System lädt das Sprachpaket selbst; danach neu prüfen.
            scope.launch {
                runCatching { systemAsr.requestDownload() }
                refreshSystemAsr()
            }
            return
        }
        if (store.isInstalled(spec)) return
        runtime.update { it + (id to ModelState.Downloading(store.bytesPresent(spec), spec.sizeBytes)) }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // KEEP: ein laufender Download wird nicht ersetzt (zwei Schreiber auf dieselbe Teildatei vermeiden).
        workManager.enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, request)
    }

    /** Lädt die empfohlene Ausstattung (Standard-Spracherkennung, VAD, Sprechererkennung), soweit sie fehlt. */
    fun downloadAll(unmeteredOnly: Boolean) = AsrSelection.RECOMMENDED.forEach { download(it.id, unmeteredOnly) }

    override fun cancel(id: String) {
        workManager.cancelUniqueWork(uniqueName(id))
        runtime.update { it - id }
        refresh(id)
    }

    override suspend fun delete(id: String) {
        val spec = ModelCatalog.byId(id) ?: return
        workManager.cancelUniqueWork(uniqueName(id))
        delay(300) // laufenden Worker den aktuellen Block beenden lassen
        store.delete(spec)
        runtime.update { it - id }
        refresh(id)
    }

    override fun installedDir(id: String): File? {
        val spec = ModelCatalog.byId(id) ?: return null
        if (isSystemModel(spec)) return if (_systemStatus.value == SystemAsrStatus.Ready) store.dir(spec).apply { mkdirs() } else null
        return if (store.isInstalled(spec)) store.dir(spec) else null
    }

    private fun isSystemModel(spec: ModelSpec) = spec.asrEngine == AsrEngine.AndroidSystem

    /** Datei eines installierten Modells (z. B. VAD- oder Sprechermodell). */
    fun installedFile(spec: ModelSpec, index: Int = 0): File? =
        installedDir(spec.id)?.let { File(it, spec.files[index].name) }

    // ---------------------------------------------------------------- vom Worker

    internal fun reportProgress(id: String, done: Long, total: Long) {
        runtime.update { it + (id to ModelState.Downloading(done, total)) }
        _presentBytes.update { it + (id to done) }
    }

    internal fun reportVerifying(id: String) = runtime.update { it + (id to ModelState.Verifying) }

    internal fun reportFailed(id: String, message: String) = runtime.update { it + (id to ModelState.Failed(message)) }

    internal fun reportInstalled(id: String) {
        runtime.update { it - id }
        refresh(id)
        onInstalled(id)
    }

    fun refresh(id: String? = null) {
        val specs = if (id == null) ModelCatalog.all else listOfNotNull(ModelCatalog.byId(id))
        fsStates.update { cur -> cur + specs.associate { it.id to fsState(it) } }
        _presentBytes.update { cur -> cur + specs.associate { it.id to store.bytesPresent(it) } }
    }

    // ---------------------------------------------------------------- Import (SAF)

    /** Importiert Modelldateien (z. B. vom PC kopiert). Zuordnung über SHA-256, sonst Dateiname. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val results = ArrayList<ImportResult>()
            val touched = HashSet<ModelSpec>()
            uris.forEachIndexed { i, uri ->
                val name = displayName(uri) ?: uri.lastPathSegment ?: "?"
                _importState.value = ImportState.Running(name, i + 1, uris.size)
                results += try {
                    importOne(uri, name, touched)
                } catch (e: Exception) {
                    Log.w(TAG, "Import von $name fehlgeschlagen", e)
                    ImportResult(name, ImportResult.Outcome.Error)
                }
            }
            for (spec in touched) {
                if (store.allFilesVerified(spec) && !store.isInstalled(spec)) {
                    runCatching { store.writeMarker(spec) }.onSuccess { reportInstalled(spec.id) }
                }
            }
            refresh()
            _importState.value = ImportState.Done(results)
        }
    }

    fun clearImportState() {
        if (_importState.value is ImportState.Done) _importState.value = null
    }

    private fun importOne(uri: Uri, name: String, touched: MutableSet<ModelSpec>): ImportResult {
        val tmpDir = store.importDir.apply { mkdirs() }
        val tmp = File.createTempFile("import", ".tmp", tmpDir)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Datei nicht lesbar")
            DigestInputStream(input, md).use { din -> tmp.outputStream().use { din.copyTo(it, 1 shl 16) } }
            val sha = DownloadLogic.toHex(md.digest())
            return when (val m = DownloadLogic.matchImport(name, sha, ModelCatalog.all)) {
                is DownloadLogic.ImportMatch.Matched -> {
                    if (store.isVerified(m.spec, m.file)) {
                        ImportResult(name, ImportResult.Outcome.AlreadyPresent, m.spec.displayName)
                    } else {
                        store.install(m.spec, m.file, tmp)
                        touched += m.spec
                        ImportResult(name, ImportResult.Outcome.Imported, m.spec.displayName)
                    }
                }
                is DownloadLogic.ImportMatch.WrongChecksum ->
                    ImportResult(name, ImportResult.Outcome.WrongChecksum, m.spec.displayName)
                DownloadLogic.ImportMatch.Unknown -> ImportResult(name, ImportResult.Outcome.Unknown)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- intern

    private suspend fun observeWork(spec: ModelSpec) {
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(spec.id)).collect { infos ->
            val info = infos.lastOrNull() ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    _waitingForNetwork.update { it + spec.id }
                    runtime.update { rt ->
                        if (rt[spec.id] is ModelState.Downloading) rt
                        else rt + (spec.id to ModelState.Downloading(store.bytesPresent(spec), spec.sizeBytes))
                    }
                }
                WorkInfo.State.RUNNING -> _waitingForNetwork.update { it - spec.id }
                WorkInfo.State.FAILED -> {
                    _waitingForNetwork.update { it - spec.id }
                    val msg = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download fehlgeschlagen"
                    runtime.update { it + (spec.id to ModelState.Failed(msg)) }
                    refresh(spec.id)
                }
                WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> {
                    _waitingForNetwork.update { it - spec.id }
                    runtime.update { it - spec.id }
                    refresh(spec.id)
                }
            }
        }
    }

    private fun readFileSystem(): Map<String, ModelState> = ModelCatalog.all.associate { it.id to fsState(it) }

    private fun fsState(spec: ModelSpec): ModelState = when {
        isSystemModel(spec) -> installedDir(spec.id)?.let { ModelState.Installed(it) } ?: ModelState.NotInstalled
        store.isInstalled(spec) -> ModelState.Installed(store.dir(spec))
        else -> ModelState.NotInstalled
    }

    companion object {
        private const val TAG = "ModelDownload"
        fun uniqueName(id: String) = "model-download-$id"
    }
}
