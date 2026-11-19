package de.open2ndbrain.audio

import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference

/** Reine Auswahllogik des Mikrofons (ohne Android-Abhängigkeit, unit-getestet). */
internal object RouteSelection {

    /** Rangfolge für [MicPreference.Auto]: höher = bevorzugt. */
    private fun autoRank(type: InputType): Int = when (type) {
        InputType.BluetoothLe -> 5
        InputType.BluetoothSco -> 4
        InputType.Usb -> 3
        InputType.Wired -> 2
        InputType.BuiltIn -> 1
        InputType.Other -> 0
    }

    fun isBluetooth(type: InputType) = type == InputType.BluetoothSco || type == InputType.BluetoothLe

    /**
     * Wählt das gewünschte Eingabegerät.
     * @param bluetoothAllowed false, wenn BLUETOOTH_CONNECT fehlt (BT wird dann übersprungen).
     * @param blockedIds Geräte, deren Aktivierung kürzlich fehlschlug (z. B. SCO-Timeout).
     * @return gewähltes Gerät; null = Systemstandard (eingebautes Mikrofon).
     */
    fun select(
        preference: MicPreference,
        inputs: List<AudioInputInfo>,
        bluetoothAllowed: Boolean,
        blockedIds: Set<Int> = emptySet(),
    ): AudioInputInfo? {
        val usable = inputs.filter { it.id !in blockedIds && (bluetoothAllowed || !isBluetooth(it.type)) }
        val builtIn = usable.firstOrNull { it.type == InputType.BuiltIn }
        return when (preference) {
            MicPreference.BuiltInOnly -> builtIn
            MicPreference.Auto ->
                usable.filter { it.type != InputType.Other }
                    .maxByOrNull { autoRank(it.type) } // bei Gleichstand das erste (stabile Reihenfolge)
                    ?: builtIn
            is MicPreference.Specific ->
                usable.firstOrNull { it.type == preference.type && it.name == preference.productName }
                    ?: usable.firstOrNull { it.type == preference.type && it.name.equals(preference.productName, ignoreCase = true) }
                    ?: builtIn
        }
    }
}
