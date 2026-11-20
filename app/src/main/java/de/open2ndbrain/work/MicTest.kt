package de.open2ndbrain.work

import android.content.Context
import android.util.Log
import de.open2ndbrain.R
import de.open2ndbrain.core.AppContainer
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.markers.MarkerLogic
import de.open2ndbrain.models.AsrSelection
import de.open2ndbrain.transcription.SherpaTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Ergebnis eines Modells im Mikrofon-Test. */
@Serializable
data class MicTestModelResult(
    val modelId: String,
    /** Wortfehlerrate in Prozent (0 = perfekt). */
    val werPct: Double,
    val processingMs: Long,
    /** Variante mit Rauschfilter vor der Erkennung. */
    val denoised: Boolean = false,
    /** Erkannter Text (zum Nachlesen). */
    val hypothesis: String,
)

@Serializable
data class MicTestResult(
    val atEpochMs: Long,
    val textIndex: Int,
    /** Gespeicherte Audiodauer des Tests. */
    val audioMs: Long,
    /** Dauer des Tests laut Uhr (Start bis Ende). */
    val wallMs: Long,
    /** „Stille verwerfen“ hat vermutlich Teile abgeschnitten (gespeichert deutlich weniger als vorgelesen). */
    val silenceCutHint: Boolean,
    val results: List<MicTestModelResult>,
    val recommendedModelId: String?,
    /** Mikrofon während des Tests (Name), falls bekannt. */
    val input: String? = null,
    /** Situation, die der Nutzer gewählt hat (z. B. „Table“, „Pocket“) – frei, von der UI vergeben. */
    val situation: String? = null,
)

sealed interface MicTestState {
    data object Idle : MicTestState
    data class Recording(val textIndex: Int, val startedAt: Long, val situation: String? = null) : MicTestState
    /** Wartet auf den Abschluss der Testdatei bzw. rechnet Modell [step] von [totalSteps]. */
    data class Processing(val textIndex: Int, val step: Int, val totalSteps: Int, val modelId: String?, val progress: Float = 0f) : MicTestState
    data class Done(val result: MicTestResult) : MicTestState
    data class Failed(val message: String) : MicTestState
}

/** Reine Logik des Mikrofon-Tests (unit-getestet). */
object MicTestScoring {
    private val PUNCT = Regex("""[^\p{L}\p{N}\s]""")
    private val SPACE = Regex("""\s+""")

    fun normalizeWords(text: String): List<String> =
        text.lowercase().replace('-', ' ').replace(PUNCT, " ").replace(SPACE, " ").trim().split(' ').filter { it.isNotEmpty() }

    /**
     * Wortfehlerrate (Ersetzungen + Auslassungen + Einfügungen) / Referenzwörter, in Prozent.
     * Hinweis: Wird durch `WordErrorRate.compute` des ML-Agenten ersetzt, sobald verfügbar (gleiche Normalisierung).
     */
    fun werPct(reference: String, hypothesis: String): Double {
        val r = normalizeWords(reference)
        val h = normalizeWords(hypothesis)
        if (r.isEmpty()) return if (h.isEmpty()) 0.0 else 100.0
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                val sub = prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[h.size] * 100.0 / r.size
    }

    /**
     * Empfehlung: niedrigste Fehlerrate; liegen zwei Modelle innerhalb von [TIE_PCT] Prozentpunkten, gewinnt das
     * schnellere. Varianten mit Rauschfilter zählen mit (die Empfehlung nennt dann das Modell).
     */
    fun recommend(results: List<MicTestModelResult>): MicTestModelResult? {
        val best = results.minByOrNull { it.werPct } ?: return null
        return results.filter { it.werPct - best.werPct <= TIE_PCT }.minByOrNull { it.processingMs }
    }

    /** Wort im erkannten Text, verglichen mit dem Vorlesetext. */
    enum class Mark { Ok, Wrong, Missing, Extra }

    /**
     * Wortweise Ausrichtung (Levenshtein mit Rückverfolgung) für die Anzeige: richtig, falsch (ersetzt), fehlt
     * (nur im Vorlesetext) oder zusätzlich (nur im erkannten Text). Wörter in Schreibweise der Erkennung, fehlende in
     * der des Vorlesetexts.
     */
    fun align(reference: String, hypothesis: String): List<Pair<String, Mark>> {
        val r = normalizeWords(reference)
        val h = normalizeWords(hypothesis)
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val out = ArrayList<Pair<String, Mark>>()
        var i = r.size
        var j = h.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1 -> {
                    out += h[j - 1] to if (r[i - 1] == h[j - 1]) Mark.Ok else Mark.Wrong
                    i--
                    j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> {
                    out += r[i - 1] to Mark.Missing
                    i--
                }
                else -> {
                    out += h[j - 1] to Mark.Extra
                    j--
                }
            }
        }
        return out.asReversed()
    }

    fun silenceCut(discardSilence: Boolean, storedMs: Long, wallMs: Long): Boolean =
        discardSilence && wallMs > 10_000 && storedMs < wallMs * SILENCE_CUT_RATIO

    const val TIE_PCT = 1.0
    const val SILENCE_CUT_RATIO = 0.75
    /** Äußerungen bis zu so viel vor/nach den Testmarken zählen mit (Uhr- und VAD-Unschärfe). */
    const val MARGIN_MS = 2_000L
}

