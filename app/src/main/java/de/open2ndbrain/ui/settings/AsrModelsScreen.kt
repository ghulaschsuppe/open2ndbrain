package de.open2ndbrain.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.R
import de.open2ndbrain.ui.models.AsrModelItem
import de.open2ndbrain.ui.models.ModelsViewModel
import de.open2ndbrain.ui.components.StatusDot
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ButtonDefaults
import de.open2ndbrain.core.AsrBenchmark
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelKind
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.models.ImportResult
import de.open2ndbrain.models.ImportState
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.DetailTopBar
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.components.WidthAware
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import java.util.Locale

/** Alles, was die Seite „Spracherkennung“ zeigt (aus dem [ModelsViewModel]). */
@Immutable
data class ModelsUi(
    val asr: List<AsrModelItem>,
    val others: List<Pair<ModelSpec, ModelState>>,
    val installedBytes: Long = 0,
    val upgrade: ModelSpec? = null,
    val importState: ImportState? = null,
)

/** Aktionen der Modellseite. */
class ModelsActions(
    val onDownload: (ModelSpec, unmeteredOnly: Boolean) -> Unit = { _, _ -> },
    val onCancel: (String) -> Unit = {},
    /** false = nicht löschbar (aktives Modell). */
    val onDelete: (String) -> Boolean = { true },
    val onActivate: (String) -> Unit = {},
    val onAcceptUpgrade: (unmeteredOnly: Boolean) -> Unit = {},
    val onDismissUpgrade: () -> Unit = {},
    val onImport: (List<android.net.Uri>) -> Unit = {},
)

/** Seite „Spracherkennung“, verdrahtet mit dem [ModelsViewModel] des App-Agenten. */
@Composable
fun AsrModelsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: ModelsViewModel = viewModel()) {
    val asr by vm.asrModels.collectAsStateWithLifecycle()
    val others by vm.otherModels.collectAsStateWithLifecycle()
    val bytes by vm.installedBytes.collectAsStateWithLifecycle()
    val upgrade by vm.upgradeSuggestion.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val offer by vm.offerRetranscribe.collectAsStateWithLifecycle()

    AsrModelsContent(
        ui = ModelsUi(asr, others, bytes, upgrade, importState),
        actions = ModelsActions(
            onDownload = { spec, wifi -> vm.download(spec.id, wifi) },
            onCancel = vm::cancel,
            onDelete = vm::delete,
            onActivate = { vm.activate(it) },
            onAcceptUpgrade = vm::acceptUpgrade,
            onDismissUpgrade = { vm.dismissUpgrade() },
            onImport = vm::import,
        ),
        onBack = onBack,
        modifier = modifier,
    )

    if (offer) {
        val activeName = asr.firstOrNull { it.active }?.spec?.displayName.orEmpty()
        RetranscribeOfferDialog(
            modelName = activeName,
            estimate = { days -> vm.estimateRecent(days) },
            onConfirm = { vm.retranscribeRecent(it) },
            onDismiss = vm::dismissRetranscribeOffer,
        )
    }
}

