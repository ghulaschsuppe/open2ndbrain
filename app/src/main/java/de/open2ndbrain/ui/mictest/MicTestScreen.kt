package de.open2ndbrain.ui.mictest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableRestaurant
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.DetailTopBar
import de.open2ndbrain.ui.components.Illustration
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.components.SpotIllustration
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.components.WidthAware
import de.open2ndbrain.ui.theme.O2bTextStyles
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular

// ---------------------------------------------------------------- UI-Vertrag (wird vom ViewModel befüllt)

/** Situation, in der getestet wird (bestimmt das Mikrofon und die Entfernung). */
enum class MicSituation(val icon: ImageVector, val label: Int) {
    Table(Icons.Filled.TableRestaurant, R.string.mictest_sit_table),
    Pocket(Icons.Filled.Work, R.string.mictest_sit_pocket),
    Hand(Icons.Filled.Smartphone, R.string.mictest_sit_hand),
    Headset(Icons.Filled.Headset, R.string.mictest_sit_headset),
}

/** Ein Wort im erkannten Text, verglichen mit dem Vorlesetext. */
@Immutable
data class WordMark(val text: String, val kind: Kind) {
    enum class Kind { Ok, Wrong, Missing, Extra }
}

/** Ergebnis eines Modells im Mikrofon-Test. */
@Immutable
data class MicTestResult(
    val spec: ModelSpec,
    /** Wortfehlerrate in Prozent (0 = perfekt). */
    val werPct: Double,
    val processingMs: Long,
    val audioMs: Long,
    val words: List<WordMark> = emptyList(),
    val active: Boolean = false,
)

/** Ein früherer Test (Verlauf). */
@Immutable
data class MicTestHistoryEntry(val atEpochMs: Long, val situation: MicSituation, val bestModel: String, val bestWerPct: Double)

/** Zustand des Mikrofon-Tests. */
sealed interface MicTestUi {
    data class Intro(val situation: MicSituation = MicSituation.Table, val history: List<MicTestHistoryEntry> = emptyList()) : MicTestUi
    data class Reading(val text: String, val elapsedMs: Long, val level: Float, val speech: Boolean) : MicTestUi
    data class Evaluating(val modelName: String, val index: Int, val count: Int, val progress: Float) : MicTestUi
    data class Result(
        val results: List<MicTestResult>,
        val bestId: String?,
        val situation: MicSituation,
        val history: List<MicTestHistoryEntry> = emptyList(),
        /** Anfangs aufgeklappter Text (z. B. für Vorschauen). */
        val openId: String? = null,
    ) : MicTestUi
}

class MicTestActions(
    val onSituation: (MicSituation) -> Unit = {},
    val onStart: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onActivate: (String) -> Unit = {},
    val onRepeat: () -> Unit = {},
)

/** Note aus der Wortfehlerrate: 1 (sehr gut) … 5 (unbrauchbar), mit Wort. */
object MicTestGrades {
    fun grade(werPct: Double): Int = when {
        werPct <= 5.0 -> 1
        werPct <= 10.0 -> 2
        werPct <= 20.0 -> 3
        werPct <= 35.0 -> 4
        else -> 5
    }

    fun label(grade: Int): Int = when (grade) {
        1 -> R.string.grade_1
        2 -> R.string.grade_2
        3 -> R.string.grade_3
        4 -> R.string.grade_4
        else -> R.string.grade_5
    }

    /** Anteil richtig erkannter Wörter (0..1) für den Balken. */
    fun accuracy(werPct: Double): Float = (1.0 - werPct / 100.0).coerceIn(0.0, 1.0).toFloat()
}

// ---------------------------------------------------------------- Oberfläche

/** Mikrofon-Test: Erklärung → Vorlesen → Auswertung → Ergebnis. */
@Composable
fun MicTestContent(ui: MicTestUi, actions: MicTestActions, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.mictest_title), onBack = onBack, subtitle = stringResource(R.string.mictest_subtitle))
        AnimatedContent(
            targetState = ui,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "mictest",
            modifier = Modifier.weight(1f),
        ) { s ->
            when (s) {
                is MicTestUi.Intro -> IntroStep(s, actions)
                is MicTestUi.Reading -> ReadingStep(s, actions)
                is MicTestUi.Evaluating -> EvaluatingStep(s, actions)
                is MicTestUi.Result -> ResultStep(s, actions)
            }
        }
    }
}

