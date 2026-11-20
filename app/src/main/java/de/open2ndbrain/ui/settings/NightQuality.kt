package de.open2ndbrain.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular

/** Fortschritt der nächtlichen Verbesserung („12 von 30 verbessert“). */
@Immutable
data class NightProgress(val improved: Int, val total: Int, val modelName: String)

/** Einstellung „Nachts in hoher Qualität neu transkribieren“. */
@Immutable
data class NightQualityUi(
    val enabled: Boolean,
    /** Gewähltes Nachtmodell (null = noch keins installiert). */
    val modelId: String?,
    /** Installierte Spracherkennungsmodelle, die zur Wahl stehen (genaueste zuerst). */
    val candidates: List<ModelSpec>,
    val progress: NightProgress? = null,
)

@Composable
fun NightQualitySetting(ui: NightQualityUi, onEnabled: (Boolean) -> Unit, onModel: (String) -> Unit) {
    val hasModel = ui.candidates.isNotEmpty()
    SwitchSetting(
        Icons.Filled.Bedtime,
        stringResource(R.string.night_title),
        stringResource(if (hasModel) R.string.night_desc else R.string.night_no_model),
        checked = ui.enabled && hasModel,
        enabled = hasModel,
        onChange = onEnabled,
    )
    // Tag/Nacht-Erklärung als kleine Zeitleiste
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 72.dp, end = Spacing.md, bottom = Spacing.xs)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.sm),
    ) {
        DayNightPart(Icons.Filled.WbSunny, stringResource(R.string.night_day), stringResource(R.string.night_day_text), Modifier.weight(1f))
        Spacer(Modifier.width(Spacing.sm))
        DayNightPart(Icons.Filled.BatteryChargingFull, stringResource(R.string.night_night), stringResource(R.string.night_night_text), Modifier.weight(1f))
    }
    if (ui.enabled && hasModel) {
        SettingHint(stringResource(R.string.night_model))
        ui.candidates.forEach { spec ->
            RadioOption(
                selected = spec.id == ui.modelId,
                title = spec.displayName,
                subtitle = spec.benchmark?.let { b ->
                    stringResource(R.string.rating_accuracy) + ": " + stringResource(ModelRatings.accuracy(b).label)
                },
                onClick = { onModel(spec.id) },
            )
        }
        ui.progress?.let { p -> NightProgressLine(p, Modifier.padding(start = 72.dp, end = Spacing.md, top = Spacing.xs, bottom = Spacing.sm)) }
    }
}

@Composable
private fun DayNightPart(icon: ImageVector, title: String, text: String, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xxs))
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** „Nachtqualität: 12 von 30 verbessert“ mit schmalem Balken (Einstellungen und Transkriptions-Karte). */
@Composable
fun NightProgressLine(p: NightProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(
                stringResource(R.string.night_progress, p.improved, p.total),
                style = MaterialTheme.typography.bodySmall.tabular(),
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { if (p.total > 0) p.improved.toFloat() / p.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
            color = MaterialTheme.colorScheme.secondary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Text(
            stringResource(R.string.night_progress_model, p.modelName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
