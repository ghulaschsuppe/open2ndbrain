package de.open2ndbrain.transcription

import android.speech.SpeechRecognizer
import de.open2ndbrain.core.AsrEngine
import de.open2ndbrain.core.ModelCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRecognizerSupportTest {
    @Test
    fun languageTagsMatch() {
        assertTrue(SystemRecognizerSupport.matchesLanguage("de-DE", "de-DE"))
        assertTrue(SystemRecognizerSupport.matchesLanguage("de_DE", "de-DE"))
        assertTrue(SystemRecognizerSupport.matchesLanguage("de", "de-DE"))
        assertTrue(SystemRecognizerSupport.matchesLanguage("de-DE", "de"))
        assertFalse(SystemRecognizerSupport.matchesLanguage("en-US", "de-DE"))
        assertFalse(SystemRecognizerSupport.matchesLanguage("de-AT", "de-DE"))
    }

    @Test
    fun evaluateLanguagePackState() {
        val installed = SystemRecognizerSupport.evaluate(listOf("en-US", "de-DE"), emptyList(), listOf("de-DE"), "de-DE")
        assertTrue(installed.ready)
        val pending = SystemRecognizerSupport.evaluate(listOf("en-US"), listOf("de-DE"), listOf("de-DE"), "de-DE")
        assertFalse(pending.ready)
        assertTrue(pending.downloadPending)
        assertFalse(pending.downloadPossible)
        val possible = SystemRecognizerSupport.evaluate(emptyList(), emptyList(), listOf("en-US", "de-DE"), "de-DE")
        assertTrue(possible.downloadPossible)
        assertEquals("Sprachpaket de-DE nicht installiert", possible.reason)
        val unsupported = SystemRecognizerSupport.evaluate(emptyList(), emptyList(), listOf("en-US"), "de-DE")
        assertFalse(unsupported.downloadPossible)
        assertTrue(unsupported.reason!!.contains("nicht unterstützt"))
    }

    @Test
    fun errorsAreClassified() {
        assertEquals(SystemRecognizerSupport.ErrorClass.NoSpeech, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(SystemRecognizerSupport.ErrorClass.NoSpeech, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SystemRecognizerSupport.ErrorClass.ModelMissing, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(SystemRecognizerSupport.ErrorClass.ModelMissing, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(SystemRecognizerSupport.ErrorClass.Retry, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SystemRecognizerSupport.ErrorClass.Retry, SystemRecognizerSupport.classifyError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
    }

    @Test
    fun pcm16LittleEndianWithClipping() {
        val b = SystemRecognizerSupport.toPcm16(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))
        assertArrayEquals(
            byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x7F, 0xFF.toByte(), 0x3F),
            b,
        )
    }

    @Test
    fun catalogFilesResolveByRole() {
        // SherpaRecognizer sucht Dateien über ihre Rolle im Namen – je Rolle genau eine Datei
        for (m in ModelCatalog.asrModels) {
            val roles = when (m.asrEngine) {
                AsrEngine.NemoTransducer -> listOf("encoder", "decoder", "joiner", "tokens")
                AsrEngine.NemoCtc -> listOf("model", "tokens")
                AsrEngine.NemoCanary, AsrEngine.Whisper -> listOf("encoder", "decoder", "tokens")
                AsrEngine.AndroidSystem -> emptyList()
                null -> error("${m.id} ohne Engine")
            }
            roles.forEach { part -> assertEquals("${m.id}/$part", 1, m.files.count { it.name.contains(part) }) }
        }
        assertTrue(ModelCatalog.ANDROID_SYSTEM.files.isEmpty())
        assertFalse(AsrEngine.AndroidSystem.hasTokenTimestamps)
    }
}
