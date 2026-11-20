package de.open2ndbrain.settings

import androidx.annotation.StringRes
import de.open2ndbrain.R

/** Texte zu Einstellungswerten (für die UI, damit Werte und Beschriftungen an einer Stelle zusammenpassen). */
object SettingsLabels {
    @StringRes
    fun bitrateTitle(kbps: Int): Int = when (kbps) {
        32 -> R.string.settings_bitrate_32
        24 -> R.string.settings_bitrate_24
        16 -> R.string.settings_bitrate_16
        else -> R.string.settings_bitrate_12
    }

    @StringRes
    fun bitrateDescription(kbps: Int): Int = when (kbps) {
        32 -> R.string.settings_bitrate_32_desc
        24 -> R.string.settings_bitrate_24_desc
        16 -> R.string.settings_bitrate_16_desc
        else -> R.string.settings_bitrate_12_desc
    }
}
