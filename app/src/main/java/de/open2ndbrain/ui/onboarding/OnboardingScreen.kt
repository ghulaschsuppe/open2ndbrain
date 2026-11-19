package de.open2ndbrain.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.ui.Formatters
import de.open2ndbrain.ui.SystemSettings
import de.open2ndbrain.ui.components.ChipRow
import de.open2ndbrain.ui.components.Illustration
import de.open2ndbrain.ui.components.O2bCard
import de.open2ndbrain.ui.components.Pill
import de.open2ndbrain.ui.components.SpotIllustration
import de.open2ndbrain.ui.settings.ALL_MODELS_BYTES
import de.open2ndbrain.ui.settings.BatteryBlock
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.Spacing

enum class OnboardingStep { Legal, Microphone, Notifications, Bluetooth, Models, Battery }

/** Schritte je nach Android-Version: Benachrichtigungen erst ab 13, Bluetooth-Verbindung ab 12. */
fun onboardingSteps(sdk: Int): List<OnboardingStep> = buildList {
    add(OnboardingStep.Legal)
    add(OnboardingStep.Microphone)
    if (sdk >= Build.VERSION_CODES.TIRAMISU) add(OnboardingStep.Notifications)
    if (sdk >= Build.VERSION_CODES.S) add(OnboardingStep.Bluetooth)
    add(OnboardingStep.Models)
    add(OnboardingStep.Battery)
}

