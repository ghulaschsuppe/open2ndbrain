package de.open2ndbrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.open2ndbrain.ui.components.IconBubble
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.SectionLabel
import de.open2ndbrain.ui.theme.Spacing

/** Gruppe in den Einstellungen: Versal-Überschrift und eine Karte mit Zeilen. */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title, modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs))
        O2bCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.xs), content = content)
    }
}

/** Kopf einer Einstellung: Icon, Titel, kurze Erklärung, optional etwas rechts. */
@Composable
fun SettingHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tint = tint, size = 40.dp)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.sm))
            trailing()
        }
    }
}

/** Einstellung mit Schalter; die ganze Zeile ist antippbar. */
@Composable
fun SwitchSetting(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    SettingHeader(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ) { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
}

/** Einstellung, die eine Unterseite öffnet. */
@Composable
fun LinkSetting(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    SettingHeader(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = onClick),
        tint = MaterialTheme.colorScheme.secondary,
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Auswahl-Option unter einem [SettingHeader] (eingerückt auf Höhe des Titels). */
@Composable
fun RadioOption(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = Spacing.sm)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.xs, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Feine Trennlinie zwischen Einstellungen einer Gruppe. */
@Composable
fun SettingDivider() {
    HorizontalDivider(
        Modifier.padding(start = 72.dp, end = Spacing.md, top = Spacing.xxs, bottom = Spacing.xxs),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** Kurzer Hinweistext innerhalb einer Gruppe. */
@Composable
fun SettingHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 72.dp, end = Spacing.md, top = Spacing.xxs, bottom = Spacing.xs),
    )
}

/** Abstand zwischen den Gruppen. */
val GroupSpacing = Arrangement.spacedBy(Spacing.lg)
