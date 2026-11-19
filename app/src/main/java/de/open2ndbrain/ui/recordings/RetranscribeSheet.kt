package de.open2ndbrain.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelSpec
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.settings.ModelRatings
import de.open2ndbrain.ui.theme.Spacing

/** Bottom-Sheet „Mit anderem Modell neu transkribieren…“ (nur installierte Spracherkennungsmodelle). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetranscribeSheet(
    installed: List<ModelSpec>,
    currentModelId: String?,
    onPick: (String) -> Unit,
    onOpenModels: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        RetranscribeSheetContent(installed, currentModelId, onPick = {
            onPick(it)
            onDismiss()
        }, onOpenModels = onOpenModels?.let { o -> { onDismiss(); o() } }, onCancel = onDismiss)
    }
}

/** Inhalt des Sheets (auch für Screenshots ohne eigenes Fenster). */
@Composable
fun RetranscribeSheetContent(
    installed: List<ModelSpec>,
    currentModelId: String?,
    onPick: (String) -> Unit,
    onOpenModels: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var choice by remember(installed, currentModelId) {
        mutableStateOf(installed.firstOrNull { it.id != currentModelId }?.id ?: currentModelId)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Text(stringResource(R.string.retranscribe_with_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.retranscribe_with_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs, bottom = Spacing.sm),
        )
        if (installed.isEmpty()) {
            Text(stringResource(R.string.models_link_none), style = MaterialTheme.typography.bodyMedium)
        }
        installed.forEach { spec ->
            val current = spec.id == currentModelId
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = choice == spec.id, role = Role.RadioButton) { choice = spec.id }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = choice == spec.id, onClick = null)
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, style = MaterialTheme.typography.bodyLarge)
                    spec.benchmark?.let { b ->
                        Text(
                            stringResource(R.string.rating_accuracy) + ": " + stringResource(ModelRatings.accuracy(b).label) +
                                " · " + stringResource(R.string.rating_speed) + ": " + stringResource(ModelRatings.speed(b).label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (current) Pill(stringResource(R.string.retranscribe_current))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm, bottom = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (onOpenModels != null) TextButton(onClick = onOpenModels) { Text(stringResource(R.string.retranscribe_more_models)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(Spacing.xs))
            Button(onClick = { choice?.let(onPick) }, enabled = choice != null) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.retranscribe_start))
            }
        }
    }
}
