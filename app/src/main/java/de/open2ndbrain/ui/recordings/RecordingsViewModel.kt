package de.open2ndbrain.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.open2ndbrain.O2bApp
import de.open2ndbrain.core.RecordingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as O2bApp).container

    /** null = wird noch geladen. */
    val days: StateFlow<List<DayGroup>?> = container.recordings.observeRecordings()
        .map { RecordingGrouping.groupByDay(it, ZoneId.systemDefault()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val player = RecordingPlayer(viewModelScope)

    fun play(recording: RecordingFile) {
        if (!recording.inProgress) player.play(recording.audio)
    }

    fun delete(recording: RecordingFile) {
        if (recording.inProgress) return
        if (player.state.value.file == recording.audio) player.close()
        viewModelScope.launch(Dispatchers.IO) { container.recordings.delete(recording) }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
