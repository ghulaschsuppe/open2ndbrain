package de.open2ndbrain.transcription

/**
 * Wortfehlerrate (WER) für den Mikrofon-Test: Referenztext gegen erkannten Text.
 *
 * Normalisierung (deutsch): Kleinschreibung, Satzzeichen entfernt (Umlaute/ß bleiben), Bindestriche trennen Wörter,
 * Tausenderpunkte in Zahlen entfernt, Dezimalkomma → „komma“, Zahlwörter 0–20, Zehner, „hundert“, „tausend“ sowie
 * einfache Zusammensetzungen („einundzwanzig“) → Ziffern, „%“ → „prozent“.
 */
object WordErrorRate {

    enum class ErrorType { Substitution, Deletion, Insertion }

    /** Ein Fehler in der Ausrichtung; [reference] fehlt bei Einfügungen, [hypothesis] bei Auslassungen. */
    data class WordError(val type: ErrorType, val reference: String?, val hypothesis: String?, val referenceIndex: Int)

    data class Result(
        val referenceWords: List<String>,
        val hypothesisWords: List<String>,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val errors: List<WordError>,
    ) {
        val errorCount: Int get() = substitutions + deletions + insertions

        /** Anteil 0…(>1 möglich bei vielen Einfügungen); leere Referenz: 0 bei leerer Hypothese, sonst 1. */
        val wer: Double
            get() = if (referenceWords.isEmpty()) (if (hypothesisWords.isEmpty()) 0.0 else 1.0)
            else errorCount.toDouble() / referenceWords.size

        /** Referenzwörter mit Markierung, ob sie richtig erkannt wurden (für die Anzeige). */
        val referenceMarks: List<Pair<String, Boolean>>
            get() {
                val wrong = errors.filter { it.type != ErrorType.Insertion }.mapTo(HashSet()) { it.referenceIndex }
                return referenceWords.mapIndexed { i, w -> w to (i !in wrong) }
            }
    }

    fun compute(reference: String, hypothesis: String): Result {
        val r = normalize(reference)
        val h = normalize(hypothesis)
        // Levenshtein mit Rückverfolgung
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            val sub = d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val errors = ArrayList<WordError>()
        var i = r.size
        var j = h.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] && r[i - 1] == h[j - 1] -> { i--; j-- }
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> {
                    errors += WordError(ErrorType.Substitution, r[i - 1], h[j - 1], i - 1); i--; j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { errors += WordError(ErrorType.Deletion, r[i - 1], null, i - 1); i-- }
                else -> { errors += WordError(ErrorType.Insertion, null, h[j - 1], i); j-- }
            }
        }
        errors.reverse()
        return Result(
            referenceWords = r,
            hypothesisWords = h,
            substitutions = errors.count { it.type == ErrorType.Substitution },
            deletions = errors.count { it.type == ErrorType.Deletion },
            insertions = errors.count { it.type == ErrorType.Insertion },
            errors = errors,
        )
    }

    private val UNITS = mapOf(
        "null" to 0, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7,
        "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11, "zwölf" to 12, "dreizehn" to 13, "vierzehn" to 14,
        "fünfzehn" to 15, "sechzehn" to 16, "siebzehn" to 17, "achtzehn" to 18, "neunzehn" to 19,
    )
    private val TENS = mapOf(
        "zwanzig" to 20, "dreißig" to 30, "vierzig" to 40, "fünfzig" to 50, "sechzig" to 60, "siebzig" to 70,
        "achtzig" to 80, "neunzig" to 90,
    )
    /** In Zusammensetzungen: „einundzwanzig“, „zweihundert“ … */
    private val COMPOUND_UNITS = UNITS + mapOf("ein" to 1, "zwan" to 2, "sech" to 6, "sieb" to 7)

    /** Zahlwort → Zahl (0–9999, einfache Formen), sonst null. */
    internal fun parseNumberWord(w: String): Int? {
        if (w.isEmpty()) return null
        UNITS[w]?.let { return it }
        TENS[w]?.let { return it }
        if (w == "hundert" || w == "einhundert") return 100
        if (w == "tausend" || w == "eintausend") return 1000
        w.indexOf("tausend").takeIf { it > 0 }?.let { k ->
            val head = COMPOUND_UNITS[w.substring(0, k)] ?: parseNumberWord(w.substring(0, k)) ?: return null
            val rest = w.substring(k + 7)
            val tail = if (rest.isEmpty()) 0 else parseNumberWord(rest) ?: return null
            return head * 1000 + tail
        }
        w.indexOf("hundert").takeIf { it > 0 }?.let { k ->
            val head = COMPOUND_UNITS[w.substring(0, k)] ?: return null
            val rest = w.substring(k + 7).removePrefix("und")
            val tail = if (rest.isEmpty()) 0 else parseNumberWord(rest) ?: return null
            return head * 100 + tail
        }
        w.indexOf("und").takeIf { it > 0 }?.let { k ->
            val unit = COMPOUND_UNITS[w.substring(0, k)] ?: return null
            val tens = TENS[w.substring(k + 3)] ?: return null
            return tens + unit
        }
        return null
    }

    internal fun normalize(text: String): List<String> {
        val t = text.lowercase()
            .replace("%", " prozent ")
            .replace(Regex("(\\d)\\.(\\d{3})(?!\\d)"), "$1$2") // 1.000 → 1000
            .replace(Regex("(\\d),(\\d)"), "$1 komma $2")
            .replace(Regex("[-–—/]"), " ")
            .replace(Regex("[^\\p{L}\\p{Nd}' ]"), " ")
            .replace("'", "")
        return t.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { w -> parseNumberWord(w)?.toString() ?: w }
    }
}