/** Einmalige Einrichtung: Willkommen + Rechtshinweis, dann Berechtigungen nacheinander. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val steps = remember { onboardingSteps(Build.VERSION.SDK_INT) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    // Wird bei ON_RESUME hochgezählt, damit Berechtigungsstatus nach Rückkehr aus den Einstellungen neu gelesen wird.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val micGranted = remember(resumeTick, index) { SystemSettings.hasMic(context) }
    val notificationsGranted = remember(resumeTick, index) { SystemSettings.hasNotifications(context) }
    val bluetoothGranted = remember(resumeTick, index) { SystemSettings.hasBluetooth(context) }
    val batteryIgnored = remember(resumeTick, index) { SystemSettings.isIgnoringBatteryOptimizations(context) }

    fun next() {
        if (index < steps.lastIndex) index++ else onFinished()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }

    Column(modifier.fillMaxSize()) {
        ProgressDots(count = steps.size, current = index, modifier = Modifier.padding(top = Spacing.md))
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 * dir } + fadeOut())
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { i ->
            when (steps[i]) {
                OnboardingStep.Legal -> LegalStep(onAccept = ::next)

                OnboardingStep.Microphone -> PermissionStep(
                    illustration = Illustration.Microphone,
                    title = stringResource(R.string.onb_mic_title),
                    text = stringResource(R.string.onb_mic_text),
                    granted = micGranted,
                    optional = false,
                    onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onNext = ::next,
                )

                OnboardingStep.Notifications -> PermissionStep(
                    illustration = Illustration.Notifications,
                    title = stringResource(R.string.onb_notif_title),
                    text = stringResource(R.string.onb_notif_text),
                    granted = notificationsGranted,
                    optional = false,
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onNext = ::next,
                )

                OnboardingStep.Bluetooth -> PermissionStep(
                    illustration = Illustration.Bluetooth,
                    title = stringResource(R.string.onb_bt_title),
                    text = stringResource(R.string.onb_bt_text),
                    granted = bluetoothGranted,
                    optional = true,
                    onRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                    onNext = ::next,
                )

                OnboardingStep.Models -> ModelsStep(onNext = ::next)

                OnboardingStep.Battery -> StepLayout(
                    illustration = Illustration.Battery,
                    title = stringResource(R.string.onb_battery_title),
                    text = stringResource(R.string.onb_battery_text),
                    actions = {
                        Button(onClick = ::next, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(stringResource(R.string.onb_finish))
                        }
                    },
                ) {
                    O2bCard(Modifier.fillMaxWidth()) { BatteryBlock(ignored = batteryIgnored) }
                }
            }
        }
    }
}

/** Fortschrittspunkte; der aktuelle Schritt ist ein breiter Balken. */
@Composable
fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.onb_progress, current + 1, count)
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 24.dp else 8.dp, label = "dotWidth")
            val color by animateColorAsState(
                when {
                    i == current -> MaterialTheme.colorScheme.primary
                    i < current -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                label = "dotColor",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Gemeinsames Layout: Illustration, Titel, Text, eigener Inhalt; Aktionen unten. */
@Composable
private fun StepLayout(
    illustration: Illustration,
    title: String,
    text: String,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SpotIllustration(illustration, Modifier.size(184.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            content()
        }
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            content = actions,
        )
    }
}

@Composable
private fun LegalStep(onAccept: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    val extra = O2bTheme.extra
    StepLayout(
        illustration = Illustration.Notebook,
        title = stringResource(R.string.onb_welcome_title),
        text = stringResource(R.string.onb_welcome_text),
        actions = {
            Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.onb_next))
            }
        },
    ) {
        ChipRow(Modifier.fillMaxWidth()) {
            Pill(stringResource(R.string.onb_pill_offline), icon = Icons.Filled.CloudOff)
            Pill(stringResource(R.string.onb_pill_no_account), icon = Icons.Filled.PersonOff)
            Pill(stringResource(R.string.onb_pill_on_device), icon = Icons.Filled.Lock)
        }
        O2bCard(
            Modifier.fillMaxWidth(),
            color = extra.meetingContainer.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, extra.meeting.copy(alpha = 0.35f)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Gavel, contentDescription = null, tint = extra.meeting)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.onb_legal_title), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(stringResource(R.string.onb_legal_text), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.onb_legal_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs)
                    .clip(MaterialTheme.shapes.small)
                    .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { accepted = it })
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = accepted, onCheckedChange = null)
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.onb_legal_accept), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun PermissionStep(
    illustration: Illustration,
    title: String,
    text: String,
    granted: Boolean,
    optional: Boolean,
    onRequest: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    StepLayout(
        illustration = illustration,
        title = title,
        text = text,
        actions = {
            if (granted) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onb_next)) }
            } else {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onb_allow)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { SystemSettings.openAppDetails(context) }) {
                        Text(stringResource(R.string.open_app_settings))
                    }
                    TextButton(onClick = onNext) {
                        Text(stringResource(if (optional) R.string.onb_skip else R.string.onb_later))
                    }
                }
            }
        },
    ) {
        if (granted) GrantedPill(stringResource(R.string.onb_granted))
    }
}

@Composable
private fun GrantedPill(text: String) {
    Pill(
        text,
        icon = Icons.Filled.CheckCircle,
        container = MaterialTheme.colorScheme.primaryContainer,
        content = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 3,
    )
}

/** Optional: Sprachmodelle gleich laden (läuft im Hintergrund weiter, kann übersprungen werden). */
@Composable
private fun ModelsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { (context.applicationContext as O2bApp).container.modelManager }
    var started by rememberSaveable { mutableStateOf(false) }
    StepLayout(
        illustration = Illustration.Models,
        title = stringResource(R.string.onb_models_title),
        text = stringResource(R.string.onb_models_text, Formatters.bytes(ALL_MODELS_BYTES)),
        actions = {
            if (started) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.onb_next)) }
            } else {
                Button(onClick = {
                    manager.downloadAll(unmeteredOnly = true)
                    started = true
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.models_download_wifi)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        manager.downloadAll(unmeteredOnly = false)
                        started = true
                    }) { Text(stringResource(R.string.models_download_mobile)) }
                    TextButton(onClick = onNext) { Text(stringResource(R.string.onb_skip)) }
                }
            }
        },
    ) {
        if (started) GrantedPill(stringResource(R.string.onb_models_started))
    }
}
