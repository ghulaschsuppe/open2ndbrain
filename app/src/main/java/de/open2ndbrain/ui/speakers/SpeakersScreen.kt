package de.open2ndbrain.ui.speakers

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.ui.Formatters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SpeakersViewModel(application: Application) : AndroidViewModel(application) {
    private val speakers = (application as O2bApp).container.speakers

    /** Alle Profile (null = wird geladen). */
    val list: StateFlow<List<SpeakerProfile>?> = speakers.observeSpeakers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(id: String, name: String?) = viewModelScope.launch { speakers.rename(id, name?.trim()?.ifEmpty { null }) }
    fun setMe(id: String) = viewModelScope.launch { speakers.setMe(id) }
    fun merge(fromId: String, intoId: String) = viewModelScope.launch { speakers.merge(fromId, intoId) }
    fun delete(id: String) = viewModelScope.launch { speakers.delete(id) }
}

/** Anzeigename: eigener Name, sonst „Sprecher 3“; „(Ich)“ wird separat angezeigt. */
@Composable
fun speakerName(profile: SpeakerProfile?): String = when {
    profile == null -> stringResource(R.string.speaker_unknown)
    profile.name != null -> profile.name
    else -> stringResource(R.string.speaker_number, profile.number)
}

@Composable
fun SpeakerDot(profile: SpeakerProfile?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(SpeakerDirectory.color(profile)),
    )
}

@Composable
fun SpeakersScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: SpeakersViewModel = viewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SpeakerProfile?>(null) }
    var showRare by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.speakers_title), style = MaterialTheme.typography.titleLarge)
        }
        val l = list
        if (l != null && l.isEmpty()) {
            Text(
                stringResource(R.string.speakers_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        val (main, rare) = remember(l) { SpeakerDirectory.splitForDisplay(l.orEmpty()) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (!l.isNullOrEmpty()) {
                item(key = "hint") {
                    Text(
                        stringResource(R.string.speakers_cleanup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(main, key = { it.id }) { p -> SpeakerRow(p) { selected = p } }
            if (rare.isNotEmpty()) {
                item(key = "rare-header") {
                    ListItem(
                        modifier = Modifier.clickable { showRare = !showRare },
                        headlineContent = { Text(stringResource(R.string.speakers_rare, rare.size)) },
                        trailingContent = {
                            Icon(
                                if (showRare) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(if (showRare) R.string.collapse else R.string.expand),
                            )
                        },
                    )
                    HorizontalDivider()
                }
                if (showRare) items(rare, key = { it.id }) { p -> SpeakerRow(p) { selected = p } }
            }
        }
    }

    selected?.let { p -> SpeakerDialog(p, list.orEmpty(), vm, onDismiss = { selected = null }) }
}

@Composable
private fun SpeakerRow(p: SpeakerProfile, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { SpeakerDot(p, Modifier.size(20.dp)) },
        headlineContent = {
            Text(if (p.isMe) "${speakerName(p)} · ${stringResource(R.string.speaker_me)}" else speakerName(p))
        },
        supportingContent = {
            Text(stringResource(R.string.speaker_details, Formatters.duration(p.totalSpeechMs), Formatters.dateTime(p.lastSeenEpochMs)))
        },
    )
    HorizontalDivider()
}

@Composable
private fun SpeakerDialog(p: SpeakerProfile, all: List<SpeakerProfile>, vm: SpeakersViewModel, onDismiss: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 Menü, 1 Umbenennen, 2 Zusammenführen, 3 Löschen
    var name by remember { mutableStateOf(p.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(speakerName(p)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (mode) {
                    1 -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.speaker_name_label)) },
                    )
                    2 -> {
                        Text(stringResource(R.string.speaker_merge_hint, speakerName(p)))
                        all.filter { it.id != p.id }.forEach { other ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.merge(p.id, other.id)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SpeakerDot(other)
                                Spacer(Modifier.width(12.dp))
                                Text(speakerName(other))
                            }
                        }
                    }
                    3 -> Text(stringResource(R.string.speaker_delete_text))
                    else -> {
                        MenuEntry(stringResource(R.string.speaker_rename)) { mode = 1 }
                        if (!p.isMe) {
                            MenuEntry(stringResource(R.string.speaker_set_me)) {
                                vm.setMe(p.id)
                                onDismiss()
                            }
                        }
                        if (all.size > 1) MenuEntry(stringResource(R.string.speaker_merge)) { mode = 2 }
                        MenuEntry(stringResource(R.string.delete)) { mode = 3 }
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                1 -> TextButton(onClick = {
                    vm.rename(p.id, name)
                    onDismiss()
                }) { Text(stringResource(R.string.save)) }
                3 -> TextButton(onClick = {
                    vm.delete(p.id)
                    onDismiss()
                }) { Text(stringResource(R.string.delete)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MenuEntry(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
