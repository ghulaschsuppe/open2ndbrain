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

/** Größe der empfohlenen Grundausstattung (empfohlenes Spracherkennungsmodell, VAD, Sprechererkennung). */
val ALL_MODELS_BYTES: Long get() = de.open2ndbrain.models.AsrSelection.RECOMMENDED_BYTES

/** Eintrag in den Einstellungen: öffnet die Seite „Spracherkennung“, mit kurzer Zusammenfassung. */
@Composable
fun ModelsLink(vm: SettingsViewModel, onOpen: () -> Unit) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val installed = ModelCatalog.asrModels.filter { states[it.id] is ModelState.Installed }
    val downloading = ModelCatalog.all.any { states[it.id] is ModelState.Downloading || states[it.id] == ModelState.Verifying }
    LinkSetting(
        Icons.Filled.RecordVoiceOver,
        stringResource(R.string.asr_title),
        when {
            downloading -> stringResource(R.string.models_link_downloading)
            installed.isEmpty() -> stringResource(R.string.models_link_none)
            else -> stringResource(R.string.models_link_installed, installed.joinToString(", ") { it.displayName })
        },
        onClick = onOpen,
    )
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
