package de.open2ndbrain.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import de.open2ndbrain.core.ModelCatalog
import de.open2ndbrain.core.ModelState
import de.open2ndbrain.core.TranscriptionStatus
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.work.TranscriptionRunStat
import de.open2ndbrain.ui.settings.SettingsActions
import de.open2ndbrain.ui.settings.SettingsContent
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.ui.unit.dp
import de.open2ndbrain.ui.recordings.RetranscribeSheetContent
import de.open2ndbrain.ui.settings.AsrModelsContent
import de.open2ndbrain.ui.settings.LinkSetting
import de.open2ndbrain.ui.settings.ModelItemUi
import de.open2ndbrain.ui.settings.ModelsActions
import de.open2ndbrain.ui.settings.ModelsUi
import de.open2ndbrain.ui.onboarding.OnboardingScreen
import de.open2ndbrain.ui.record.RecordActions
import de.open2ndbrain.ui.record.RecordContent
import de.open2ndbrain.ui.record.RecordSamples
import de.open2ndbrain.ui.recordings.MeetingDetailContent
import de.open2ndbrain.ui.recordings.PlayerState
import de.open2ndbrain.ui.recordings.RecordingDetailContent
import de.open2ndbrain.ui.recordings.RecordingsData
import de.open2ndbrain.ui.recordings.TimelineActions
import de.open2ndbrain.ui.recordings.TimelineContent
import de.open2ndbrain.ui.speakers.SpeakersContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots der Hauptbildschirme mit Beispieldaten (nur mit O2B_SCREENSHOTS=1, siehe app/build.gradle.kts).
 * Ergebnis: docs/screenshots/<name>-hell.png / -dunkel.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi", application = Application::class)
class ScreensScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Now() = RecordContent(
        RecordSamples.recording, RecordSamples.meeting, RecordSamples.transcribing, null, RecordActions(onTranscribeNow = {}),
        modelHint = ModelCatalog.DE_FASTCONFORMER,
        runs = listOf(TranscriptionRunStat(0, "2025-11-19_12-00.ogg", 1_800_000, 252_000, 3_400, 4, "service", "Pixel 8 (Tensor G3)")),
    )

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztDunkel() = compose.snapshot("1-jetzt", dark = true) { Now() }

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztHell() = compose.snapshot("1-jetzt", dark = false) { Now() }

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztAusHell() = compose.snapshot("1-jetzt-aus", dark = false) {
        RecordContent(RecordSamples.off, null, TranscriptionStatus(), null, RecordActions(onTranscribeNow = {}))
    }

    @Composable
    private fun Timeline(data: RecordingsData = SampleData.data) = TimelineContent(
        data = data,
        player = PlayerState(file = SampleData.h14.audio, prepared = true, playing = true, positionMs = 754_000, durationMs = SampleData.h14.durationMs),
        status = RecordSamples.transcribing.copy(currentFile = SampleData.h13.audio.name),
        speakers = SampleData.directory,
        today = SampleData.today,
        actions = TimelineActions(),
        preview = { rec, _ -> SampleData.previews[rec.audio.path] },
    )

    @Test fun zeitleisteDunkel() = compose.snapshot("2-zeitleiste", dark = true) { Timeline() }

    @Test fun zeitleisteHell() = compose.snapshot("2-zeitleiste", dark = false) { Timeline() }

    @Test fun zeitleisteLeer() = compose.snapshot("2-zeitleiste-leer", dark = true) {
        Timeline(RecordingsData(emptyList(), emptyMap(), emptyMap(), emptySet(), emptyMap()))
    }

    @Composable
    private fun Detail() = RecordingDetailContent(
        recording = SampleData.h14,
        transcript = SampleData.transcript,
        hint = null,
        audioExists = true,
        speakers = SampleData.directory,
        bookmarks = listOf(SampleData.bookmark),
        player = PlayerState(file = SampleData.h14.audio, prepared = true, playing = true, positionMs = 313_000, durationMs = SampleData.h14.durationMs),
        onBack = {},
        onPlayLine = {},
        onToggle = {},
        onSeek = {},
        onBookmarkClick = {},
    )

    @Test fun transkriptDunkel() = compose.snapshot("3-transkript", dark = true) { Detail() }

    @Test fun transkriptHell() = compose.snapshot("3-transkript", dark = false) { Detail() }

    @Composable
    private fun Meeting() = MeetingDetailContent(
        meeting = SampleData.meeting,
        lines = SampleData.meetingLines,
        speakers = SampleData.directory,
        bookmarks = listOf(SampleData.bookmark),
        player = PlayerState(),
        pendingFiles = 1,
        onTranscribeNow = {},
        onBack = {},
        onEdit = {},
        onPlayLine = {},
        onToggle = {},
        onSeek = {},
        onClosePlayer = {},
    )

    @Test fun meetingDunkel() = compose.snapshot("4-meeting", dark = true) { Meeting() }

    @Test fun meetingHell() = compose.snapshot("4-meeting", dark = false) { Meeting() }

    @Test fun sprecherDunkel() = compose.snapshot("5-sprecher", dark = true) {
        SpeakersContent(SampleData.speakers, onSelect = {}, initiallyShowRare = true)
    }

    @Test fun sprecherHell() = compose.snapshot("5-sprecher", dark = false) {
        SpeakersContent(SampleData.speakers, onSelect = {}, initiallyShowRare = true)
    }

    @Test fun sprecherLeer() = compose.snapshot("5-sprecher-leer", dark = false) {
        SpeakersContent(emptyList(), onSelect = {})
    }

    @Composable
    private fun Models() = AsrModelsContent(
        ui = ModelsUi(
            asr = listOf(
                ModelItemUi(ModelCatalog.DE_FASTCONFORMER, ModelState.Installed(File("/x"))),
                ModelItemUi(ModelCatalog.CANARY_180M_DE, ModelState.Downloading(88_000_000, 207_170_046)),
                ModelItemUi(ModelCatalog.PARAKEET_V3, ModelState.Installed(File("/x"))),
                ModelItemUi(ModelCatalog.WHISPER_SMALL, ModelState.NotInstalled),
            ),
            others = listOf(
                ModelItemUi(ModelCatalog.SILERO_VAD, ModelState.Installed(File("/x"))),
                ModelItemUi(ModelCatalog.WESPEAKER_RESNET34, ModelState.NotInstalled),
            ),
            activeAsrId = ModelCatalog.DE_FASTCONFORMER.id,
        ),
        actions = ModelsActions(onActivate = {}),
        onBack = {},
    )

    @Config(qualifiers = "w400dp-h3000dp-xhdpi")
    @Test fun modelleDunkel() = compose.snapshot("8-spracherkennung", dark = true) { Models() }

    @Config(qualifiers = "w400dp-h3000dp-xhdpi")
    @Test fun modelleHell() = compose.snapshot("8-spracherkennung", dark = false) { Models() }

    @Test fun neuTranskribieren() = compose.snapshot("9-neu-transkribieren", dark = true) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            Detail()
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
            )
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
                shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(top = 20.dp)) {
                    RetranscribeSheetContent(
                        installed = listOf(ModelCatalog.DE_FASTCONFORMER, ModelCatalog.CANARY_180M_DE, ModelCatalog.PARAKEET_V3),
                        currentModelId = ModelCatalog.PARAKEET_V3.id,
                        onPick = {},
                        onOpenModels = {},
                        onCancel = {},
                    )
                }
            }
        }
    }

    @Composable
    private fun Settings() = SettingsContent(
        settings = AppSettings(),
        speakerModelInstalled = true,
        batteryIgnored = false,
        dynamicColor = false,
        actions = SettingsActions(),
        models = {
            LinkSetting(Icons.Filled.RecordVoiceOver, "Spracherkennung", "Geladen: Deutsch – schnell (FastConformer)") {}
        },
    )

    @Config(qualifiers = "w400dp-h2400dp-xhdpi")
    @Test fun einstellungenDunkel() = compose.snapshot("6-einstellungen", dark = true) { Settings() }

    @Config(qualifiers = "w400dp-h2400dp-xhdpi")
    @Test fun einstellungenHell() = compose.snapshot("6-einstellungen", dark = false) { Settings() }

    @Test fun einrichtungDunkel() = compose.snapshot("7-einrichtung", dark = true) { OnboardingScreen(onFinished = {}) }

    @Test fun einrichtungHell() = compose.snapshot("7-einrichtung", dark = false) { OnboardingScreen(onFinished = {}) }

}
