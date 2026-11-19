package de.open2ndbrain.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.open2ndbrain.core.AudioInputInfo
import de.open2ndbrain.core.InputType
import de.open2ndbrain.core.MicPreference

/**
 * Wählt das Mikrofon (intern / kabelgebunden / USB / Bluetooth) und hält die Liste der Eingänge aktuell.
 *
 * - Beobachtet Geräte per [AudioDeviceCallback].
 * - Bluetooth: Android 12+ über `setCommunicationDevice()`, darunter `startBluetoothSco()` und
 *   Warten auf `ACTION_SCO_AUDIO_STATE_UPDATED`. Bis BT steht und nach jedem Fehlschlag läuft die
 *   Aufnahme über das interne Mikrofon weiter; das gewünschte Gerät meldet der Router der Engine,
 *   die es per `AudioRecord.setPreferredDevice()` anwendet (ohne die Aufnahme anzuhalten).
 * - Ohne BLUETOOTH_CONNECT (Android 12+) wird BT übersprungen.
 *
 * Alle Methoden posten auf [handler] (eigener Thread der Engine); die Callbacks kommen von dort.
 */
internal class AudioRouter(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onInputsChanged(inputs: List<AudioInputInfo>)

        /** Gewünschtes Eingabegerät für `AudioRecord.setPreferredDevice` (null = Systemstandard). */
        fun onPreferredDeviceChanged(device: AudioDeviceInfo?)
    }

    private val am: AudioManager = context.getSystemService(AudioManager::class.java)
    private var preference: MicPreference = MicPreference.Auto
    private var registered = false
    private var active = false
    private val devices = LinkedHashMap<Int, AudioDeviceInfo>()

    /** Geräte-ID → gesperrt bis (elapsedRealtime). */
    private val blocked = HashMap<Int, Long>()
    private var reported: AudioDeviceInfo? = null
    private var reportedOnce = false

    private var commDeviceSet = false
    private var scoStarted = false
    private var scoConnected = false
    private var scoTargetId: Int? = null
    private var scoReceiverRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
    }

    private val scoTimeout = Runnable {
        if (scoStarted && !scoConnected) {
            Log.w(TAG, "Bluetooth-SCO nicht verbunden (Timeout) – internes Mikrofon")
            scoTargetId?.let { block(it, BLOCK_MS) }
            releaseBluetooth()
            evaluate()
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val st = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            when (st) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> if (scoStarted && !scoConnected) {
                    Log.i(TAG, "Bluetooth-SCO verbunden")
                    scoConnected = true
                    handler.removeCallbacks(scoTimeout)
                    evaluate()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (scoConnected) {
                    Log.i(TAG, "Bluetooth-SCO getrennt")
                    scoTargetId?.let { block(it, RETRY_AFTER_DISCONNECT_MS) }
                    releaseBluetooth()
                    evaluate()
                    handler.postDelayed({ evaluate() }, RETRY_AFTER_DISCONNECT_MS + 100)
                }
            }
        }
    }

    /** Beginnt die Beobachtung; [active] = Mikrofon wird gerade genutzt (BT darf aktiviert werden). */
    fun start(pref: MicPreference, active: Boolean) = handler.post {
        preference = pref
        this.active = active
        if (!registered) {
            registered = true
            am.registerAudioDeviceCallback(deviceCallback, handler) // liefert sofort alle Geräte
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) registerScoReceiver()
        }
        onDevicesChanged()
    }

    fun setPreference(pref: MicPreference) = handler.post {
        if (pref != preference) {
            preference = pref
            blocked.clear()
        }
        evaluate()
    }

    /** Bei Pause inaktiv: SCO wird freigegeben (Musik wieder in voller Qualität). */
    fun setActive(value: Boolean) = handler.post {
        active = value
        if (!value) releaseBluetooth()
        evaluate()
    }

    /** Erneut auswerten, z. B. nachdem eine Berechtigung erteilt wurde. */
    fun reevaluate() = handler.post {
        blocked.clear()
        evaluate()
    }

    fun stop() = handler.post {
        active = false
        releaseBluetooth()
        if (registered) {
            runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
            registered = false
        }
        if (scoReceiverRegistered) {
            runCatching { context.unregisterReceiver(scoReceiver) }
            scoReceiverRegistered = false
        }
        reportedOnce = false
        reported = null
    }

    private fun registerScoReceiver() {
        try {
            ContextCompat.registerReceiver(
                context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                null, handler, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            scoReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "SCO-Empfänger nicht registrierbar", t)
        }
    }

    private fun onDevicesChanged() {
        try {
            devices.clear()
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { isMicType(it.type) }
                .forEach { devices[it.id] = it }
            listener.onInputsChanged(devices.values.map { toInfo(it) })
            blocked.keys.retainAll(devices.keys)
            evaluate()
        } catch (t: Throwable) {
            Log.e(TAG, "Geräteliste nicht lesbar", t)
        }
    }

    private fun evaluate() {
        try {
            evaluateInternal()
        } catch (t: Throwable) {
            // z. B. SecurityException ohne BT-Berechtigung: nie abstürzen, internes Mikro nutzen.
            Log.e(TAG, "Mikrofonauswahl fehlgeschlagen – internes Mikrofon", t)
            runCatching { releaseBluetooth() }
            publish(null)
        }
    }

    private fun evaluateInternal() {
        val now = SystemClock.elapsedRealtime()
        blocked.entries.removeAll { it.value <= now }
        if (!registered) return
        val infos = devices.values.map { toInfo(it) }
        val target = RouteSelection.select(preference, infos, hasBluetoothPermission(), blocked.keys)
        val dev = target?.let { devices[it.id] }
        if (dev != null && target.let { RouteSelection.isBluetooth(it.type) }) {
            if (!active) {
                publish(null) // keine SCO-Verbindung, solange nicht aufgenommen wird
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activateCommDevice(dev) else activateSco(dev)
        } else {
            releaseBluetooth()
            publish(builtInOrExplicit(dev))
        }
    }

    /** Eingebautes Mikro: Systemstandard nutzen, solange nichts anderes angeschlossen ist. */
    private fun builtInOrExplicit(dev: AudioDeviceInfo?): AudioDeviceInfo? {
        if (dev == null) return null
        if (dev.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) return dev
        val onlyBuiltIn = devices.values.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return if (onlyBuiltIn) null else dev
    }

    @SuppressLint("NewApi", "InlinedApi")
    private fun activateCommDevice(input: AudioDeviceInfo) {
        val wantedType =
            if (input.type == AudioDeviceInfo.TYPE_BLE_HEADSET) AudioDeviceInfo.TYPE_BLE_HEADSET
            else AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val candidates = am.availableCommunicationDevices.filter { it.type == wantedType }
        val comm = candidates.firstOrNull { it.address.isNotEmpty() && it.address == input.address }
            ?: candidates.firstOrNull { it.productName == input.productName }
            ?: candidates.firstOrNull()
        if (comm == null) {
            Log.w(TAG, "Kein Kommunikationsgerät für ${input.productName}")
            failBluetooth(input)
            return
        }
        val ok = am.communicationDevice?.id == comm.id || am.setCommunicationDevice(comm)
        if (!ok) {
            Log.w(TAG, "setCommunicationDevice(${comm.productName}) abgelehnt")
            failBluetooth(input)
            return
        }
        if (!commDeviceSet) Log.i(TAG, "Kommunikationsgerät gesetzt: ${comm.productName}")
        commDeviceSet = true
        publish(input)
    }

    @Suppress("DEPRECATION")
    private fun activateSco(input: AudioDeviceInfo) {
        if (scoConnected && scoTargetId == input.id) {
            publish(input)
            return
        }
        if (!am.isBluetoothScoAvailableOffCall) {
            failBluetooth(input)
            return
        }
        if (!scoStarted) {
            Log.i(TAG, "Starte Bluetooth-SCO für ${input.productName}")
            scoStarted = true
            scoConnected = false
            scoTargetId = input.id
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            handler.removeCallbacks(scoTimeout)
            handler.postDelayed(scoTimeout, SCO_TIMEOUT_MS)
        }
        publish(builtInOrExplicit(null)) // bis SCO steht: Systemstandard weiter nutzen
    }

    private fun failBluetooth(input: AudioDeviceInfo) {
        block(input.id, BLOCK_MS)
        releaseBluetooth()
        evaluateInternal() // blockiertes Gerät ist ausgeschlossen → endet beim internen Mikro
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun releaseBluetooth() {
        if (commDeviceSet) {
            runCatching { am.clearCommunicationDevice() }
            commDeviceSet = false
            Log.i(TAG, "Kommunikationsgerät freigegeben")
        }
        if (scoStarted) {
            handler.removeCallbacks(scoTimeout)
            runCatching {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            scoStarted = false
            scoConnected = false
            scoTargetId = null
            Log.i(TAG, "Bluetooth-SCO beendet")
        }
    }

    private fun block(id: Int, ms: Long) {
        blocked[id] = SystemClock.elapsedRealtime() + ms
    }

    private fun publish(dev: AudioDeviceInfo?) {
        if (reportedOnce && reported?.id == dev?.id) return
        reportedOnce = true
        reported = dev
        listener.onPreferredDeviceChanged(dev)
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "O2B-Audio"
        private const val SCO_TIMEOUT_MS = 6_000L
        private const val BLOCK_MS = 60_000L
        private const val RETRY_AFTER_DISCONNECT_MS = 5_000L

        /** AudioDeviceInfo.TYPE_ECHO_REFERENCE (System-API, daher als Wert). */
        private const val TYPE_ECHO_REFERENCE = 28

        @SuppressLint("InlinedApi")
        fun isMicType(type: Int): Boolean = when (type) {
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_FM_TUNER,
            AudioDeviceInfo.TYPE_TV_TUNER,
            TYPE_ECHO_REFERENCE,
            -> false
            else -> true
        }

        @SuppressLint("InlinedApi")
        fun inputType(type: Int): InputType = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputType.BuiltIn
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputType.BluetoothSco
            AudioDeviceInfo.TYPE_BLE_HEADSET -> InputType.BluetoothLe
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> InputType.Wired
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> InputType.Usb
            else -> InputType.Other
        }

        /** Anzeigename: Produktname, beim eingebauten Mikro ergänzt um die Position (z. B. „bottom“). */
        fun displayName(dev: AudioDeviceInfo): String {
            val product = dev.productName?.toString()?.trim().orEmpty()
            val base = product.ifEmpty {
                when (inputType(dev.type)) {
                    InputType.BuiltIn -> "Internes Mikrofon"
                    InputType.BluetoothSco, InputType.BluetoothLe -> "Bluetooth-Headset"
                    InputType.Wired -> "Kabel-Headset"
                    InputType.Usb -> "USB-Mikrofon"
                    InputType.Other -> "Mikrofon (Typ ${dev.type})"
                }
            }
            val addr = dev.address.orEmpty()
            return if (dev.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && addr.isNotBlank() && !addr.contains(':')) {
                "$base ($addr)"
            } else {
                base
            }
        }

        fun toInfo(dev: AudioDeviceInfo) = AudioInputInfo(dev.id, displayName(dev), inputType(dev.type))
    }
}