/** Seite „Spracherkennung“: Erklärung, Karten je Modell mit Genauigkeit/Tempo, weitere Modelle, Import. */
@Composable
fun AsrModelsContent(ui: ModelsUi, actions: ModelsActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var askDownload by remember { mutableStateOf<Pair<ModelSpec, Long>?>(null) }
    var askDelete by remember { mutableStateOf<ModelSpec?>(null) }
    var cannotDelete by remember { mutableStateOf(false) }
    val activeName = ui.asr.firstOrNull { it.active }?.spec?.displayName
    val chosenName = ui.asr.firstOrNull { it.chosen }?.spec?.displayName

    Column(modifier.fillMaxSize()) {
        DetailTopBar(
            stringResource(R.string.asr_title),
            onBack = onBack,
            subtitle = if (ui.installedBytes > 0) {
                stringResource(R.string.asr_subtitle_bytes, Formatters.bytes(ui.installedBytes))
            } else {
                stringResource(R.string.asr_subtitle)
            },
        )
        WidthAware(Modifier.fillMaxSize()) { side ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side + Spacing.md, end = side + Spacing.md, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                ui.upgrade?.let { spec ->
                    item(key = "upgrade") {
                        UpgradeCard(spec, onAccept = actions.onAcceptUpgrade, onDismiss = actions.onDismissUpgrade)
                    }
                }
                item(key = "intro") { IntroCard() }
                item(key = "asr-label") {
                    SectionLabel(stringResource(R.string.asr_section), modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm))
                }
                items(ui.asr, key = { it.spec.id }) { m ->
                    AsrModelCard(
                        item = m,
                        activeName = activeName,
                        chosenName = chosenName,
                        onDownload = { askDownload = m.spec to m.presentBytes },
                        onCancel = { actions.onCancel(m.spec.id) },
                        onDelete = { askDelete = m.spec },
                        onActivate = { actions.onActivate(m.spec.id) },
                    )
                }
                item(key = "others-label") {
                    SectionLabel(stringResource(R.string.models_others), modifier = Modifier.padding(start = Spacing.xs, top = Spacing.md))
                }
                item(key = "others") {
                    O2bCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                        Text(
                            stringResource(R.string.models_others_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        )
                        ui.others.forEach { (spec, state) ->
                            SettingDivider()
                            ModelRow(
                                spec = spec,
                                state = state,
                                presentBytes = 0,
                                waitingForNetwork = false,
                                onDownload = { askDownload = spec to 0L },
                                onCancel = { actions.onCancel(spec.id) },
                                onDelete = { askDelete = spec },
                            )
                        }
                    }
                }
                item(key = "import") { ImportCard(ui.importState, actions) }
            }
        }
    }

    askDownload?.let { (spec, present) ->
        DownloadDialog(
            sizeBytes = spec.sizeBytes - present,
            onWifi = {
                actions.onDownload(spec, true)
                askDownload = null
            },
            onAnyNetwork = {
                actions.onDownload(spec, false)
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
                    if (!actions.onDelete(spec.id)) cannotDelete = true
                    askDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (cannotDelete) {
        AlertDialog(
            onDismissRequest = { cannotDelete = false },
            title = { Text(stringResource(R.string.models_delete_active_title)) },
            text = { Text(stringResource(R.string.models_delete_active_text)) },
            confirmButton = { TextButton(onClick = { cannotDelete = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun IntroCard() {
    O2bCard(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = null,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.asr_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.asr_intro_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Wechsel-Vorschlag für Bestandsinstallationen („Neues, besseres Deutsch-Modell verfügbar“).
 * Wird im Jetzt-Tab und oben auf der Modellseite gezeigt. [onOpen] (optional) führt zur Modellseite.
 */
@Composable
fun UpgradeCard(
    spec: ModelSpec,
    onAccept: (unmeteredOnly: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    val extra = O2bTheme.extra
    O2bCard(
        modifier.fillMaxWidth(),
        color = extra.meetingContainer.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, extra.meeting.copy(alpha = 0.4f)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = extra.meeting)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.upgrade_title, Formatters.bytes(spec.sizeBytes)), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.upgrade_text, spec.displayName), style = MaterialTheme.typography.bodySmall)
                spec.benchmark?.let { b ->
                    Text(
                        stringResource(R.string.rating_accuracy) + ": " + stringResource(ModelRatings.accuracy(b).label) +
                            " · " + stringResource(R.string.rating_speed) + ": " + stringResource(ModelRatings.speed(b).label),
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.meeting,
                    )
                }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_hint_later)) }
            if (onOpen != null) OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.model_hint_open)) }
            Button(
                onClick = { onAccept(true) },
                colors = ButtonDefaults.buttonColors(containerColor = extra.meeting, contentColor = MaterialTheme.colorScheme.surface),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.upgrade_accept))
            }
        }
    }
}

