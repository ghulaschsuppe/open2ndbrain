package de.open2ndbrain.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelKind
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.ImportResult
import de.open2ndbrain.models.ImportState
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.IconBubble
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular

/** Gesamtgröße aller Modelle (für Hinweise zum Datenvolumen). */
val ALL_MODELS_BYTES: Long get() = de.open2ndbrain.models.AsrSelection.RECOMMENDED_BYTES

/** Sprachmodelle: Liste mit Download/Abbrechen/Löschen, Fortschritt und Import. */
@Composable
fun ModelsBlock(vm: SettingsViewModel) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val present by vm.presentBytes.collectAsStateWithLifecycle()
    val waiting by vm.waitingForNetwork.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    var askDownload by remember { mutableStateOf<ModelSpec?>(null) }
    var askDelete by remember { mutableStateOf<ModelSpec?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.import(uris)
    }

    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.models_volume_hint, Formatters.bytes(ALL_MODELS_BYTES)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ModelCatalog.all.forEach { spec ->
        SettingDivider()
        ModelRow(
            spec = spec,
            state = states[spec.id] ?: ModelState.NotInstalled,
            presentBytes = present[spec.id] ?: 0L,
            waitingForNetwork = spec.id in waiting,
            onDownload = { askDownload = spec },
            onCancel = { vm.cancel(spec.id) },
            onDelete = { askDelete = spec },
        )
    }
    SettingDivider()
    SettingHeader(Icons.Filled.FileOpen, stringResource(R.string.models_import_title), stringResource(R.string.models_import_hint), tint = MaterialTheme.colorScheme.secondary)
    Column(Modifier.padding(start = 72.dp, end = Spacing.md, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        when (val st = importState) {
            is ImportState.Running -> {
                Text(stringResource(R.string.models_import_running, st.displayName, st.index, st.count), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
            }
            is ImportState.Done -> {
                st.results.forEach { r -> Text(importResultText(r), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = vm::clearImport) { Text(stringResource(R.string.close)) }
            }
            null -> OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.models_import_button))
            }
        }
    }

    askDownload?.let { spec ->
        DownloadDialog(
            sizeBytes = spec.sizeBytes - (present[spec.id] ?: 0L),
            onWifi = {
                vm.download(spec.id, unmeteredOnly = true)
                askDownload = null
            },
            onAnyNetwork = {
                vm.download(spec.id, unmeteredOnly = false)
                askDownload = null
            },
            onDismiss = { askDownload = null },
        )
    }
    askDelete?.let { spec ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text(stringResource(R.string.models_delete_title)) },
            text = { Text(stringResource(R.string.models_delete_text, spec.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteModel(spec.id)
                    askDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Eine Modellzeile im App-Stil: Icon nach Art, Name mit Plakette, Größe/Sprachen, Beschreibung, Aktion. */
@Composable
fun ModelRow(
    spec: ModelSpec,
    state: ModelState,
    presentBytes: Long,
    waitingForNetwork: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val installed = state is ModelState.Installed
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.Top) {
        IconBubble(
            when (spec.kind) {
                ModelKind.Asr -> Icons.Filled.RecordVoiceOver
                ModelKind.Vad -> Icons.Filled.GraphicEq
                ModelKind.SpeakerEmbedding -> Icons.Filled.Groups
            },
            tint = if (installed) O2bTheme.extra.success else scheme.secondary,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (installed) {
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.models_installed),
                        tint = O2bTheme.extra.success,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Pill(
                stringResource(if (spec.required) R.string.models_required else R.string.models_optional),
                container = if (spec.required) scheme.primaryContainer else scheme.surfaceContainerHighest,
                content = if (spec.required) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Text(
                "${Formatters.bytes(spec.sizeBytes)} · ${spec.languages}",
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = scheme.onSurfaceVariant,
            )
            Text(spec.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            Text(
                stringResource(R.string.models_license, spec.license),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            when (state) {
                is ModelState.Installed -> TextButton(onClick = onDelete, contentPadding = androidx.compose.material3.ButtonDefaults.TextButtonContentPadding) {
                    Text(stringResource(R.string.delete), color = scheme.error)
                }
                is ModelState.Downloading -> {
                    val fraction = state.bytesDone.toFloat() / state.bytesTotal.coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                        strokeCap = StrokeCap.Round,
                        trackColor = scheme.surfaceContainerHighest,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (waitingForNetwork) {
                                stringResource(R.string.models_waiting_network, Formatters.bytes(state.bytesDone), Formatters.bytes(state.bytesTotal))
                            } else {
                                stringResource(R.string.download_progress, Formatters.bytes(state.bytesDone), Formatters.bytes(state.bytesTotal))
                            },
                            style = MaterialTheme.typography.bodySmall.tabular(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                }
                ModelState.Verifying -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.xs), strokeCap = StrokeCap.Round)
                    Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.bodySmall)
                }
                is ModelState.Failed -> {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = Spacing.xxs)) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xxs))
                        Text(state.message, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        Button(onClick = onDownload, modifier = Modifier.heightIn(min = 40.dp)) { Text(stringResource(R.string.models_retry)) }
                        if (presentBytes > 0) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                    }
                }
                ModelState.NotInstalled -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xxs)) {
                    Button(onClick = onDownload, modifier = Modifier.heightIn(min = 40.dp)) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(if (presentBytes > 0) R.string.models_resume else R.string.models_download))
                    }
                    if (presentBytes > 0) {
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            stringResource(R.string.download_progress, Formatters.bytes(presentBytes), Formatters.bytes(spec.sizeBytes)),
                            style = MaterialTheme.typography.bodySmall.tabular(),
                        )
                    }
                }
            }
        }
    }
}

/** Nachfrage vor dem Download: nur WLAN (empfohlen) oder auch mobile Daten. */
@Composable
fun DownloadDialog(sizeBytes: Long, onWifi: () -> Unit, onAnyNetwork: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.models_download_title)) },
        text = { Text(stringResource(R.string.models_download_text, Formatters.bytes(sizeBytes))) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onWifi) { Text(stringResource(R.string.models_download_wifi)) }
                TextButton(onClick = onAnyNetwork) { Text(stringResource(R.string.models_download_mobile)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun importResultText(r: ImportResult): String = when (r.outcome) {
    ImportResult.Outcome.Imported -> stringResource(R.string.models_import_ok, r.displayName, r.modelName.orEmpty())
    ImportResult.Outcome.AlreadyPresent -> stringResource(R.string.models_import_present, r.displayName)
    ImportResult.Outcome.WrongChecksum -> stringResource(R.string.models_import_bad, r.displayName)
    ImportResult.Outcome.Unknown -> stringResource(R.string.models_import_unknown, r.displayName)
    ImportResult.Outcome.Error -> stringResource(R.string.models_import_error, r.displayName)
}
