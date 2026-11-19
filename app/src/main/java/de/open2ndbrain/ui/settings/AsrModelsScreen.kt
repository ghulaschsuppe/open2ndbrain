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

/** Ein Modell mit seinem Zustand (UI-Sicht; wird aus dem ViewModel befüllt). */
@Immutable
data class ModelItemUi(
    val spec: ModelSpec,
    val state: ModelState,
    val presentBytes: Long = 0,
    val waitingForNetwork: Boolean = false,
)

/** Alles, was die Seite „Spracherkennung“ zeigt. */
@Immutable
data class ModelsUi(
    val asr: List<ModelItemUi>,
    val others: List<ModelItemUi>,
    /** Aktives Spracherkennungsmodell; null = noch nicht bekannt/keins. */
    val activeAsrId: String?,
    val importState: ImportState? = null,
)

/** Aktionen der Modellseite. [onActivate] = null blendet „Aktivieren“ aus (noch nicht verdrahtet). */
class ModelsActions(
    val onDownload: (ModelSpec, unmeteredOnly: Boolean) -> Unit = { _, _ -> },
    val onCancel: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onActivate: ((String) -> Unit)? = null,
    val onImport: (List<android.net.Uri>) -> Unit = {},
    val onClearImport: () -> Unit = {},
)

/** Verdrahtung mit dem bestehenden [SettingsViewModel] (bis das ModelsViewModel des App-Agenten da ist). */
@Composable
fun AsrModelsScreen(vm: SettingsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val states by vm.modelStates.collectAsStateWithLifecycle()
    val present by vm.presentBytes.collectAsStateWithLifecycle()
    val waiting by vm.waitingForNetwork.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    fun item(spec: ModelSpec) = ModelItemUi(spec, states[spec.id] ?: ModelState.NotInstalled, present[spec.id] ?: 0L, spec.id in waiting)
    AsrModelsContent(
        ui = ModelsUi(
            asr = ModelCatalog.asrModels.map(::item),
            others = ModelCatalog.all.filter { it.kind != ModelKind.Asr }.map(::item),
            activeAsrId = null,
            importState = importState,
        ),
        actions = ModelsActions(
            onDownload = { spec, wifi -> vm.download(spec.id, wifi) },
            onCancel = vm::cancel,
            onDelete = { vm.deleteModel(it) },
            onImport = vm::import,
            onClearImport = vm::clearImport,
        ),
        onBack = onBack,
        modifier = modifier,
    )
}

/** Seite „Spracherkennung“: Erklärung, Karten je Modell mit Genauigkeit/Tempo, weitere Modelle, Import. */
@Composable
fun AsrModelsContent(ui: ModelsUi, actions: ModelsActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var askDownload by remember { mutableStateOf<ModelItemUi?>(null) }
    var askDelete by remember { mutableStateOf<ModelSpec?>(null) }

    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.asr_title), onBack = onBack, subtitle = stringResource(R.string.asr_subtitle))
        WidthAware(Modifier.fillMaxSize()) { side ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side + Spacing.md, end = side + Spacing.md, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "intro") { IntroCard() }
                item(key = "asr-label") {
                    SectionLabel(stringResource(R.string.asr_section), modifier = Modifier.padding(start = Spacing.xs, top = Spacing.sm))
                }
                items(ui.asr, key = { it.spec.id }) { m ->
                    AsrModelCard(
                        item = m,
                        active = m.spec.id == ui.activeAsrId,
                        onDownload = { askDownload = m },
                        onCancel = { actions.onCancel(m.spec.id) },
                        onDelete = { askDelete = m.spec },
                        onActivate = actions.onActivate?.let { a -> { a(m.spec.id) } },
                    )
                }
                item(key = "others-label") {
                    SectionLabel(stringResource(R.string.models_others), modifier = Modifier.padding(start = Spacing.xs, top = Spacing.md))
                }
                item(key = "others") {
                    O2bCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs)) {
                        ui.others.forEachIndexed { i, m ->
                            if (i > 0) SettingDivider()
                            ModelRow(
                                spec = m.spec,
                                state = m.state,
                                presentBytes = m.presentBytes,
                                waitingForNetwork = m.waitingForNetwork,
                                onDownload = { askDownload = m },
                                onCancel = { actions.onCancel(m.spec.id) },
                                onDelete = { askDelete = m.spec },
                            )
                        }
                    }
                }
                item(key = "import") { ImportCard(ui.importState, actions) }
            }
        }
    }

    askDownload?.let { m ->
        DownloadDialog(
            sizeBytes = m.spec.sizeBytes - m.presentBytes,
            onWifi = {
                actions.onDownload(m.spec, true)
                askDownload = null
            },
            onAnyNetwork = {
                actions.onDownload(m.spec, false)
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
                    actions.onDelete(spec.id)
                    askDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.cancel)) } },
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

/** Karte eines Spracherkennungsmodells. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AsrModelCard(
    item: ModelItemUi,
    active: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onActivate: (() -> Unit)?,
) {
    val spec = item.spec
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    val installed = item.state is ModelState.Installed
    O2bCard(
        Modifier.fillMaxWidth().animateContentSize(),
        border = if (active) BorderStroke(1.5.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            if (spec.recommended) {
                Pill(
                    stringResource(R.string.models_recommended),
                    icon = Icons.Filled.Star,
                    container = extra.meetingContainer,
                    content = extra.onMeetingContainer,
                )
            }
            if (active) {
                Pill(
                    stringResource(R.string.models_active),
                    icon = Icons.Filled.CheckCircle,
                    container = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer,
                )
            } else if (installed) {
                Pill(stringResource(R.string.models_installed))
            }
        }
        if (spec.recommended || active || installed) Spacer(Modifier.height(Spacing.xs))
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

        Spacer(Modifier.height(Spacing.sm))
        when (val st = item.state) {
            is ModelState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = extra.success, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        stringResource(R.string.models_active_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    if (onActivate != null) {
                        Button(onClick = onActivate, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(stringResource(R.string.models_activate))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = scheme.error) }
                }
            }
            is ModelState.Downloading -> {
                val fraction = st.bytesDone.toFloat() / st.bytesTotal.coerceAtLeast(1)
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
                if (spec.recommended) {
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
                    TextButton(onClick = actions.onClearImport) { Text(stringResource(R.string.close)) }
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
