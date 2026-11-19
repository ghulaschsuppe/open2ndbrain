package de.open2ndbrain.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.ui.components.ContentWidth
import de.open2ndbrain.ui.components.DetailTopBar
import de.open2ndbrain.ui.components.IconBubble
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.theme.Spacing

/** Lizenzen der App, der Bibliotheken, der Schriften und Namensnennung der Modelle (Parakeet CC-BY-4.0 verlangt sie). */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        DetailTopBar(stringResource(R.string.licenses_title), onBack = onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ContentWidth {
                Column(
                    Modifier.padding(horizontal = Spacing.md).padding(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    LicenseCard(Icons.Filled.Code, stringResource(R.string.app_name), stringResource(R.string.licenses_app))
                    LicenseCard(Icons.Filled.Widgets, stringResource(R.string.licenses_libraries_title), stringResource(R.string.licenses_libraries))
                    FontsCard()
                    SectionLabel(stringResource(R.string.licenses_models_title), modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs))
                    ModelCatalog.all.forEach { spec ->
                        LicenseCard(
                            Icons.Filled.RecordVoiceOver,
                            spec.displayName,
                            stringResource(R.string.licenses_model_entry, spec.license, spec.attribution),
                        )
                    }
                    Text(
                        stringResource(R.string.licenses_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseCard(icon: ImageVector, title: String, text: String, tint: Color = MaterialTheme.colorScheme.secondary) {
    O2bCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            IconBubble(icon, tint = tint, size = 36.dp)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Schriften (SIL OFL 1.1) mit aufklappbarem vollständigem Lizenztext. */
@Composable
private fun FontsCard() {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val text = remember(open) {
        if (open) context.resources.openRawResource(R.raw.license_ofl).bufferedReader().use { it.readText() } else ""
    }
    O2bCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.Top) {
            IconBubble(Icons.Filled.TextFields, tint = MaterialTheme.colorScheme.secondary, size = 36.dp)
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.licenses_fonts_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.licenses_fonts), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { open = !open }) {
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xxs))
                    Text(stringResource(if (open) R.string.licenses_hide_text else R.string.licenses_show_text))
                }
                if (open) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

