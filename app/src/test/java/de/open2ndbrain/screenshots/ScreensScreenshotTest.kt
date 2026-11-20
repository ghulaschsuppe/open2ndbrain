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
import de.open2ndbrain.ui.models.AsrModelItem
import de.open2ndbrain.ui.mictest.MicSituation
import de.open2ndbrain.ui.mictest.MicTestActions
import de.open2ndbrain.ui.mictest.MicTestContent
import de.open2ndbrain.ui.mictest.MicTestHistoryEntry
import de.open2ndbrain.ui.mictest.MicTestResult
import de.open2ndbrain.ui.mictest.MicTestUi
import de.open2ndbrain.ui.mictest.WordMark
import de.open2ndbrain.ui.settings.NightProgress
import de.open2ndbrain.ui.settings.NightQualityUi
import de.open2ndbrain.ui.settings.SystemAsrState
import de.open2ndbrain.ui.settings.RetranscribeOfferContent
import de.open2ndbrain.work.Retranscription
import androidx.compose.foundation.layout.size
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
        night = NightProgress(12, 30, ModelCatalog.CANARY_180M_DE.displayName),
        runs = listOf(TranscriptionRunStat(0, "2025-11-19_12-00.ogg", 1_800_000, 252_000, 3_400, 4, "service", "Pixel 8 (Tensor G3)")),
    )

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztDunkel() = compose.snapshot("1-jetzt", dark = true) { Now() }

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztHell() = compose.snapshot("1-jetzt", dark = false) { Now() }

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztVorschlagHell() = compose.snapshot("1-jetzt-vorschlag", dark = false) {
        RecordContent(
            RecordSamples.off, null, TranscriptionStatus(), null,
            RecordActions(onTranscribeNow = {}, onMicTest = {}), modelHint = ModelCatalog.DE_FASTCONFORMER,
        )
    }

    @Config(qualifiers = "w400dp-h1240dp-xhdpi")
    @Test fun jetztTestKarteDunkel() = compose.snapshot("1-jetzt-mikrofontest", dark = true) {
        RecordContent(RecordSamples.off, null, TranscriptionStatus(), null, RecordActions(onMicTest = {}), showMicTestCard = true)
    }

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
        improvedWith = ModelCatalog.CANARY_180M_DE.displayName,
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
                AsrModelItem(ModelCatalog.DE_FASTCONFORMER, ModelState.Downloading(88_000_000, 131_663_631), 88_000_000, false, chosen = true, active = false),
                AsrModelItem(ModelCatalog.CANARY_180M_DE, ModelState.NotInstalled, 0, false, chosen = false, active = false),
                AsrModelItem(ModelCatalog.PARAKEET_V3, ModelState.Installed(File("/x")), 0, false, chosen = false, active = true),
                AsrModelItem(ModelCatalog.WHISPER_SMALL, ModelState.Installed(File("/x")), 0, false, chosen = false, active = false),
                AsrModelItem(ModelCatalog.ANDROID_SYSTEM, ModelState.NotInstalled, 0, false, chosen = false, active = false),
            ),
            others = listOf(
                ModelCatalog.SILERO_VAD to ModelState.Installed(File("/x")),
                ModelCatalog.WESPEAKER_RESNET34 to ModelState.NotInstalled,
                ModelCatalog.GTCRN_DENOISER to ModelState.NotInstalled,
            ),
            installedBytes = 1_090_000_000,
            system = SystemAsrState.LanguagePackMissing,
        ),
        actions = ModelsActions(onMicTest = {}),
        onBack = {},
    )

    private val history = listOf(
        MicTestHistoryEntry(1_763_640_000_000, MicSituation.Table, ModelCatalog.DE_FASTCONFORMER.displayName, 8.4),
        MicTestHistoryEntry(1_763_380_000_000, MicSituation.Headset, ModelCatalog.CANARY_180M_DE.displayName, 3.1),
    )

    @Test fun mikrofonTestEinleitung() = compose.snapshot("10-mikrofontest-1-einleitung", dark = true) {
        MicTestContent(MicTestUi.Intro(MicSituation.Table, history), MicTestActions(), onBack = {})
    }

    @Test fun mikrofonTestLesen() = compose.snapshot("10-mikrofontest-2-lesen", dark = false) {
        MicTestContent(
            MicTestUi.Reading(androidx.compose.ui.res.stringResource(de.open2ndbrain.R.string.mictest_text), 14_000, 0.62f, true),
            MicTestActions(), onBack = {},
        )
    }

    @Test fun mikrofonTestAuswertung() = compose.snapshot("10-mikrofontest-3-auswertung", dark = true) {
        MicTestContent(MicTestUi.Evaluating(ModelCatalog.CANARY_180M_DE.displayName, 2, 3, 0.55f), MicTestActions(), onBack = {})
    }

    private fun w(t: String, k: WordMark.Kind = WordMark.Kind.Ok) = WordMark(t, k)

    @Config(qualifiers = "w400dp-h1500dp-xhdpi")
    @Test fun mikrofonTestErgebnis() = compose.snapshot("10-mikrofontest-4-ergebnis", dark = false) {
        val words = listOf(
            w("Am"), w("Dienstag"), w("treffen"), w("wir"), w("uns"), w("um"), w("halb"), w("zehn"), w("im"), w("Büro"),
            w("in"), w("der"), w("Linden", WordMark.Kind.Wrong), w("Straße", WordMark.Kind.Extra), w("Frau"), w("Schröder", WordMark.Kind.Wrong),
            w("bringt"), w("die"), w("Unterlagen"), w("für"), w("das"), w("neue", WordMark.Kind.Missing), w("Projekt"), w("mit"),
        )
        MicTestContent(
            MicTestUi.Result(
                results = listOf(
                    MicTestResult(ModelCatalog.DE_FASTCONFORMER, 8.4, 900, 31_000, words, active = true),
                    MicTestResult(ModelCatalog.CANARY_180M_DE, 4.2, 2_700, 31_000, words),
                    MicTestResult(ModelCatalog.PARAKEET_V3, 27.0, 5_200, 31_000, words),
                ),
                bestId = ModelCatalog.CANARY_180M_DE.id,
                situation = MicSituation.Table,
                history = history,
                openId = ModelCatalog.DE_FASTCONFORMER.id,
            ),
            MicTestActions(), onBack = {},
        )
    }

    @Test fun neuErstellenAngebot() = compose.snapshot("8-neu-erstellen-angebot", dark = false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                    androidx.compose.material3.Text("Ältere Transkripte neu erstellen?", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                    androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.size(16.dp))
                    RetranscribeOfferContent(
                        modelName = ModelCatalog.DE_FASTCONFORMER.displayName,
                        days = 7,
                        onDays = {},
                        estimate = Retranscription.Estimate(files = 23, audioMs = 9L * 3_600_000 + 20 * 60_000, estimatedMs = 26 * 60_000),
                    )
                }
            }
        }
    }

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
        denoise = false,
        denoiserInstalled = true,
        night = NightQualityUi(
            enabled = true,
            modelId = ModelCatalog.CANARY_180M_DE.id,
            candidates = listOf(ModelCatalog.CANARY_180M_DE, ModelCatalog.DE_FASTCONFORMER),
            progress = NightProgress(12, 30, ModelCatalog.CANARY_180M_DE.displayName),
        ),
        models = {
            LinkSetting(Icons.Filled.RecordVoiceOver, "Spracherkennung", "Geladen: Deutsch – schnell (FastConformer)") {}
        },
    )

    @Config(qualifiers = "w400dp-h3200dp-xhdpi")
    @Test fun einstellungenDunkel() = compose.snapshot("6-einstellungen", dark = true) { Settings() }

    @Config(qualifiers = "w400dp-h3200dp-xhdpi")
    @Test fun einstellungenHell() = compose.snapshot("6-einstellungen", dark = false) { Settings() }

    @Test fun einrichtungDunkel() = compose.snapshot("7-einrichtung", dark = true) { OnboardingScreen(onFinished = {}) }

    @Test fun einrichtungHell() = compose.snapshot("7-einrichtung", dark = false) { OnboardingScreen(onFinished = {}) }

}
