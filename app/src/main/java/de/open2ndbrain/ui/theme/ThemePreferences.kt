package de.open2ndbrain.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reine Darstellungs-Einstellungen (nur UI, unabhängig von den Aufnahme-Einstellungen im DataStore).
 * Bewusst klein und synchron lesbar, damit das Thema schon beim ersten Frame stimmt.
 */
class ThemePreferences private constructor(private val prefs: SharedPreferences) {
    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, false))

    /** Systemfarben (Material You) statt der Markenpalette; Standard: aus. */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, value).apply()
        _dynamicColor.value = value
    }

    companion object {
        private const val FILE = "ui_prefs"
        private const val KEY_DYNAMIC = "dynamic_color"

        @Volatile
        private var instance: ThemePreferences? = null

        fun get(context: Context): ThemePreferences =
            instance ?: synchronized(this) {
                instance ?: ThemePreferences(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                    .also { instance = it }
            }
    }
}
