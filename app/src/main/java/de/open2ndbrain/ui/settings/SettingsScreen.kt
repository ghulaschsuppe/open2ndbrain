package de.open2ndbrain.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.settings.TranscriptionMode
import de.open2ndbrain.ui.SystemSettings
import de.open2ndbrain.ui.VendorTip
import de.open2ndbrain.ui.components.ContentWidth
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.ScreenTitle
import de.open2ndbrain.ui.record.preferenceLabel
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing
import de.open2ndbrain.ui.theme.ThemePreferences

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    openModels: Boolean = false,
    onModelsOpened: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Main) }
    // Sprung aus dem Jetzt-Tab („Besseres Modell verfügbar“)
    LaunchedEffect(openModels) {
        if (openModels) {
            page = SettingsPage.Models
            onModelsOpened()
        }
    }
    BackHandler(enabled = page != SettingsPage.Main) { page = SettingsPage.Main }
    when (page) {
        SettingsPage.Licenses -> LicensesScreen(onBack = { page = SettingsPage.Main }, modifier = modifier)
        SettingsPage.Models -> AsrModelsScreen(onBack = { page = SettingsPage.Main }, modifier = modifier)
        SettingsPage.Main -> SettingsMain(modifier, vm, onOpen = { page = it })
    }
}

private enum class SettingsPage { Main, Licenses, Models }

@Composable
private fun SettingsMain(modifier: Modifier, vm: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelStates by vm.modelStates.collectAsStateWithLifecycle()
    val batteryIgnored by vm.batteryIgnored.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val themePrefs = remember { ThemePreferences.get(context) }
    val dynamicColor by themePrefs.dynamicColor.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshBattery() }

    val s = settings ?: return
    SettingsContent(
        settings = s,
        speakerModelInstalled = modelStates[ModelCatalog.WESPEAKER_RESNET34.id] is ModelState.Installed,
        batteryIgnored = batteryIgnored,
        dynamicColor = dynamicColor,
        actions = SettingsActions(
            onBitrate = { vm.setBitrate(it) },
            onDiscardSilence = { vm.setDiscardSilence(it) },
            onMicPreference = { vm.setMicPreference(it) },
            onTranscriptionMode = { vm.setTranscriptionMode(it) },
            onSpeakerRecognition = { vm.setSpeakerRecognition(it) },
            onThreads = { vm.setThreads(it) },
            onInterval = { vm.setInterval(it) },
            onRetention = { vm.setRetention(it) },
            onDynamicColor = themePrefs::setDynamicColor,
            onOpenLicenses = { onOpen(SettingsPage.Licenses) },
        ),
        models = { ModelsLink(vm, onOpen = { onOpen(SettingsPage.Models) }) },
        modifier = modifier,
    )
}

/** Aktionen der Einstellungsseite (zustandslos, für Previews und Screenshots). */
class SettingsActions(
    val onBitrate: (Int) -> Unit = {},
    val onDiscardSilence: (Boolean) -> Unit = {},
    val onMicPreference: (MicPreference) -> Unit = {},
    val onTranscriptionMode: (TranscriptionMode) -> Unit = {},
    val onSpeakerRecognition: (Boolean) -> Unit = {},
    val onThreads: (Int) -> Unit = {},
    val onInterval: (Int) -> Unit = {},
    val onRetention: (Int) -> Unit = {},
    val onDynamicColor: (Boolean) -> Unit = {},
    val onDenoise: (Boolean) -> Unit = {},
    val onNightEnabled: (Boolean) -> Unit = {},
    val onNightModel: (String) -> Unit = {},
    val onOpenLicenses: () -> Unit = {},
)