/** Karte eines Spracherkennungsmodells – „gewählt“ und „aktiv“ werden getrennt sichtbar gemacht. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsrModelCard(
    item: AsrModelItem,
    activeName: String?,
    chosenName: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    val spec = item.spec
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    val pendingChoice = item.chosen && !item.active
    O2bCard(
        Modifier.fillMaxWidth().animateContentSize(),
        border = when {
            item.active -> BorderStroke(1.5.dp, scheme.primary)
            pendingChoice -> BorderStroke(1.5.dp, scheme.secondary)
            else -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f))
        },
    ) {
        val badges = spec.recommended || item.active || item.chosen || item.installed
        if (badges) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                if (item.active) {
                    Pill(
                        stringResource(R.string.models_active),
                        leading = { StatusDot(extra.success) },
                        container = scheme.primaryContainer,
                        content = scheme.onPrimaryContainer,
                    )
                }
                if (pendingChoice) {
                    Pill(
                        stringResource(R.string.models_chosen),
                        icon = Icons.Filled.Schedule,
                        container = scheme.secondaryContainer,
                        content = scheme.onSecondaryContainer,
                    )
                }
                if (spec.recommended) {
                    Pill(
                        stringResource(R.string.models_recommended),
                        icon = Icons.Filled.Star,
                        container = extra.meetingContainer,
                        content = extra.onMeetingContainer,
                    )
                }
                if (item.installed && !item.active) Pill(stringResource(R.string.models_installed))
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            "${spec.languages} · ${Formatters.bytes(spec.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = scheme.onSurfaceVariant,
        )
        Text(spec.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))

        spec.benchmark?.let { b ->
            Spacer(Modifier.height(Spacing.sm))
            RatingBar(stringResource(R.string.rating_accuracy), ModelRatings.accuracy(b), scheme.primary)
            Spacer(Modifier.height(Spacing.xs))
            RatingBar(stringResource(R.string.rating_speed), ModelRatings.speed(b), scheme.secondary)
            Text(
                benchmarkDetail(b),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        // Hinweis, wenn Wahl und tatsächlicher Einsatz auseinanderfallen
        val note = when {
            pendingChoice && activeName != null -> stringResource(R.string.models_chosen_pending, activeName)
            pendingChoice -> stringResource(R.string.models_chosen_pending_none)
            item.active && !item.chosen && chosenName != null -> stringResource(R.string.models_active_fallback, chosenName)
            item.active -> stringResource(R.string.models_active_hint)
            else -> null
        }
        if (note != null) {
            Row(
                Modifier
                    .padding(top = Spacing.sm)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(if (item.active) scheme.primaryContainer.copy(alpha = 0.4f) else scheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (item.active) Icons.Filled.CheckCircle else Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = if (item.active) extra.success else scheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        when (val st = item.state) {
            is ModelState.Installed -> if (!item.active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onActivate, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.models_activate))
                    }
                    Spacer(Modifier.weight(1f))
                    if (item.deletable) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = scheme.error) }
                }
            }
            is ModelState.Downloading -> {
                val fraction = item.downloadProgress ?: 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                    trackColor = scheme.surfaceContainerHighest,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.waitingForNetwork) {
                            stringResource(R.string.models_waiting_network, Formatters.bytes(st.bytesDone), Formatters.bytes(st.bytesTotal))
                        } else {
                            stringResource(R.string.download_progress, Formatters.bytes(st.bytesDone), Formatters.bytes(st.bytesTotal)) +
                                " · " + Formatters.percent(fraction)
                        },
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            ModelState.Verifying -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                Text(stringResource(R.string.models_verifying), style = MaterialTheme.typography.bodySmall)
            }
            is ModelState.Failed -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Spacing.xxs))
                    Text(st.message, color = scheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    Button(onClick = onDownload, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.models_retry)) }
                    if (item.presentBytes > 0) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
            }
            ModelState.NotInstalled -> {
                val label = if (item.presentBytes > 0) {
                    stringResource(R.string.models_resume_size, Formatters.bytes(spec.sizeBytes - item.presentBytes))
                } else {
                    stringResource(R.string.models_download_size, Formatters.bytes(spec.sizeBytes))
                }
                val buttonContent: @Composable () -> Unit = {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(label)
                }
                if (spec.recommended || item.chosen) {
                    Button(onClick = onDownload, modifier = Modifier.heightIn(min = 44.dp)) { buttonContent() }
                } else {
                    FilledTonalButton(onClick = onDownload, modifier = Modifier.heightIn(min = 44.dp)) { buttonContent() }
                }
            }
        }
    }
}

/** Beschrifteter Balken aus fünf Stufen: „Genauigkeit im Alltag: sehr gut“. */
@Composable
private fun RatingBar(title: String, rating: Rating, color: Color) {
    val word = stringResource(rating.label)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$title: $word" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(word, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xxs)
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(ModelRatings.MAX) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i < rating.level) color else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
    }
}

@Composable
private fun benchmarkDetail(b: AsrBenchmark): String =
    stringResource(R.string.benchmark_detail, pct(b.werCleanPct), pct(b.werEverydayPct), String.format(Locale.GERMANY, "%.2f", b.realTimeFactor))

private fun pct(v: Double): String = String.format(Locale.GERMANY, "%.1f", v).removeSuffix(",0")

@Composable
private fun ImportCard(state: ImportState?, actions: ModelsActions) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> actions.onImport(uris) }
    O2bCard(Modifier.fillMaxWidth().padding(top = Spacing.xs), contentPadding = PaddingValues(vertical = Spacing.xs)) {
        SettingHeader(Icons.Filled.FileOpen, stringResource(R.string.models_import_title), stringResource(R.string.models_import_hint), tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.padding(start = 72.dp, end = Spacing.md, bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            when (state) {
                is ImportState.Running -> {
                    Text(stringResource(R.string.models_import_running, state.displayName, state.index, state.count), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
                }
                is ImportState.Done -> {
                    state.results.forEach { r -> Text(importText(r), style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.models_import_done), style = MaterialTheme.typography.labelSmall)
                }
                null -> OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.models_import_button))
                }
            }
        }
    }
}

@Composable
private fun importText(r: ImportResult): String = when (r.outcome) {
    ImportResult.Outcome.Imported -> stringResource(R.string.models_import_ok, r.displayName, r.modelName.orEmpty())
    ImportResult.Outcome.AlreadyPresent -> stringResource(R.string.models_import_present, r.displayName)
    ImportResult.Outcome.WrongChecksum -> stringResource(R.string.models_import_bad, r.displayName)
    ImportResult.Outcome.Unknown -> stringResource(R.string.models_import_unknown, r.displayName)
    ImportResult.Outcome.Error -> stringResource(R.string.models_import_error, r.displayName)
}
