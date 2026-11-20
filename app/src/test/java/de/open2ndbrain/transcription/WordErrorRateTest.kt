package de.open2ndbrain.transcription

import de.open2ndbrain.transcription.WordErrorRate.ErrorType
import org.junit.Assert.assertEquals
import org.junit.Test

class WordErrorRateTest {
    @Test
    fun identicalAfterNormalizationIsZero() {
        val r = WordErrorRate.compute("Erst Viertel auf zehn.", "erst viertel auf 10")
        assertEquals(0.0, r.wer, 0.0)
        assertEquals(0.0, WordErrorRate.compute("Es kostet 1.000 Euro, also 3,5 %!", "es kostet tausend euro also 3 komma 5 prozent").wer, 0.0)
        assertEquals(0.0, WordErrorRate.compute("Das Ergebnis: einundzwanzig Prozent.", "das ergebnis 21 %").wer, 0.0)
        assertEquals(listOf("e", "mail", "an", "max"), WordErrorRate.normalize("E-Mail an Max")) // Bindestrich trennt
        assertEquals(listOf("schöne", "grüße", "aus", "köln"), WordErrorRate.normalize("Schöne Grüße aus Köln!"))
    }

    @Test
    fun countsSubstitutionsDeletionsInsertions() {
        val r = WordErrorRate.compute("der Hund läuft schnell nach Hause", "der Hut läuft nach Hause heute")
        assertEquals(1, r.substitutions) // hund → hut
        assertEquals(1, r.deletions) // schnell
        assertEquals(1, r.insertions) // heute
        assertEquals(3.0 / 6, r.wer, 1e-9)
        assertEquals(
            listOf(
                WordErrorRate.WordError(ErrorType.Substitution, "hund", "hut", 1),
                WordErrorRate.WordError(ErrorType.Deletion, "schnell", null, 3),
                WordErrorRate.WordError(ErrorType.Insertion, null, "heute", 6),
            ),
            r.errors,
        )
        assertEquals(listOf(true, false, true, false, true, true), r.referenceMarks.map { it.second })
    }

    @Test
    fun edgeCases() {
        assertEquals(0.0, WordErrorRate.compute("", "").wer, 0.0)
        assertEquals(1.0, WordErrorRate.compute("", "hallo").wer, 0.0)
        assertEquals(1.0, WordErrorRate.compute("hallo welt", "").wer, 0.0)
        assertEquals(2.0, WordErrorRate.compute("ja", "nein nein ja").wer, 0.0)
    }

    @Test
    fun numberWords() {
        assertEquals(21, WordErrorRate.parseNumberWord("einundzwanzig"))
        assertEquals(99, WordErrorRate.parseNumberWord("neunundneunzig"))
        assertEquals(300, WordErrorRate.parseNumberWord("dreihundert"))
        assertEquals(1999, WordErrorRate.parseNumberWord("eintausendneunhundertneunundneunzig"))
        assertEquals(2026, WordErrorRate.parseNumberWord("zweitausendsechsundzwanzig"))
        assertEquals(16, WordErrorRate.parseNumberWord("sechzehn"))
        assertEquals(null, WordErrorRate.parseNumberWord("ein")) // Artikel bleibt Wort
        assertEquals(null, WordErrorRate.parseNumberWord("hundertprozentig"))
        assertEquals(null, WordErrorRate.parseNumberWord("rund"))
    }
}