@Composable
private fun StepColumn(bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            content = bottom,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntroStep(s: MicTestUi.Intro, actions: MicTestActions) {
    StepColumn(bottom = {
        Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(stringResource(R.string.mictest_start))
        }
    }) {
        SpotIllustration(Illustration.Microphone, Modifier.size(150.dp).align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.mictest_intro_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.mictest_intro_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel(stringResource(R.string.mictest_situation))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            MicSituation.entries.forEach { sit ->
                FilterChip(
                    selected = sit == s.situation,
                    onClick = { actions.onSituation(sit) },
                    label = { Text(stringResource(sit.label)) },
                    leadingIcon = { Icon(sit.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        O2bCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = null) {
            listOf(R.string.mictest_tip_1, R.string.mictest_tip_2, R.string.mictest_tip_3).forEachIndexed { i, tip ->
                Row(Modifier.padding(vertical = Spacing.xxs), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(tip), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (s.history.isNotEmpty()) HistoryCard(s.history)
    }
}

@Composable
private fun ReadingStep(s: MicTestUi.Reading, actions: MicTestActions) {
    val level by animateFloatAsState(s.level.coerceIn(0f, 1f), tween(150), label = "level")
    val extra = O2bTheme.extra
    StepColumn(bottom = {
        LevelMeter(level, s.speech)
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(extra.live, pulsing = true, size = 10.dp)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                Formatters.clock(s.elapsedMs),
                style = MaterialTheme.typography.titleLarge.tabular(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(Spacing.xs))
            Button(onClick = actions.onFinish, modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.mictest_done))
            }
        }
    }) {
        Text(stringResource(R.string.mictest_read_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Großer, ruhiger Lesetext
        Text(
            s.text,
            style = O2bTextStyles.transcript.copy(fontSize = 24.sp, lineHeight = 38.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Pegelanzeige aus 24 Balken; leuchtet mint, wenn Sprache erkannt wird. */
@Composable
private fun LevelMeter(level: Float, speech: Boolean) {
    val on = if (speech) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = stringResource(if (speech) R.string.record_speech_detected else R.string.record_listening)
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bars = 24
        repeat(bars) { i ->
            val lit = i < (level * bars).toInt()
            val h = 0.35f + 0.65f * (1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (lit) on else off),
            )
        }
    }
}

@Composable
private fun EvaluatingStep(s: MicTestUi.Evaluating, actions: MicTestActions) {
    StepColumn(bottom = {
        TextButton(onClick = actions.onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
    }) {
        Spacer(Modifier.height(Spacing.xl))
        SpotIllustration(Illustration.Transcript, Modifier.size(150.dp).align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.mictest_evaluating), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            stringResource(R.string.mictest_evaluating_model, s.modelName, s.index, s.count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        LinearProgressIndicator(
            progress = { s.progress },
            modifier = Modifier.fillMaxWidth(),
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun ResultStep(s: MicTestUi.Result, actions: MicTestActions) {
    val sorted = s.results.sortedBy { it.werPct }
    WidthAware(Modifier.fillMaxSize()) { side ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side + Spacing.md, end = side + Spacing.md, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs)) {
                    Text(stringResource(R.string.mictest_result_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.mictest_result_text, stringResource(s.situation.label)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sorted, key = { it.spec.id }) { r ->
                ResultCard(r, best = r.spec.id == s.bestId, initiallyOpen = r.spec.id == s.openId, onActivate = { actions.onActivate(r.spec.id) })
            }
            item(key = "repeat") {
                OutlinedButton(onClick = actions.onRepeat, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.mictest_repeat))
                }
            }
            if (s.history.isNotEmpty()) item(key = "history") { HistoryCard(s.history) }
        }
    }
}

@Composable
private fun ResultCard(r: MicTestResult, best: Boolean, initiallyOpen: Boolean, onActivate: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    val grade = MicTestGrades.grade(r.werPct)
    var open by rememberSaveable(r.spec.id) { mutableStateOf(initiallyOpen) }
    O2bCard(
        Modifier.fillMaxWidth().animateContentSize(),
        color = if (best) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainer,
        border = if (best) BorderStroke(1.5.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        if (best) {
            Pill(
                stringResource(R.string.mictest_best),
                icon = Icons.Filled.EmojiEvents,
                container = extra.meetingContainer,
                content = extra.onMeetingContainer,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.spec.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.mictest_compute, Formatters.seconds(r.processingMs), Formatters.seconds(r.audioMs)),
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = scheme.onSurfaceVariant,
                )
            }
            GradeBadge(grade)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(MicTestGrades.label(grade)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.mictest_wer, Formatters.percent((r.werPct / 100).toFloat())),
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = scheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { MicTestGrades.accuracy(r.werPct) },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs).height(8.dp),
            color = gradeColor(grade),
            trackColor = scheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (r.words.isNotEmpty()) {
                TextButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xxs))
                    Text(stringResource(if (open) R.string.mictest_hide_text else R.string.mictest_show_text))
                }
            }
            Spacer(Modifier.weight(1f))
            if (r.active) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = extra.success, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xxs))
                Text(stringResource(R.string.models_active), style = MaterialTheme.typography.labelLarge, color = extra.success)
            } else if (best) {
                Button(onClick = onActivate) { Text(stringResource(R.string.models_activate)) }
            } else {
                TextButton(onClick = onActivate) { Text(stringResource(R.string.models_activate)) }
            }
        }
        if (open) {
            Text(
                markedText(r.words),
                style = O2bTextStyles.transcript,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Text(
                stringResource(R.string.mictest_legend),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
    }
}

@Composable
private fun gradeColor(grade: Int): Color = when (grade) {
    1, 2 -> O2bTheme.extra.success
    3 -> MaterialTheme.colorScheme.secondary
    4 -> O2bTheme.extra.meeting
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun GradeBadge(grade: Int) {
    val color = gradeColor(grade)
    val description = stringResource(R.string.mictest_grade_desc, grade, stringResource(MicTestGrades.label(grade)))
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.18f))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("$grade", style = O2bTextStyles.metric, color = color)
    }
}

/** Erkannter Text mit markierten Fehlwörtern: falsch (Koralle, unterstrichen), fehlt (durchgestrichen), zu viel (Amber). */
@Composable
private fun markedText(words: List<WordMark>): AnnotatedString {
    val err = MaterialTheme.colorScheme.error
    val extra = O2bTheme.extra
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        words.forEachIndexed { i, w ->
            if (i > 0) append(' ')
            when (w.kind) {
                WordMark.Kind.Ok -> append(w.text)
                WordMark.Kind.Wrong -> withStyle(SpanStyle(color = err, background = err.copy(alpha = 0.12f), textDecoration = TextDecoration.Underline)) { append(w.text) }
                WordMark.Kind.Missing -> withStyle(SpanStyle(color = muted, textDecoration = TextDecoration.LineThrough)) { append(w.text) }
                WordMark.Kind.Extra -> withStyle(SpanStyle(color = extra.meeting, background = extra.meeting.copy(alpha = 0.12f))) { append(w.text) }
            }
        }
    }
}

@Composable
private fun HistoryCard(history: List<MicTestHistoryEntry>) {
    O2bCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            SectionLabel(stringResource(R.string.mictest_history))
        }
        history.take(5).forEach { h ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(h.situation.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(h.bestModel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${Formatters.dateTime(h.atEpochMs)} · ${stringResource(h.situation.label)}",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GradeChip(MicTestGrades.grade(h.bestWerPct))
            }
        }
    }
}

@Composable
private fun GradeChip(grade: Int) {
    val color = gradeColor(grade)
    Text(
        stringResource(R.string.mictest_grade_short, grade),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Einstiegskarte „Welches Modell passt zu dir?“ (Jetzt-Tab und Spracherkennungs-Seite). */
@Composable
fun MicTestEntryCard(onStart: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    O2bCard(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        border = null,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) }
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mictest_entry_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.mictest_entry_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.End) {
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_hint_later)) }
            Button(onClick = onStart) { Text(stringResource(R.string.mictest_entry_action)) }
        }
    }
}
