package de.open2ndbrain.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import de.open2ndbrain.R

/** Hilfen rund um Berechtigungen und System-Einstellungsseiten. */
object SystemSettings {

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasMic(context: Context) = isGranted(context, Manifest.permission.RECORD_AUDIO)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun hasBluetooth(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Fragt direkt nach der Ausnahme von der Akku-Optimierung. Für eine 24/7-Aufnahme-App ist das der
     * Kernzweck (zulässiger Anwendungsfall laut Android-Doku); Fallback ist die Übersichtsliste.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        if (!launch(context, direct)) launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openBatteryOptimizationList(context: Context) {
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** Herstellerspezifische Tipps gegen aggressives Beenden von Hintergrund-Apps. */
enum class VendorTip(@param:StringRes val text: Int) {
    Samsung(R.string.vendor_samsung),
    Xiaomi(R.string.vendor_xiaomi),
    Huawei(R.string.vendor_huawei),
    OnePlusOppo(R.string.vendor_oneplus),
    Vivo(R.string.vendor_vivo),
    Generic(R.string.vendor_generic),
    ;

    companion object {
        fun forManufacturer(manufacturer: String?): VendorTip = when (manufacturer?.trim()?.lowercase()) {
            "samsung" -> Samsung
            "xiaomi", "redmi", "poco" -> Xiaomi
            "huawei", "honor" -> Huawei
            "oneplus", "oppo", "realme" -> OnePlusOppo
            "vivo", "iqoo" -> Vivo
            else -> Generic
        }

        fun current(): VendorTip = forManufacturer(Build.MANUFACTURER)
    }
}
