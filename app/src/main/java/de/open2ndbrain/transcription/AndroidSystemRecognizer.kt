package de.open2ndbrain.transcription

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import de.open2ndbrain.core.ModelLoadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Experimentell: Spracherkennung des Systems, **nur on-device** (`SpeechRecognizer.createOnDeviceSpeechRecognizer`,
 * nie der normale, evtl. cloudbasierte Recognizer). Das Audio einer Einheit (≤ 15 s, PCM16 16 kHz mono) wird über
 * eine Pipe als `EXTRA_AUDIO_SOURCE` übergeben (ab Android 13). Liefert keine Zeitstempel → Einheiten-Modus.
 *
 * Threading: Der SpeechRecognizer wird auf dem Main-Looper erzeugt und bedient; [recognize] wartet blockierend
 * (mit Zeitlimit) auf das Ergebnis – nie vom Main-Thread aufrufen.
 *
 * Annahmen (ohne Gerätetest, aus der Android-Dokumentation):
 * - `EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE` → Ergebnisse in `onSegmentResults`, Ende mit
 *   `onEndOfSegmentedSession`, sobald die Pipe geschlossen ist; ohne Unterstützung kommt `onResults`.
 * - Fehler NO_MATCH/SPEECH_TIMEOUT bedeuten „keine Sprache“ (leerer Text).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AndroidSystemRecognizer(
    context: Context,
    private val language: String = SystemRecognizerSupport.DEFAULT_LANGUAGE,
) : Recognizer, AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Nur auf dem Main-Thread benutzen. */
    private var recognizer: SpeechRecognizer? = null

    init {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
            throw ModelLoadException("Keine On-Device-Spracherkennung des Systems verfügbar")
        }
    }

    override val hasTokenTimestamps: Boolean get() = false

    override fun recognize(samples: FloatArray): List<RawToken> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Nicht vom Main-Thread aufrufen" }
        val pcm = SystemRecognizerSupport.toPcm16(samples)
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            throw IOException("Pipe für die Systemerkennung nicht anlegbar", e)
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        // Pipe-Puffer ist klein → in eigenem Thread schreiben; Schließen signalisiert das Ende des Audios
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { it.write(pcm) }
            } catch (e: IOException) {
                Log.d(TAG, "Pipe vorzeitig geschlossen: ${e.message}")
            }
        }, "O2B-SysAsrPipe").apply { start() }

        val done = CountDownLatch(1)
        val segments = ArrayList<String>()
        var finalText: String? = null
        var errorCode: Int? = null
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) {
                best(segmentResults)?.let { synchronized(segments) { segments += it } }
            }
            override fun onEndOfSegmentedSession() = done.countDown()
            override fun onResults(results: Bundle?) {
                finalText = results?.let(::best)
                done.countDown()
            }
            override fun onError(error: Int) {
                errorCode = error
                done.countDown()
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        var startError: Throwable? = null
        main.post {
            try {
                val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { recognizer = it }
                r.setRecognitionListener(listener)
                r.startListening(intent)
            } catch (t: Throwable) {
                startError = t
                done.countDown()
            }
        }
        try {
            val timeoutMs = samples.size * 1000L / SAMPLE_RATE * 3 + BASE_TIMEOUT_MS
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                cancelOnMain()
                throw IOException("Systemerkennung antwortet nicht (Zeitlimit)")
            }
        } catch (e: InterruptedException) {
            cancelOnMain()
            throw e
        } finally {
            runCatching { readFd.close() }
            writer.join(1_000)
        }
        startError?.let { throw IOException("Systemerkennung nicht startbar: ${it.message}", it) }
        errorCode?.let { code ->
            when (SystemRecognizerSupport.classifyError(code)) {
                SystemRecognizerSupport.ErrorClass.NoSpeech -> return emptyList()
                SystemRecognizerSupport.ErrorClass.ModelMissing ->
                    throw ModelLoadException("Systemerkennung: Sprachpaket $language fehlt (Fehler $code)")
                SystemRecognizerSupport.ErrorClass.Retry -> throw IOException("Systemerkennung: Fehler $code")
            }
        }
        val text = synchronized(segments) { segments.joinToString(" ") }.ifBlank { finalText.orEmpty() }
        if (text.isBlank()) return emptyList()
        return listOf(RawToken(text, 0f, samples.size / SAMPLE_RATE.toFloat()))
    }

    private fun best(b: Bundle): String? =
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun cancelOnMain() {
        main.post { runCatching { recognizer?.cancel() } }
    }

    override fun close() {
        val latch = CountDownLatch(1)
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    companion object {
        private const val TAG = "O2B-ML"
        private const val BASE_TIMEOUT_MS = 30_000L
    }
}

