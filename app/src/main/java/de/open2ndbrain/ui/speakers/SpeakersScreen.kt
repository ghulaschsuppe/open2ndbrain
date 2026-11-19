package de.open2ndbrain.ui.speakers

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.core.SpeakerProfile
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.EmptyState
import de.open2ndbrain.ui.components.Illustration
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.ScreenTitle
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.components.WidthAware
import de.open2ndbrain.ui.recordings.MeBadge
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
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
            .background(speakerColor(profile)),
    )
}

/** Sprecher-Tab (oder Unterseite mit [onBack]): Avatar-Karten mit Sprechzeit-Leiste, „Ich“-Abzeichen. */
@Composable
fun SpeakersScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, vm: SpeakersViewModel = viewModel()) {
    val list by vm.list.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SpeakerProfile?>(null) }
    SpeakersContent(list, onBack = onBack, onSelect = { selected = it }, modifier = modifier)
    selected?.let { p -> SpeakerDialog(p, list.orEmpty(), vm, onDismiss = { selected = null }) }
}

/** Zustandslose Sprecherliste (auch für Previews/Screenshots). */
@Composable
fun SpeakersContent(
    list: List<SpeakerProfile>?,
    onSelect: (SpeakerProfile) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    initiallyShowRare: Boolean = false,
) {
    var showRare by rememberSaveable { mutableStateOf(initiallyShowRare) }
    val (main, rare) = remember(list) { SpeakerDirectory.splitForDisplay(list.orEmpty()) }
    val maxSpeech = remember(list) { list.orEmpty().maxOfOrNull { it.totalSpeechMs }?.coerceAtLeast(1) ?: 1 }

    WidthAware(modifier.fillMaxSize()) { side ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, bottom = Spacing.lg),
        ) {
            item(key = "title") {
                ScreenTitle(
                    stringResource(R.string.speakers_title),
                    subtitle = list?.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.speakers_count, it.size, it.size) },
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    }
                }
            }
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Illustration.Speakers,
                        title = stringResource(R.string.speakers_empty_title),
                        text = stringResource(R.string.speakers_empty),
                    )
                }
            }
            items(main, key = { it.id }) { p ->
                SpeakerCard(p, maxSpeech, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs)) { onSelect(p) }
            }
            if (rare.isNotEmpty()) {
                item(key = "rare-header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { showRare = !showRare }
                            .padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(stringResource(R.string.speakers_rare, rare.size), modifier = Modifier.weight(1f))
                        Icon(
                            if (showRare) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(if (showRare) R.string.collapse else R.string.expand),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showRare) {
                    items(rare, key = { it.id }) { p ->
                        SpeakerCard(p, maxSpeech, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs), compact = true) { onSelect(p) }
                    }
                }
            }
            if (!list.isNullOrEmpty()) {
                item(key = "hint") {
                    Row(Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.md), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.speakers_cleanup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerCard(p: SpeakerProfile, maxSpeechMs: Long, modifier: Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val color = speakerColor(p)
    O2bCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpeakerAvatar(p, size = if (compact) 40.dp else 48.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        speakerName(p),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (p.isMe) {
                        Spacer(Modifier.width(Spacing.xs))
                        MeBadge()
                    }
                }
                Text(
                    stringResource(R.string.speaker_last_seen, Formatters.dateTime(p.lastSeenEpochMs)),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fraction = (p.totalSpeechMs.toFloat() / maxSpeechMs).coerceIn(0.03f, 1f)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(color.copy(alpha = 0.85f)),
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        Formatters.duration(p.totalSpeechMs),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerDialog(p: SpeakerProfile, all: List<SpeakerProfile>, vm: SpeakersViewModel, onDismiss: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 Menü, 1 Umbenennen, 2 Zusammenführen, 3 Löschen
    var name by remember { mutableStateOf(p.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SpeakerAvatar(p, size = 56.dp) },
        title = { Text(speakerName(p)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                when (mode) {
                    1 -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        label = { Text(stringResource(R.string.speaker_name_label)) },
                    )
                    2 -> {
                        Text(stringResource(R.string.speaker_merge_hint, speakerName(p)), style = MaterialTheme.typography.bodyMedium)
                        all.filter { it.id != p.id }.forEach { other ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(role = Role.Button) {
                                        vm.merge(p.id, other.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SpeakerAvatar(other, size = 32.dp)
                                Spacer(Modifier.width(Spacing.sm))
                                Text(speakerName(other), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    3 -> Text(stringResource(R.string.speaker_delete_text))
                    else -> {
                        Text(
                            stringResource(R.string.speaker_details, Formatters.duration(p.totalSpeechMs), Formatters.dateTime(p.lastSeenEpochMs)),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.xs),
                        )
                        MenuEntry(Icons.Filled.Edit, stringResource(R.string.speaker_rename)) { mode = 1 }
                        if (!p.isMe) {
                            MenuEntry(Icons.Filled.Person, stringResource(R.string.speaker_set_me)) {
                                vm.setMe(p.id)
                                onDismiss()
                            }
                        }
                        if (all.size > 1) MenuEntry(Icons.AutoMirrored.Filled.MergeType, stringResource(R.string.speaker_merge)) { mode = 2 }
                        MenuEntry(Icons.Filled.Delete, stringResource(R.string.delete), danger = true) { mode = 3 }
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
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MenuEntry(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Spacing.md))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
