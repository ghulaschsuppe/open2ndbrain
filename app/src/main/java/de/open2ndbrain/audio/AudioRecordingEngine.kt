package de.open2ndbrain.audio

import android.content.Context
import de.open2ndbrain.core.EngineConfig
import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.RecordingEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** PLATZHALTER – wird vom Audio-Agenten implementiert. */
class AudioRecordingEngine(@Suppress("unused") private val context: Context) : RecordingEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    override fun start(config: EngineConfig) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun updateConfig(config: EngineConfig) = Unit
}
