package de.open2ndbrain.ui.recordings

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val file: File? = null,
    val prepared: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: Boolean = false,
)

/** Einfacher Player für Aufnahmedateien (OGG/Opus wird von MediaPlayer ab Android 10 unterstützt). Main-Thread. */
class RecordingPlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private var pendingSeekMs: Long? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Spielt [file] ab; gleiche Datei ohne [startMs] → Pause/Weiter umschalten, mit [startMs] → dorthin springen. */
    fun play(file: File, startMs: Long? = null) {
        if (_state.value.file == file && player != null) {
            if (startMs == null) {
                toggle()
            } else if (_state.value.prepared) {
                seekTo(startMs)
                if (player?.isPlaying == false) toggle()
            } else {
                pendingSeekMs = startMs
            }
            return
        }
        pendingSeekMs = startMs
        release()
        _state.value = PlayerState(file = file)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.path)
            mp.setOnPreparedListener {
                _state.update { s -> s.copy(prepared = true, durationMs = it.duration.toLong().coerceAtLeast(0)) }
                pendingSeekMs?.let { ms ->
                    it.seekTo(ms.toInt())
                    _state.update { s -> s.copy(positionMs = ms) }
                }
                pendingSeekMs = null
                it.start()
                onPlaying(true)
            }
            mp.setOnCompletionListener {
                onPlaying(false)
                _state.update { s -> s.copy(positionMs = s.durationMs) }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer-Fehler $what/$extra")
                onPlaying(false)
                _state.update { it.copy(error = true) }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Datei nicht abspielbar: ${file.name}", e)
            release()
            _state.value = PlayerState(file = file, error = true)
        }
    }

    fun toggle() {
        val mp = player ?: return
        if (!_state.value.prepared) return
        if (mp.isPlaying) {
            mp.pause()
            onPlaying(false)
        } else {
            if (_state.value.positionMs >= _state.value.durationMs && _state.value.durationMs > 0) mp.seekTo(0)
            mp.start()
            onPlaying(true)
        }
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        if (!_state.value.prepared) return
        mp.seekTo(ms.toInt())
        _state.update { it.copy(positionMs = ms) }
    }

    fun close() {
        release()
        _state.value = PlayerState()
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        player?.let {
            try {
                it.reset()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }

    private fun onPlaying(playing: Boolean) {
        _state.update { it.copy(playing = playing) }
        progressJob?.cancel()
        if (playing) {
            progressJob = scope.launch {
                while (isActive) {
                    player?.let { mp -> _state.update { it.copy(positionMs = mp.currentPosition.toLong()) } }
                    delay(250)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RecordingPlayer"
    }
}
