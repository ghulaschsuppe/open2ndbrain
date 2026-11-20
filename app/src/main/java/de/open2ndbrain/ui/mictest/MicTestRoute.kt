package de.open2ndbrain.ui.mictest

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Dünner Wrapper: verbindet [MicTestContent] (Design) mit dem [MicTestViewModel]; fragt bei Bedarf die Mikrofon-Berechtigung an. */
@Composable
fun MicTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: MicTestViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val silenceCutHint by vm.silenceCutHint.collectAsStateWithLifecycle()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.start()
    }
    val actions = remember(vm) { vm.actions(onStartFailed = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) }
    MicTestContent(ui = ui, actions = actions, onBack = onBack, modifier = modifier, error = error, silenceCutHint = silenceCutHint)
}
