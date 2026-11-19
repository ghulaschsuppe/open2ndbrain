package de.open2ndbrain.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.open2ndbrain.O2bApp
import de.open2ndbrain.R
import de.open2ndbrain.ui.onboarding.OnboardingScreen
import de.open2ndbrain.ui.record.RecordScreen
import de.open2ndbrain.ui.recordings.RecordingsScreen
import de.open2ndbrain.ui.settings.SettingsScreen
import de.open2ndbrain.ui.speakers.SpeakersScreen
import de.open2ndbrain.ui.theme.O2bTheme
import de.open2ndbrain.ui.theme.ThemePreferences
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settingsRepo = (application as O2bApp).container.settings
        val themePrefs = ThemePreferences.get(this)
        setContent {
            val dynamic by themePrefs.dynamicColor.collectAsStateWithLifecycle()
            O2bTheme(dynamicColor = dynamic) {
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

private enum class Tab(@param:StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    Now(R.string.tab_now, Icons.Outlined.Mic, Icons.Filled.Mic),
    Timeline(R.string.tab_timeline, Icons.Outlined.ViewTimeline, Icons.Filled.ViewTimeline),
    Speakers(R.string.tab_speakers, Icons.Outlined.Groups, Icons.Filled.Groups),
    Settings(R.string.tab_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
}

/** Untere Navigationsleiste auf Telefonen, seitliche Leiste ab 600 dp Breite (Querformat, Tablet). */
@Composable
private fun MainScaffold() {
    var tab by rememberSaveable { mutableStateOf(Tab.Now) }
    var openModels by rememberSaveable { mutableStateOf(false) }
    val content: @Composable (Modifier) -> Unit = { m ->
        TabContent(
            tab = tab,
            openModels = openModels,
            onSelect = { tab = it },
            onOpenModels = {
                openModels = true
                tab = Tab.Settings
            },
            onModelsOpened = { openModels = false },
            modifier = m,
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label)) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
                content(
                    Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.systemBars),
                )
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(if (tab == t) t.selectedIcon else t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label), maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                content(Modifier.padding(padding).consumeWindowInsets(padding))
            }
        }
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    openModels: Boolean,
    onSelect: (Tab) -> Unit,
    onOpenModels: () -> Unit,
    onModelsOpened: () -> Unit,
    modifier: Modifier,
) {
    AnimatedContent(
        targetState = tab,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "tab",
        modifier = modifier,
    ) { t ->
        when (t) {
            Tab.Now -> RecordScreen(onOpenModels = onOpenModels)
            Tab.Timeline -> RecordingsScreen(onStartRecording = { onSelect(Tab.Now) })
            Tab.Speakers -> SpeakersScreen()
            Tab.Settings -> SettingsScreen(openModels = openModels, onModelsOpened = onModelsOpened)
        }
    }
}
