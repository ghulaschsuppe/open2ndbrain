package de.open2ndbrain.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.ui.onboarding.OnboardingScreen
import de.open2ndbrain.ui.record.RecordScreen
import de.open2ndbrain.ui.recordings.RecordingsScreen
import de.open2ndbrain.ui.settings.SettingsScreen
import de.open2ndbrain.ui.theme.O2bTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settingsRepo = (application as O2bApp).container.settings
        setContent {
            O2bTheme {
                val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s = settings
                    when {
                        s == null -> Unit // DataStore lädt (wenige ms)
                        !s.onboardingDone -> OnboardingScreen(
                            onFinished = { scope.launch { settingsRepo.setOnboardingDone(true) } },
                            modifier = Modifier.safeDrawingPadding(),
                        )
                        else -> MainScaffold()
                    }
                }
            }
        }
    }
}

private enum class Tab(@param:StringRes val label: Int, val icon: ImageVector) {
    Record(R.string.tab_record, Icons.Filled.Mic),
    Recordings(R.string.tab_recordings, Icons.AutoMirrored.Filled.List),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
private fun MainScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Record) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Record -> RecordScreen(modifier)
            Tab.Recordings -> RecordingsScreen(modifier)
            Tab.Settings -> SettingsScreen(modifier)
        }
    }
}
