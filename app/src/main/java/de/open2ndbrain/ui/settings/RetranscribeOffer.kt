package de.open2ndbrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.tabular
import de.open2ndbrain.work.Retranscription

/** Zeiträume, die nach einem Modellwechsel zum Neu-Transkribieren angeboten werden. */
val RETRANSCRIBE_DAYS = listOf(1, 7, 30)

/** Dialog nach einem Modellwechsel: „Transkripte der letzten N Tage neu erstellen?“ (Standard: nein). */
@Composable
fun RetranscribeOfferDialog(
    modelName: String,
    estimate: suspend (Int) -> Retranscription.Estimate,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by remember { mutableIntStateOf(RETRANSCRIBE_DAYS[1]) }
    var result by remember { mutableStateOf<Retranscription.Estimate?>(null) }
    LaunchedEffect(days) {
        result = null
        result = estimate(days)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        title = { Text(stringResource(R.string.retranscribe_offer_title)) },
        text = { RetranscribeOfferContent(modelName, days, { days = it }, result) },
        confirmButton = {
            TextButton(onClick = { onConfirm(days) }, enabled = (result?.files ?: 0) > 0) {
                Text(stringResource(R.string.retranscribe_offer_yes))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.retranscribe_offer_no)) } },
    )
}

/** Inhalt des Dialogs (auch für Screenshots): Zeitraum-Chips und Zeitschätzung. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RetranscribeOfferContent(modelName: String, days: Int, onDays: (Int) -> Unit, estimate: Retranscription.Estimate?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.retranscribe_offer_text, modelName), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            RETRANSCRIBE_DAYS.forEach { d ->
                FilterChip(
                    selected = d == days,
                    onClick = { onDays(d) },
                    label = { Text(pluralStringResource(R.plurals.retranscribe_days, d, d)) },
                    modifier = Modifier.heightIn(min = 40.dp),
                )
            }
        }
        O2bCard(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = null,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (estimate == null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(Spacing.sm))
                Column {
                    when {
                        estimate == null -> Text(stringResource(R.string.retranscribe_offer_estimating), style = MaterialTheme.typography.bodySmall)
                        estimate.files == 0 -> Text(stringResource(R.string.retranscribe_offer_nothing), style = MaterialTheme.typography.bodySmall)
                        else -> {
                            Text(
                                stringResource(R.string.retranscribe_offer_estimate, Formatters.duration(estimate.estimatedMs)),
                                style = MaterialTheme.typography.titleSmall.tabular(),
                            )
                            Text(
                                pluralStringResource(R.plurals.retranscribe_offer_files, estimate.files, estimate.files, Formatters.duration(estimate.audioMs)),
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.retranscribe_offer_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}