/** Einstellungen als gruppierte Karten mit Icons und kurzen Erklärungen. [models] = Inhalt der Gruppe „Sprachmodelle“. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    settings: AppSettings,
    speakerModelInstalled: Boolean,
    batteryIgnored: Boolean,
    dynamicColor: Boolean,
    actions: SettingsActions,
    models: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    /** Rauschfilter vor der Erkennung; null = Einstellung (noch) nicht verfügbar. */
    denoise: Boolean? = null,
    denoiserInstalled: Boolean = false,
    /** Nachtqualität; null = (noch) nicht verfügbar. */
    night: NightQualityUi? = null,
) {
    val s = settings
    var pendingRetention by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ContentWidth {
            Column {
                ScreenTitle(stringResource(R.string.tab_settings), subtitle = stringResource(R.string.settings_subtitle))
                Column(
                    Modifier.padding(horizontal = Spacing.md).padding(top = Spacing.xs, bottom = Spacing.xl),
                    verticalArrangement = GroupSpacing,
                ) {
                    // ---------------------------------------------------------------- Aufnahme
                    SettingsGroup(stringResource(R.string.settings_section_recording)) {
                        SettingHeader(Icons.Filled.HighQuality, stringResource(R.string.settings_bitrate), stringResource(R.string.settings_bitrate_desc))
                        AppSettings.BITRATE_OPTIONS.sorted().forEach { kbps ->
                            RadioOption(
                                selected = s.bitrateKbps == kbps,
                                title = bitrateTitle(kbps),
                                subtitle = bitrateDescription(kbps),
                                onClick = { actions.onBitrate(kbps) },
                            )
                        }
                        SettingDivider()
                        SwitchSetting(
                            Icons.Filled.VolumeOff,
                            stringResource(R.string.settings_discard_silence),
                            stringResource(R.string.settings_discard_silence_desc),
                            checked = s.discardSilence,
                            onChange = actions.onDiscardSilence,
                        )
                        SettingDivider()
                        SettingHeader(Icons.Filled.Mic, stringResource(R.string.settings_mic), stringResource(R.string.settings_mic_specific_hint))
                        val micOptions = buildList {
                            add(MicPreference.Auto)
                            add(MicPreference.BuiltInOnly)
                            if (s.micPreference is MicPreference.Specific) add(s.micPreference)
                        }
                        micOptions.forEach { pref ->
                            RadioOption(
                                selected = s.micPreference == pref,
                                title = preferenceLabel(pref),
                                subtitle = when (pref) {
                                    MicPreference.Auto -> stringResource(R.string.settings_mic_auto_desc)
                                    MicPreference.BuiltInOnly -> stringResource(R.string.settings_mic_builtin_desc)
                                    is MicPreference.Specific -> stringResource(R.string.settings_mic_specific_desc)
                                },
                                onClick = { actions.onMicPreference(pref) },
                            )
                        }
                    }

                    // ---------------------------------------------------------------- Transkription
                    SettingsGroup(stringResource(R.string.settings_section_transcription)) {
                        SettingHeader(Icons.Filled.Schedule, stringResource(R.string.settings_transcription_mode))
                        TranscriptionMode.entries.forEach { mode ->
                            RadioOption(
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
                                onClick = { actions.onTranscriptionMode(mode) },
                            )
                        }
                        SettingDivider()
                        val speakerModel = speakerModelInstalled
                        SwitchSetting(
                            Icons.Filled.Groups,
                            stringResource(R.string.settings_speaker_recognition),
                            stringResource(
                                if (speakerModel) R.string.settings_speaker_recognition_desc else R.string.settings_speaker_recognition_no_model,
                            ),
                            checked = s.speakerRecognition && speakerModel,
                            enabled = speakerModel,
                            onChange = { actions.onSpeakerRecognition(it) },
                        )
                        if (denoise != null) {
                            SettingDivider()
                            SwitchSetting(
                                Icons.Filled.BlurOn,
                                stringResource(R.string.settings_denoise),
                                stringResource(if (denoiserInstalled) R.string.settings_denoise_desc else R.string.settings_denoise_no_model),
                                checked = denoise && denoiserInstalled,
                                enabled = denoiserInstalled,
                                onChange = actions.onDenoise,
                            )
                        }
                        SettingDivider()
                        SettingHeader(
                            Icons.Filled.Memory,
                            stringResource(R.string.settings_threads),
                            if (s.transcriptionThreads == AppSettings.THREADS_AUTO) {
                                stringResource(R.string.settings_threads_auto, s.effectiveThreads()) + " · " +
                                    stringResource(R.string.settings_threads_fast)
                            } else {
                                stringResource(R.string.settings_threads_saver)
                            },
                        )
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 72.dp, end = Spacing.md, bottom = Spacing.xs),
                        ) {
                            AppSettings.THREAD_OPTIONS.forEachIndexed { i, n ->
                                SegmentedButton(
                                    selected = s.transcriptionThreads == n,
                                    onClick = { actions.onThreads(n) },
                                    shape = SegmentedButtonDefaults.itemShape(i, AppSettings.THREAD_OPTIONS.size),
                                ) {
                                    Text(
                                        if (n == AppSettings.THREADS_AUTO) {
                                            stringResource(R.string.settings_threads_auto_short)
                                        } else {
                                            n.toString()
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        SettingDivider()
                        SettingHeader(
                            Icons.Filled.Timelapse,
                            stringResource(R.string.settings_interval),
                            stringResource(
                                if (s.transcriptionIntervalMin == AppSettings.DEFAULT_INTERVAL_MIN) R.string.settings_interval_default else R.string.settings_interval_desc,
                            ),
                        )
                        FlowRow(
                            Modifier.padding(start = 72.dp, end = Spacing.md, bottom = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            AppSettings.INTERVAL_OPTIONS.forEach { m ->
                                val selected = s.transcriptionIntervalMin == m
                                FilterChip(
                                    selected = selected,
                                    onClick = { actions.onInterval(m) },
                                    label = { Text(stringResource(R.string.settings_interval_value, m)) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.heightIn(min = 40.dp),
                                )
                            }
                        }
                    }

                    // ---------------------------------------------------------------- Nachtqualität
                    if (night != null) {
                        SettingsGroup(stringResource(R.string.night_section)) {
                            NightQualitySetting(night, actions.onNightEnabled, actions.onNightModel)
                        }
                    }

                    // ---------------------------------------------------------------- Modelle
                    SettingsGroup(stringResource(R.string.settings_section_models)) {
                        models()
                    }

                    // ---------------------------------------------------------------- Speicher
                    SettingsGroup(stringResource(R.string.settings_section_storage)) {
                        SettingHeader(Icons.Filled.Delete, stringResource(R.string.settings_retention), stringResource(R.string.settings_retention_hint))
                        FlowRow(
                            Modifier.padding(start = 72.dp, end = Spacing.md, bottom = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            AppSettings.RETENTION_OPTIONS.forEach { days ->
                                val selected = s.audioRetentionDays == days
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (days == s.audioRetentionDays) return@FilterChip
                                        if (AppSettings.retentionShortens(s.audioRetentionDays, days)) pendingRetention = days
                                        else actions.onRetention(days)
                                    },
                                    label = { Text(retentionLabel(days)) },
                                    leadingIcon = if (selected) {
                                        { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.heightIn(min = 40.dp),
                                )
                            }
                        }
                    }

                    // ---------------------------------------------------------------- Akku
                    SettingsGroup(stringResource(R.string.settings_section_battery)) {
                        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) {
                            BatteryBlock(batteryIgnored)
                        }
                    }

                    // ---------------------------------------------------------------- Darstellung
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingsGroup(stringResource(R.string.settings_section_appearance)) {
                            SwitchSetting(
                                Icons.Filled.Palette,
                                stringResource(R.string.settings_dynamic_color),
                                stringResource(R.string.settings_dynamic_color_desc),
                                checked = dynamicColor,
                                onChange = actions.onDynamicColor,
                            )
                        }
                    }

                    // ---------------------------------------------------------------- Über
                    SettingsGroup(stringResource(R.string.settings_section_about)) {
                        SettingHeader(
                            Icons.Filled.Shield,
                            stringResource(R.string.settings_privacy_title),
                            stringResource(R.string.settings_privacy),
                        )
                        SettingDivider()
                        SettingHeader(
                            Icons.Filled.Gavel,
                            stringResource(R.string.settings_legal_title),
                            stringResource(R.string.legal_short),
                            tint = O2bTheme.extra.meeting,
                        )
                        SettingDivider()
                        SettingHeader(
                            Icons.Filled.Code,
                            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                            stringResource(R.string.settings_license) + "\n" + stringResource(R.string.settings_source),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        SettingDivider()
                        LinkSetting(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.licenses_title)) { actions.onOpenLicenses() }
                    }
                }
            }
        }
    }

    pendingRetention?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_retention_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.settings_retention_confirm_text, days, days)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRetention(days)
                    pendingRetention = null
                }) { Text(stringResource(R.string.settings_retention_confirm_ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Akku-Optimierung: Status, Ausnahme anfordern, Herstellertipps. Auch im Onboarding verwendet. */
@Composable
fun BatteryBlock(ignored: Boolean) {
    val context = LocalContext.current
    val ok = O2bTheme.extra.success
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(
            if (ignored) Icons.Filled.CheckCircle else Icons.Filled.BatteryAlert,
            contentDescription = null,
            tint = if (ignored) ok else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(if (ignored) R.string.battery_ok else R.string.battery_not_ok),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    val action: @Composable () -> Unit = {
        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(stringResource(if (ignored) R.string.battery_open_settings else R.string.battery_request))
    }
    if (ignored) {
        OutlinedButton(onClick = { SystemSettings.openBatteryOptimizationList(context) }, modifier = Modifier.padding(top = Spacing.xs)) { action() }
    } else {
        FilledTonalButton(onClick = { SystemSettings.requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.padding(top = Spacing.xs)) { action() }
    }
    O2bCard(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xs),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = null,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = O2bTheme.extra.meeting, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.battery_vendor_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(VendorTip.current().text), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.battery_vendor_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days == AppSettings.RETENTION_UNLIMITED) stringResource(R.string.settings_retention_unlimited)
    else pluralStringResource(R.plurals.settings_retention_days, days, days)


/** „24 kbit/s – Standard“ usw.; Standard ist [AppSettings.DEFAULT_BITRATE]. */
@Composable
private fun bitrateTitle(kbps: Int): String {
    val base = stringResource(R.string.settings_bitrate_value, kbps)
    return when {
        kbps == AppSettings.DEFAULT_BITRATE -> stringResource(R.string.settings_bitrate_standard, base)
        kbps <= 12 -> stringResource(R.string.settings_bitrate_saver, base)
        else -> base
    }
}

@Composable
private fun bitrateDescription(kbps: Int): String = when {
    kbps <= 12 -> stringResource(R.string.settings_bitrate_12_desc)
    kbps <= 16 -> stringResource(R.string.settings_bitrate_16_desc)
    kbps <= 24 -> stringResource(R.string.settings_bitrate_24_desc)
    else -> stringResource(R.string.settings_bitrate_32_desc)
}