/** Verfügbarkeit der Systemerkennung für App/Design (Anzeige, Sprachpaket laden). */
object SystemRecognizerSupport {
    const val DEFAULT_LANGUAGE = "de-DE"


    data class Status(
        /** Grundsätzlich nutzbar (Android 13+, On-Device-Erkennung vorhanden). */
        val available: Boolean,
        /** Sprachpaket installiert und sofort nutzbar. */
        val languageInstalled: Boolean = false,
        /** Sprachpaket wird gerade geladen. */
        val downloadPending: Boolean = false,
        /** Sprachpaket kann über [requestDownload] geladen werden. */
        val downloadPossible: Boolean = false,
        /** Grund, falls nicht nutzbar (für die Anzeige). */
        val reason: String? = null,
    ) {
        val ready: Boolean get() = available && languageInstalled
    }

    /**
     * Prüft Gerät und Sprachpaket. Erzeugt dafür kurz einen On-Device-Recognizer auf dem Main-Thread.
     * Darf aus jeder Coroutine aufgerufen werden.
     */
    suspend fun check(context: Context, language: String = SystemRecognizerSupport.DEFAULT_LANGUAGE): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Status(false, reason = "Erst ab Android 13")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Status(false, reason = "Keine On-Device-Spracherkennung auf diesem Gerät")
        }
        return withContext(Dispatchers.Main) { checkOnMain(context.applicationContext, language) }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkOnMain(context: Context, language: String): Status = suspendCancellableCoroutine { cont ->
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        cont.invokeOnCancellation { r.destroy() }
        try {
            r.checkRecognitionSupport(languageIntent(language), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    r.destroy()
                    if (cont.isActive) {
                        cont.resume(
                            evaluate(
                                support.installedOnDeviceLanguages,
                                support.pendingOnDeviceLanguages,
                                support.supportedOnDeviceLanguages,
                                language,
                            ),
                        )
                    }
                }

                override fun onError(error: Int) {
                    r.destroy()
                    if (cont.isActive) cont.resume(Status(true, reason = "Sprachpaket nicht prüfbar (Fehler $error)"))
                }
            })
        } catch (t: Throwable) {
            r.destroy()
            if (cont.isActive) cont.resume(Status(false, reason = "Prüfung fehlgeschlagen: ${t.message}"))
        }
    }

    /**
     * Stößt das Laden des Sprachpakets durch das System an (Fortschritt zeigt das System; danach erneut [check]).
     * Rückgabe false, wenn nicht möglich (Android < 13 oder Fehler).
     */
    suspend fun requestDownload(context: Context, language: String = SystemRecognizerSupport.DEFAULT_LANGUAGE): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
        return withContext(Dispatchers.Main) {
            runCatching {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)
                try {
                    r.triggerModelDownload(languageIntent(language))
                } finally {
                    r.destroy()
                }
            }.onFailure { Log.w("O2B-ML", "Sprachpaket-Download nicht angestoßen", it) }.isSuccess
        }
    }

    private fun languageIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }

    // ---------------------------------------------------------------- reine Logik (unit-getestet)

    /** Sprach-Tags vergleichen: „de-DE“ passt zu „de-DE“, „de_DE“ und – als Rückfall – „de“. */
    internal fun matchesLanguage(tag: String, wanted: String): Boolean {
        val a = tag.replace('_', '-').lowercase()
        val b = wanted.replace('_', '-').lowercase()
        return a == b || a == b.substringBefore('-') || a.substringBefore('-') == b
    }

    internal fun evaluate(installed: List<String>, pending: List<String>, supported: List<String>, language: String): Status {
        val inst = installed.any { matchesLanguage(it, language) }
        val pend = pending.any { matchesLanguage(it, language) }
        val sup = supported.any { matchesLanguage(it, language) }
        return Status(
            available = true,
            languageInstalled = inst,
            downloadPending = !inst && pend,
            downloadPossible = !inst && !pend && sup,
            reason = when {
                inst -> null
                pend -> "Sprachpaket wird geladen"
                sup -> "Sprachpaket $language nicht installiert"
                else -> "Sprache $language wird von der Systemerkennung nicht unterstützt"
            },
        )
    }

    enum class ErrorClass { NoSpeech, ModelMissing, Retry }

    @SuppressLint("InlinedApi")
    internal fun classifyError(code: Int): ErrorClass = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorClass.NoSpeech
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        -> ErrorClass.ModelMissing
        else -> ErrorClass.Retry // u. a. BUSY, TOO_MANY_REQUESTS, AUDIO, CLIENT, SERVER(_DISCONNECTED)
    }

    /** Float [-1,1] → PCM16 little-endian (begrenzt). */
    internal fun toPcm16(samples: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in samples) bb.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return bb.array()
    }
}
