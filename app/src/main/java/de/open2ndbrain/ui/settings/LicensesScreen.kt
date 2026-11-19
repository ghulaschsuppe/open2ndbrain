package de.open2ndbrain.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.open2ndbrain.R
import de.open2ndbrain.core.ModelCatalog

/** Lizenzen der App, der Bibliotheken und Namensnennung der Modelle (Parakeet CC-BY-4.0 verlangt sie). */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.licenses_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LicenseCard(stringResource(R.string.app_name), stringResource(R.string.licenses_app))
            LicenseCard(stringResource(R.string.licenses_libraries_title), stringResource(R.string.licenses_libraries))
            Text(stringResource(R.string.licenses_models_title), style = MaterialTheme.typography.titleMedium)
            ModelCatalog.all.forEach { spec ->
                LicenseCard(spec.displayName, stringResource(R.string.licenses_model_entry, spec.license, spec.attribution))
            }
            Text(
                stringResource(R.string.licenses_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenseCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
