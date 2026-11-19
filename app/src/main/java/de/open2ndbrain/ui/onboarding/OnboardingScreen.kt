package de.open2ndbrain.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.open2ndbrain.R
import de.open2ndbrain.ui.SystemSettings
import de.open2ndbrain.ui.settings.BatteryBlock

enum class OnboardingStep { Legal, Microphone, Notifications, Bluetooth, Battery }

/** Schritte je nach Android-Version: Benachrichtigungen erst ab 13, Bluetooth-Verbindung ab 12. */
fun onboardingSteps(sdk: Int): List<OnboardingStep> = buildList {
    add(OnboardingStep.Legal)
    add(OnboardingStep.Microphone)
    if (sdk >= Build.VERSION_CODES.TIRAMISU) add(OnboardingStep.Notifications)
    if (sdk >= Build.VERSION_CODES.S) add(OnboardingStep.Bluetooth)
    add(OnboardingStep.Battery)
}

/** Einmalige Einrichtung: Rechtshinweis, dann Berechtigungen nacheinander. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val steps = remember { onboardingSteps(Build.VERSION.SDK_INT) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        when (step) {
            OnboardingStep.Legal -> LegalStep(onAccept = ::next)

            OnboardingStep.Microphone -> PermissionStep(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.onb_mic_title),
                text = stringResource(R.string.onb_mic_text),
                granted = micGranted,
                optional = false,
                onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onNext = ::next,
            )

            OnboardingStep.Notifications -> PermissionStep(
                icon = Icons.Filled.Notifications,
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
                icon = Icons.Filled.Bluetooth,
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

            OnboardingStep.Battery -> {
                StepHeader(Icons.Filled.BatteryChargingFull, stringResource(R.string.onb_battery_title))
                Text(stringResource(R.string.onb_battery_text), style = MaterialTheme.typography.bodyLarge)
                BatteryBlock(ignored = batteryIgnored)
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::next, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_finish))
                }
            }
        }
    }
}

@Composable
private fun LegalStep(onAccept: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    StepHeader(Icons.Filled.Gavel, stringResource(R.string.onb_legal_title))
    Text(stringResource(R.string.onb_legal_text), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.onb_legal_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { accepted = it })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = accepted, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.onb_legal_accept))
    }
    Button(onClick = onAccept, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_next))
    }
}

@Composable
private fun PermissionStep(
    icon: ImageVector,
    title: String,
    text: String,
    granted: Boolean,
    optional: Boolean,
    onRequest: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    StepHeader(icon, title)
    Text(text, style = MaterialTheme.typography.bodyLarge)
    if (granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.onb_granted))
        }
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_next)) }
    } else {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_allow)) }
        TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (optional) R.string.onb_skip else R.string.onb_later))
        }
        TextButton(onClick = { SystemSettings.openAppDetails(context) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.open_app_settings))
        }
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
}
