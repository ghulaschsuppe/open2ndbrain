package de.open2ndbrain.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.open2ndbrain.BuildConfig
import de.open2ndbrain.R
import de.open2ndbrain.core.MicPreference
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.ui.speakers.SpeakersScreen
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.ui.SystemSettings
import de.open2ndbrain.ui.VendorTip
import de.open2ndbrain.ui.record.preferenceLabel

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    BackHandler(enabled = page != SettingsPage.Main) { page = SettingsPage.Main }
    when (page) {
        SettingsPage.Speakers -> SpeakersScreen(onBack = { page = SettingsPage.Main }, modifier = modifier)
        SettingsPage.Licenses -> LicensesScreen(onBack = { page = SettingsPage.Main }, modifier = modifier)
        SettingsPage.Main -> SettingsMain(modifier, vm, onOpen = { page = it })
    }
}

private enum class SettingsPage { Main, Speakers, Licenses }

@Composable
private fun SettingsMain(modifier: Modifier, vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelStates by vm.modelStates.collectAsStateWithLifecycle()
    val batteryIgnored by vm.batteryIgnored.collectAsStateWithLifecycle()
    var pendingRetention by remember { mutableStateOf<Int?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBattery() }

    val s = settings ?: return
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section(stringResource(R.string.settings_section_recording)) {
            Label(stringResource(R.string.settings_bitrate))
            AppSettings.BITRATE_OPTIONS.forEach { kbps ->
                RadioRow(
                    selected = s.bitrateKbps == kbps,
                    title = stringResource(if (kbps == AppSettings.DEFAULT_BITRATE) R.string.settings_bitrate_16 else R.string.settings_bitrate_12),
                    subtitle = stringResource(if (kbps == AppSettings.DEFAULT_BITRATE) R.string.settings_bitrate_16_desc else R.string.settings_bitrate_12_desc),
                    onClick = { vm.setBitrate(kbps) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(
                checked = s.discardSilence,
                title = stringResource(R.string.settings_discard_silence),
                subtitle = stringResource(R.string.settings_discard_silence_desc),
                onChange = vm::setDiscardSilence,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Label(stringResource(R.string.settings_mic))
            val micOptions = buildList {
                add(MicPreference.Auto)
                add(MicPreference.BuiltInOnly)
                if (s.micPreference is MicPreference.Specific) add(s.micPreference)
            }
            micOptions.forEach { pref ->
                RadioRow(
                    selected = s.micPreference == pref,
                    title = preferenceLabel(pref),
                    subtitle = when (pref) {
                        MicPreference.Auto -> stringResource(R.string.settings_mic_auto_desc)
                        MicPreference.BuiltInOnly -> stringResource(R.string.settings_mic_builtin_desc)
                        is MicPreference.Specific -> stringResource(R.string.settings_mic_specific_desc)
                    },
                    onClick = { vm.setMicPreference(pref) },
                )
            }
            Hint(stringResource(R.string.settings_mic_specific_hint))
        }

        Section(stringResource(R.string.settings_section_transcription)) {
            Label(stringResource(R.string.settings_transcription_mode))
            TranscriptionMode.entries.forEach { mode ->
                RadioRow(
                    selected = s.transcriptionMode == mode,
                    title = stringResource(
                        when (mode) {
                            TranscriptionMode.Automatic -> R.string.transcription_mode_auto
                            TranscriptionMode.ChargingOnly -> R.string.transcription_mode_charging
                            TranscriptionMode.Off -> R.string.transcription_mode_off
                        },
                    ),
                    subtitle = stringResource(
                        when (mode) {
                            TranscriptionMode.Automatic -> R.string.transcription_mode_auto_desc
                            TranscriptionMode.ChargingOnly -> R.string.transcription_mode_charging_desc
                            TranscriptionMode.Off -> R.string.transcription_mode_off_desc
                        },
                    ),
                    onClick = { vm.setTranscriptionMode(mode) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val speakerModel = modelStates[ModelCatalog.WESPEAKER_RESNET34.id] is ModelState.Installed
            SwitchRow(
                checked = s.speakerRecognition && speakerModel,
                title = stringResource(R.string.settings_speaker_recognition),
                subtitle = stringResource(
                    if (speakerModel) R.string.settings_speaker_recognition_desc else R.string.settings_speaker_recognition_no_model,
                ),
                onChange = { if (speakerModel) vm.setSpeakerRecognition(it) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Label(stringResource(R.string.settings_threads))
            AppSettings.THREAD_OPTIONS.forEach { n ->
                RadioRow(
                    selected = s.transcriptionThreads == n,
                    title = pluralStringResource(R.plurals.settings_threads_value, n, n),
                    subtitle = stringResource(if (n == AppSettings.DEFAULT_THREADS) R.string.settings_threads_fast else R.string.settings_threads_saver),
                    onClick = { vm.setThreads(n) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = { onOpen(SettingsPage.Speakers) }) { Text(stringResource(R.string.speakers_title)) }
        }

        Section(stringResource(R.string.settings_section_models)) {
            ModelsBlock(vm)
        }

        Section(stringResource(R.string.settings_section_storage)) {
            Label(stringResource(R.string.settings_retention))
            AppSettings.RETENTION_OPTIONS.forEach { days ->
                RadioRow(
                    selected = s.audioRetentionDays == days,
                    title = retentionLabel(days),
                    onClick = {
                        if (days == s.audioRetentionDays) return@RadioRow
                        if (AppSettings.retentionShortens(s.audioRetentionDays, days)) pendingRetention = days
                        else vm.setRetention(days)
                    },
                )
            }
            Hint(stringResource(R.string.settings_retention_hint))
        }

        Section(stringResource(R.string.settings_section_battery)) {
            BatteryBlock(batteryIgnored)
        }

        Section(stringResource(R.string.settings_section_about)) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_license), style = MaterialTheme.typography.bodyMedium)
            Hint(stringResource(R.string.settings_source))
            Hint(stringResource(R.string.settings_privacy))
            Hint(stringResource(R.string.legal_short))
            OutlinedButton(onClick = { onOpen(SettingsPage.Licenses) }) { Text(stringResource(R.string.licenses_title)) }
        }
    }

    pendingRetention?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text(stringResource(R.string.settings_retention_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.settings_retention_confirm_text, days, days)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setRetention(days)
                    pendingRetention = null
                }) { Text(stringResource(R.string.settings_retention_confirm_ok)) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Akku-Optimierung: Status, Ausnahme anfordern, Herstellertipps. Auch im Onboarding verwendet. */
@Composable
fun BatteryBlock(ignored: Boolean) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ignored) Icons.Filled.CheckCircle else Icons.Filled.BatteryAlert,
            contentDescription = null,
            tint = if (ignored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(if (ignored) R.string.battery_ok else R.string.battery_not_ok),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    OutlinedButton(
        onClick = {
            if (ignored) SystemSettings.openBatteryOptimizationList(context)
            else SystemSettings.requestIgnoreBatteryOptimizations(context)
        },
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(stringResource(if (ignored) R.string.battery_open_settings else R.string.battery_request))
    }
    Label(stringResource(R.string.battery_vendor_title))
    Hint(stringResource(VendorTip.current().text))
    Hint(stringResource(R.string.battery_vendor_more))
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days == AppSettings.RETENTION_UNLIMITED) stringResource(R.string.settings_retention_unlimited)
    else pluralStringResource(R.plurals.settings_retention_days, days, days)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
    }
}

@Composable
private fun SwitchRow(checked: Boolean, title: String, subtitle: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
