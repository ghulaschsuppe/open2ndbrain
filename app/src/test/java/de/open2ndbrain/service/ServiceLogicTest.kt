package de.open2ndbrain.service

import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.EngineState
import de.open2ndbrain.core.EngineStatus
import de.open2ndbrain.core.InputType
import de.open2ndbrain.settings.AppSettings
import de.open2ndbrain.ui.VendorTip
import de.open2ndbrain.ui.onboarding.OnboardingStep
import de.open2ndbrain.ui.onboarding.onboardingSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLogicTest {
    private val running = ServiceStatus(running = true)

    @Test
    fun displayStatusMapping() {
        assertEquals(DisplayStatus.Off, displayStatus(EngineState(status = EngineStatus.Recording), ServiceStatus()))
        assertEquals(DisplayStatus.Starting, displayStatus(EngineState(), running))
        assertEquals(DisplayStatus.Recording, displayStatus(EngineState(status = EngineStatus.Recording), running))
        assertEquals(DisplayStatus.MicBusy, displayStatus(EngineState(status = EngineStatus.MicBusy), running))
        assertEquals(DisplayStatus.Error, displayStatus(EngineState(status = EngineStatus.Error), running))
        // Nutzer-Pause hat Vorrang vor dem Engine-Status
        assertEquals(DisplayStatus.Paused, displayStatus(EngineState(status = EngineStatus.Recording), running.copy(pausedUntil = 5)))
        assertEquals(DisplayStatus.Stopping, displayStatus(EngineState(), running.copy(stopping = true)))
        // Gespeicherte Pause ohne laufenden Dienst (Neustart unter Android 14+)
        assertEquals(DisplayStatus.Paused, displayStatus(EngineState(), ServiceStatus(), pausedBySettings = true))
        assertEquals(DisplayStatus.Off, displayStatus(EngineState(), ServiceStatus(), pausedBySettings = false))
    }

    @Test
    fun storageFullDetection() {
        assertTrue(isStorageFull("Speicher voll"))
        assertTrue(isStorageFull("Speicher voll – 120 MB frei"))
        assertFalse(isStorageFull("Mikrofon im Hintergrund gesperrt"))
        assertFalse(isStorageFull(null))
    }

    @Test
    fun notificationModelRoundsToMinutes() {
        val start = 1_000_000L
        val engine = EngineState(
            status = EngineStatus.Recording,
            currentInput = AudioInputInfo(1, "Pixel Buds", InputType.BluetoothSco),
            sessionStartedAt = start,
            storedMsToday = 125_000,
            level = 0.3f,
        )
        val a = NotificationModel.from(engine, running, start + (3 * 60 + 12) * 60_000L + 10_000)
        assertEquals(192L, a.elapsedMinutes)
        assertEquals("Pixel Buds", a.inputName)
        assertEquals(2L, a.storedMinutesToday)
        // Pegel/Sprache ändern das Modell nicht → keine unnötigen Benachrichtigungs-Updates.
        val b = NotificationModel.from(engine.copy(level = 0.9f, speechActive = true), running, start + (3 * 60 + 12) * 60_000L + 50_000)
        assertEquals(a, b)
    }

    @Test
    fun notificationModelPausedAndError() {
        val paused = NotificationModel.from(EngineState(status = EngineStatus.Paused, sessionStartedAt = 0), running.copy(pausedUntil = 99), 1000)
        assertEquals(DisplayStatus.Paused, paused.status)
        assertEquals(99L, paused.pausedUntil)
        assertNull(paused.elapsedMinutes)
        val error = NotificationModel.from(EngineState(status = EngineStatus.Error, errorMessage = "kaputt"), running, 0)
        assertEquals("kaputt", error.errorMessage)
    }

    @Test
    fun bootDecision() {
        val desired = AppSettings(recordingDesired = true, onboardingDone = true)
        assertEquals(BootReceiver.BootDecision.Nothing, BootReceiver.decide(34, AppSettings(onboardingDone = true), true))
        assertEquals(BootReceiver.BootDecision.DirectStart, BootReceiver.decide(29, desired, true))
        assertEquals(BootReceiver.BootDecision.Prompt, BootReceiver.decide(29, desired, false))
        // Android 11–13: Dienst liefe, aber das Mikrofon wäre stumm → nur Hinweis.
        assertEquals(BootReceiver.BootDecision.Prompt, BootReceiver.decide(30, desired, true))
        assertEquals(BootReceiver.BootDecision.Prompt, BootReceiver.decide(33, desired, true))
        assertEquals(BootReceiver.BootDecision.Prompt, BootReceiver.decide(36, desired, true))
        assertEquals(
            BootReceiver.BootDecision.Prompt,
            BootReceiver.decide(29, desired.copy(pausedUntilEpochMs = AppSettings.PAUSED_INDEFINITELY), true),
        )
    }

    @Test
    fun onboardingStepsPerVersion() {
        assertEquals(
            listOf(OnboardingStep.Legal, OnboardingStep.Microphone, OnboardingStep.Battery),
            onboardingSteps(29),
        )
        assertEquals(
            listOf(OnboardingStep.Legal, OnboardingStep.Microphone, OnboardingStep.Bluetooth, OnboardingStep.Battery),
            onboardingSteps(31),
        )
        assertEquals(5, onboardingSteps(36).size)
    }

    @Test
    fun vendorTips() {
        assertEquals(VendorTip.Samsung, VendorTip.forManufacturer("samsung"))
        assertEquals(VendorTip.Xiaomi, VendorTip.forManufacturer("Xiaomi"))
        assertEquals(VendorTip.Huawei, VendorTip.forManufacturer("HONOR"))
        assertEquals(VendorTip.OnePlusOppo, VendorTip.forManufacturer("OnePlus"))
        assertEquals(VendorTip.Generic, VendorTip.forManufacturer("Google"))
        assertEquals(VendorTip.Generic, VendorTip.forManufacturer(null))
    }
}