/**
 * Mikrofon-Test: Der Nutzer liest einen vorgegebenen Text in seiner echten Aufnahmesituation vor. Danach wird die
 * Testaufnahme mit jedem installierten Modell transkribiert (eigene Läufe, exklusiv; Kopien im Cache – keine normalen
 * Transkripte, keine Sprecherprofile) und die Wortfehlerrate verglichen.
 */
class MicTest(private val context: Context, private val c: AppContainer) {
    private val _state = MutableStateFlow<MicTestState>(MicTestState.Idle)
    val state: StateFlow<MicTestState> = _state.asStateFlow()

    private val historyFile = File(context.filesDir, "stats/mictests.json")
    private val _history = MutableStateFlow(loadHistory())
    /** Letzte [MAX_HISTORY] Tests, neueste zuerst. */
    val history: StateFlow<List<MicTestResult>> = _history.asStateFlow()

    private var job: Job? = null

    /** Vorlesetexte (Referenz für die Fehlerrate). */
    val texts: List<String> get() = TEXT_IDS.map { context.getString(it) }

    /** Test beginnen (die Aufnahme muss laufen): laufende Datei abschließen, damit der Test sauber beginnt. */
    fun begin(textIndex: Int, situation: String? = null) {
        require(textIndex in TEXT_IDS.indices)
        c.recordingEngine.splitNow("mic_test_start")
        _state.value = MicTestState.Recording(textIndex, System.currentTimeMillis(), situation)
    }

