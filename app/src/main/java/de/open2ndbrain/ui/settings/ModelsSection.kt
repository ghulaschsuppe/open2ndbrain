package de.open2ndbrain.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.ImportResult
import de.open2ndbrain.models.ImportState
import de.open2ndbrain.ui.Formatters

/** Gesamtgröße aller Modelle (für Hinweise zum Datenvolumen). */
val ALL_MODELS_BYTES: Long get() = ModelCatalog.all.sumOf { it.sizeBytes }

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

    Text(
        stringResource(R.string.models_volume_hint, Formatters.bytes(ALL_MODELS_BYTES)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ModelCatalog.all.forEachIndexed { i, spec ->
        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.models_import_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.models_import_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when (val st = importState) {
        is ImportState.Running -> {
            Text(stringResource(R.string.models_import_running, st.displayName, st.index, st.count))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is ImportState.Done -> {
            st.results.forEach { r -> Text(importResultText(r), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = vm::clearImport) { Text(stringResource(R.string.close)) }
        }
        null -> OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.models_import_button))
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
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    state: ModelState,
    presentBytes: Long,
    waitingForNetwork: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (state is ModelState.Installed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.models_installed), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            stringResource(
                if (spec.required) R.string.models_meta_required else R.string.models_meta_optional,
                Formatters.bytes(spec.sizeBytes),
                spec.languages,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(spec.description, style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.models_license, spec.license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is ModelState.Installed -> TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            is ModelState.Downloading -> {
                val fraction = state.bytesDone.toFloat() / state.bytesTotal.coerceAtLeast(1)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    if (waitingForNetwork) {
                        stringResource(R.string.models_waiting_network, Formatters.bytes(state.bytesDone), Formatters.bytes(state.bytesTotal))
                    } else {
                        stringResource(R.string.download_progress, Formatters.bytes(state.bytesDone), Formatters.bytes(state.bytesTotal))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            ModelState.Verifying -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.bodySmall)
            }
            is ModelState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.models_retry)) }
                    if (presentBytes > 0) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
            }
            ModelState.NotInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDownload) {
                    Text(stringResource(if (presentBytes > 0) R.string.models_resume else R.string.models_download))
                }
                if (presentBytes > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.download_progress, Formatters.bytes(presentBytes), Formatters.bytes(spec.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                    )
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
        title = { Text(stringResource(R.string.models_download_title)) },
        text = { Text(stringResource(R.string.models_download_text, Formatters.bytes(sizeBytes))) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onWifi) { Text(stringResource(R.string.models_download_wifi)) }
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
