package de.open2ndbrain.ui.settings

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.StatusDot
import de.open2ndbrain.ui.models.AsrModelItem
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing

/** Zustand der experimentellen Systemerkennung (vom Gerätehersteller, offline). */
enum class SystemAsrState { Checking, Unavailable, LanguagePackMissing, Ready }

/**
 * Grobe lokale Prüfung, solange das ViewModel keinen genaueren Zustand liefert:
 * vor Android 13 oder ohne On-Device-Erkennung → nicht verfügbar, sonst „wird geprüft“.
 */
@Composable
fun rememberLocalSystemAsrState(): SystemAsrState {
    val context = LocalContext.current
    return remember {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> SystemAsrState.Unavailable
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false) -> SystemAsrState.Checking
            else -> SystemAsrState.Unavailable
        }
    }
}

/** Eigene Karte für die Systemerkennung: keine Größe, Badge „Experimentell“, Herkunftshinweis. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemAsrCard(item: AsrModelItem, state: SystemAsrState, onActivate: () -> Unit, onLanguagePack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = O2bTheme.extra
    val unavailable = state == SystemAsrState.Unavailable
    O2bCard(
        Modifier.fillMaxWidth(),
        color = if (unavailable) scheme.surfaceContainerLow else scheme.surfaceContainer,
        border = when {
            item.active -> BorderStroke(1.5.dp, scheme.primary)
            else -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f))
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Pill(
                stringResource(R.string.system_asr_experimental),
                icon = Icons.Filled.Science,
                container = scheme.tertiaryContainer,
                content = scheme.onTertiaryContainer,
            )
            if (item.active) {
                Pill(
                    stringResource(R.string.models_active),
                    leading = { StatusDot(extra.success) },
                    container = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(item.spec.displayName, style = MaterialTheme.typography.titleMedium)
        }
        Text(item.spec.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.xs))
        Text(
            stringResource(R.string.system_asr_origin),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )

        val (icon, text, tint) = when (state) {
            SystemAsrState.Checking -> Triple(Icons.Filled.HourglassTop, R.string.system_asr_checking, scheme.secondary)
            SystemAsrState.Unavailable -> Triple(Icons.Filled.Block, R.string.system_asr_unavailable, scheme.outline)
            SystemAsrState.LanguagePackMissing -> Triple(Icons.Filled.Download, R.string.system_asr_pack_missing, extra.meeting)
            SystemAsrState.Ready -> Triple(Icons.Filled.CheckCircle, R.string.system_asr_ready, extra.success)
        }
        StateRow(icon, stringResource(text), tint)

        when (state) {
            SystemAsrState.LanguagePackMissing -> FilledTonalButton(onClick = onLanguagePack, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 44.dp)) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.system_asr_pack_action))
            }
            SystemAsrState.Ready -> if (!item.active) {
                Button(onClick = onActivate, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 44.dp)) {
                    Text(stringResource(R.string.models_activate))
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun StateRow(icon: ImageVector, text: String, tint: Color) {
    Row(
        Modifier
            .padding(top = Spacing.sm)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.12f))
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