    /** Nächster Vorlesetext (wechselt von Test zu Test). */
    fun nextTextIndex(): Int = _history.value.size % TEXT_IDS.size

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = MicTestState.Idle
    }

    fun reset() {
        if (_state.value !is MicTestState.Processing) _state.value = MicTestState.Idle
    }

    /** Test beenden und auswerten. [withDenoiseVariant]: das beste Modell zusätzlich mit Rauschfilter prüfen. */
    fun finish(withDenoiseVariant: Boolean = false) {
        val rec = _state.value as? MicTestState.Recording ?: return
        val endedAt = System.currentTimeMillis()
        val fileAtEnd = c.recordingEngine.state.value.currentFile
        c.recordingEngine.splitNow("mic_test")
        _state.value = MicTestState.Processing(rec.textIndex, 0, 0, null)
        job = c.appScope.launch {
            _state.value = try {
                MicTestState.Done(evaluate(rec, endedAt, fileAtEnd, withDenoiseVariant))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Mikrofon-Test fehlgeschlagen", e)
                MicTestState.Failed(e.message ?: context.getString(R.string.mic_test_failed))
            }
        }
    }

    private suspend fun evaluate(rec: MicTestState.Recording, endedAt: Long, fileAtEnd: File?, denoiseVariant: Boolean): MicTestResult {
        // 1. Warten, bis die Testdatei abgeschlossen ist (splitNow wirkt asynchron).
        withTimeoutOrNull(FILE_WAIT_MS) {
            while (c.recordingEngine.state.value.currentFile == fileAtEnd && fileAtEnd != null) delay(250)
        }
        delay(1_000)
        val range = (rec.startedAt - MicTestScoring.MARGIN_MS) until (endedAt + MicTestScoring.MARGIN_MS)
        val files = c.recordings.observeRecordings().first()
            .filter { !it.inProgress && it.audio.isFile && MarkerLogic.overlaps(range, MarkerLogic.fileRange(it)) }
        if (files.isEmpty()) throw IllegalStateException(context.getString(R.string.mic_test_no_audio))
        val s = c.settings.current()
        val storedMs = files.sumOf { f ->
            f.sidecar?.segments.orEmpty().filter { it.startEpochMs in range }.sumOf { it.durationMs }.takeIf { it > 0 } ?: f.durationMs
        }
        val wallMs = endedAt - rec.startedAt

        // 2. Kopien im Cache, damit keine normalen Transkripte entstehen.
        val workDir = File(context.cacheDir, "mictest").apply { deleteRecursively(); mkdirs() }
        val copies = withContext(Dispatchers.IO) {
            files.map { f -> f.copy(audio = f.audio.copyTo(File(workDir, f.audio.name), overwrite = true)) }
        }
        val installed = AsrSelection.installedIds(c.modelManager.states.value)
        val models = ModelCatalog.asrModels.filter { it.id in installed }
        val vad = c.modelManager.installedFile(ModelCatalog.SILERO_VAD) ?: throw IllegalStateException(context.getString(R.string.waiting_model_missing))
        val denoiser = c.modelManager.installedFile(ModelCatalog.GTCRN_DENOISER)
        val reference = texts[rec.textIndex]
        val totalSteps = models.size + if (denoiseVariant && denoiser != null) 1 else 0

        try {
            val results = c.transcriptionRunner.exclusive {
                val list = ArrayList<MicTestModelResult>()
                models.forEachIndexed { i, spec ->
                    _state.value = MicTestState.Processing(rec.textIndex, i + 1, totalSteps, spec.id)
                    runModel(spec.id, vad, null, copies, range, reference, s.effectiveThreads()) { p ->
                        _state.value = MicTestState.Processing(rec.textIndex, i + 1, totalSteps, spec.id, p)
                    }?.let { list += it }
                }
                if (denoiseVariant && denoiser != null) {
                    MicTestScoring.recommend(list)?.let { best ->
                        _state.value = MicTestState.Processing(rec.textIndex, totalSteps, totalSteps, best.modelId)
                        runModel(best.modelId, vad, denoiser, copies, range, reference, s.effectiveThreads())?.let { list += it }
                    }
                }
                list
            }
            val result = MicTestResult(
                atEpochMs = System.currentTimeMillis(),
                textIndex = rec.textIndex,
                audioMs = storedMs,
                wallMs = wallMs,
                silenceCutHint = MicTestScoring.silenceCut(s.discardSilence, storedMs, wallMs),
                results = results.sortedBy { it.werPct },
                recommendedModelId = MicTestScoring.recommend(results)?.modelId,
                input = c.recordingEngine.state.value.currentInput?.name,
                situation = rec.situation,
            )
            addHistory(result)
            return result
        } finally {
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }

    private suspend fun runModel(
        modelId: String,
        vad: File,
        denoiser: File?,
        files: List<RecordingFile>,
        range: LongRange,
        reference: String,
        threads: Int,
        onProgress: (Float) -> Unit = {},
    ): MicTestModelResult? {
        val spec = ModelCatalog.byId(modelId) ?: return null
        val dir = c.modelManager.installedDir(modelId) ?: return null
        val config = TranscriberConfig(dir, vad, speakerModelFile = null, numThreads = threads, denoiserModelFile = denoiser, asrModel = spec)
        val transcriber = SherpaTranscriber(context, config, c.speakers)
        return try {
            var processing = 0L
            val text = StringBuilder()
            for ((k, f) in files.withIndex()) {
                val t = runInterruptible(Dispatchers.Default) {
                    transcriber.transcribe(f) { p -> onProgress((k + p) / files.size) }
                }
                processing += t.processingMs
                t.utterances.filter { it.startEpochMs in range }.forEach { text.append(it.text).append(' ') }
            }
            val hyp = text.toString().trim()
            MicTestModelResult(modelId, MicTestScoring.werPct(reference, hyp), processing, denoiser != null, hyp)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Mikrofon-Test mit $modelId fehlgeschlagen", e)
            null
        } finally {
            runCatching { withContext(Dispatchers.Default) { transcriber.close() } }
        }
    }

    private fun addHistory(r: MicTestResult) {
        val list = (listOf(r) + _history.value).take(MAX_HISTORY)
        _history.value = list
        runCatching {
            historyFile.parentFile?.mkdirs()
            val tmp = File(historyFile.path + ".tmp")
            tmp.writeText(JSON.encodeToString(SERIALIZER, list))
            if (!tmp.renameTo(historyFile)) tmp.delete()
        }.onFailure { Log.w(TAG, "Testverlauf nicht speicherbar", it) }
    }

    private fun loadHistory(): List<MicTestResult> = runCatching {
        if (historyFile.isFile) JSON.decodeFromString(SERIALIZER, historyFile.readText()) else emptyList()
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "MicTest"
        const val MAX_HISTORY = 5
        private const val FILE_WAIT_MS = 20_000L
        val TEXT_IDS = listOf(R.string.mic_test_text_1, R.string.mic_test_text_2, R.string.mic_test_text_3)
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(MicTestResult.serializer())
    }
}
