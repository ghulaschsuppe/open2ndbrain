package de.open2ndbrain.transcription

import android.content.Context
import de.open2ndbrain.core.RecordingFile
import de.open2ndbrain.core.SpeakerRepository
import de.open2ndbrain.core.Transcriber
import de.open2ndbrain.core.TranscriberConfig
import de.open2ndbrain.core.Transcript

/** PLATZHALTER – wird vom ML-Agenten implementiert. */
class SherpaTranscriber(
    @Suppress("unused") private val context: Context,
    @Suppress("unused") private val config: TranscriberConfig,
    @Suppress("unused") private val speakers: SpeakerRepository,
) : Transcriber {
    override fun transcribe(recording: RecordingFile, onProgress: (Float) -> Unit): Transcript =
        throw UnsupportedOperationException("noch nicht implementiert")

    override fun close() = Unit
}
