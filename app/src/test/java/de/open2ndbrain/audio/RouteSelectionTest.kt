package de.open2ndbrain.audio

import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSelectionTest {
    private val builtIn = AudioInputInfo(1, "Pixel (bottom)", InputType.BuiltIn)
    private val builtIn2 = AudioInputInfo(2, "Pixel (back)", InputType.BuiltIn)
    private val sco = AudioInputInfo(10, "Jabra Talk", InputType.BluetoothSco)
    private val le = AudioInputInfo(11, "Buds LE", InputType.BluetoothLe)
    private val wired = AudioInputInfo(20, "Headset", InputType.Wired)
    private val usb = AudioInputInfo(30, "USB Mic", InputType.Usb)
    private val other = AudioInputInfo(40, "Tuner", InputType.Other)

    @Test
    fun autoPrefersBluetoothThenUsbWiredBuiltIn() {
        val all = listOf(builtIn, builtIn2, wired, usb, sco, le, other)
        assertEquals(le, RouteSelection.select(MicPreference.Auto, all, true))
        assertEquals(sco, RouteSelection.select(MicPreference.Auto, all - le, true))
        assertEquals(usb, RouteSelection.select(MicPreference.Auto, all - le - sco, true))
        assertEquals(wired, RouteSelection.select(MicPreference.Auto, listOf(builtIn, wired), true))
        assertEquals(builtIn, RouteSelection.select(MicPreference.Auto, listOf(builtIn, builtIn2, other), true))
        assertNull(RouteSelection.select(MicPreference.Auto, emptyList(), true))
    }

    @Test
    fun withoutBluetoothPermissionBluetoothIsSkipped() {
        val all = listOf(builtIn, sco, le)
        assertEquals(builtIn, RouteSelection.select(MicPreference.Auto, all, bluetoothAllowed = false))
        assertEquals(
            builtIn,
            RouteSelection.select(MicPreference.Specific("Jabra Talk", InputType.BluetoothSco), all, false),
        )
    }

    @Test
    fun blockedDevicesFallBack() {
        val all = listOf(builtIn, sco, le)
        assertEquals(sco, RouteSelection.select(MicPreference.Auto, all, true, blockedIds = setOf(11)))
        assertEquals(builtIn, RouteSelection.select(MicPreference.Auto, all, true, blockedIds = setOf(10, 11)))
    }

    @Test
    fun builtInOnlyNeverPicksExternal() {
        val all = listOf(sco, le, wired, builtIn)
        assertEquals(builtIn, RouteSelection.select(MicPreference.BuiltInOnly, all, true))
        assertNull(RouteSelection.select(MicPreference.BuiltInOnly, listOf(sco), true))
    }

    @Test
    fun specificMatchesNameAndTypeElseBuiltIn() {
        val all = listOf(builtIn, sco, le, AudioInputInfo(12, "Jabra Talk", InputType.BluetoothLe))
        assertEquals(sco, RouteSelection.select(MicPreference.Specific("Jabra Talk", InputType.BluetoothSco), all, true))
        assertEquals(sco, RouteSelection.select(MicPreference.Specific("jabra talk", InputType.BluetoothSco), all, true))
        assertEquals(
            builtIn2,
            RouteSelection.select(MicPreference.Specific("Pixel (back)", InputType.BuiltIn), all + builtIn2, true),
        )
        // nicht verbunden → internes Mikro, kein anderes BT-Gerät
        assertEquals(builtIn, RouteSelection.select(MicPreference.Specific("Other", InputType.BluetoothSco), all, true))
    }
}
