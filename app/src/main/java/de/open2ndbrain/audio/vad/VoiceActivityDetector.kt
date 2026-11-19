package de.open2ndbrain.audio.vad

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Sprach-/Stille-Entscheidung auf einem 16-kHz-Mono-PCM16-Strom.
 *
 * Implementierungen puffern intern auf ihre eigene Fenstergröße (Energie-VAD: 20 ms, Silero: 32 ms),
 * daher darf [accept] mit beliebig langen Stücken aufgerufen werden. Nicht threadsicher.
 */
interface VoiceActivityDetector {
    /** Verarbeitet Samples und liefert die aktuelle Entscheidung (true = Sprache). */
    fun accept(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Boolean

    /** Aktuelle Entscheidung ohne neue Daten. */
    val isSpeech: Boolean

    /** Zustand vergessen (z. B. nach Mikrofonwechsel oder Pause). */
    fun reset()
}

/** Pegelhilfen (dBFS bezogen auf Vollaussteuerung 32768). */
object AudioLevels {
    /** Untergrenze für digitale Stille. */
    const val MIN_DB = -100.0

    fun rms(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / length)
    }

    fun dbfs(rms: Double): Double =
        if (rms <= 0.0) MIN_DB else (20.0 * log10(rms / 32768.0)).coerceAtLeast(MIN_DB)

    /** Anzeigepegel 0..1: −60 dBFS → 0, 0 dBFS → 1 (logarithmisch). */
    fun displayLevel(rms: Double): Float = ((dbfs(rms) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Energiebasierte VAD (Phase 1).
 *
 * - Rauschboden = Minimum der Fensterenergien der letzten ~[floorWindowMs] (Minimum-Statistik):
 *   Sprache hat immer kurze Pausen, daher folgt der Boden dem Hintergrundrauschen, auch wenn
 *   lange gesprochen wird. Gleichmäßiges Rauschen (Lüfter) wird nach wenigen Sekunden Boden.
 * - Hysterese: Einschalten ab Boden + [onDb], Ausschalten unter Boden + [offDb].
 * - Mindestsprechdauer [minSpeechMs] über der Einschaltschwelle (filtert Klicks/Türknallen).
 * - Nach Sprachende bleibt die Entscheidung noch [releaseMs] an (kurze Silbenpausen).
 * - Absolute Untergrenze [minSpeechDbfs]: sehr leise Signale gelten nie als Sprache.
 *
 * Bewusst großzügig eingestellt – das Gate darüber speichert lieber zu viel als zu wenig.
 */
class EnergyVad(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val onDb: Double = 9.0,
    private val offDb: Double = 5.0,
    private val minSpeechMs: Int = 100,
    private val releaseMs: Int = 200,
    private val floorWindowMs: Int = 4_000,
    private val minSpeechDbfs: Double = -62.0,
) : VoiceActivityDetector {

    private val frameSamples = sampleRate * frameMs / 1000
    private val buf = ShortArray(frameSamples)
    private var bufLen = 0

    // Minimum-Statistik in Blöcken zu je 500 ms
    private val framesPerBlock = (500 / frameMs).coerceAtLeast(1)
    private val blockMins = DoubleArray((floorWindowMs / 500).coerceAtLeast(1)) { Double.NaN }
    private var blockIndex = 0
    private var framesInBlock = 0
    private var currentBlockMin = Double.POSITIVE_INFINITY

    private val minSpeechFrames = (minSpeechMs / frameMs).coerceAtLeast(1)
    private val releaseFrames = (releaseMs / frameMs).coerceAtLeast(0)
    private var aboveCount = 0
    private var belowCount = 0

    override var isSpeech: Boolean = false
        private set

    /** Aktueller Rauschboden in dBFS (für Tests/Diagnose). */
    var noiseFloorDb: Double = AudioLevels.MIN_DB
        private set

    /** Energie des zuletzt verarbeiteten Fensters in dBFS. */
    var lastFrameDb: Double = AudioLevels.MIN_DB
        private set

    override fun accept(samples: ShortArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val n = minOf(frameSamples - bufLen, end - i)
            System.arraycopy(samples, i, buf, bufLen, n)
            bufLen += n
            i += n
            if (bufLen == frameSamples) {
                processFrame(AudioLevels.dbfs(AudioLevels.rms(buf, 0, frameSamples)))
                bufLen = 0
            }
        }
        return isSpeech
    }

    private fun processFrame(db: Double) {
        lastFrameDb = db
        updateFloor(db)
        val on = maxOf(noiseFloorDb + onDb, minSpeechDbfs)
        val off = maxOf(noiseFloorDb + offDb, minSpeechDbfs - (onDb - offDb))
        when {
            db >= on -> {
                aboveCount++
                belowCount = 0
            }
            db < off -> {
                aboveCount = 0
                belowCount++
            }
            else -> belowCount = 0 // Zwischenbereich: Zustand halten
        }
        if (!isSpeech && aboveCount >= minSpeechFrames) isSpeech = true
        if (isSpeech && belowCount > releaseFrames) isSpeech = false
    }

    private fun updateFloor(db: Double) {
        if (db < currentBlockMin) currentBlockMin = db
        framesInBlock++
        if (framesInBlock >= framesPerBlock) {
            blockMins[blockIndex] = currentBlockMin
            blockIndex = (blockIndex + 1) % blockMins.size
            framesInBlock = 0
            currentBlockMin = Double.POSITIVE_INFINITY
        }
        var m = currentBlockMin
        for (b in blockMins) if (!b.isNaN() && b < m) m = b
        noiseFloorDb = m
    }

    override fun reset() {
        bufLen = 0
        blockMins.fill(Double.NaN)
        blockIndex = 0
        framesInBlock = 0
        currentBlockMin = Double.POSITIVE_INFINITY
        aboveCount = 0
        belowCount = 0
        isSpeech = false
        noiseFloorDb = AudioLevels.MIN_DB
        lastFrameDb = AudioLevels.MIN_DB
    }
}
