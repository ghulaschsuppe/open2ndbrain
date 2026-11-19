package de.open2ndbrain.ui.settings

import androidx.annotation.StringRes
import de.open2ndbrain.R
import de.open2ndbrain.core.AsrBenchmark

/** Verständliche Einstufung der Messwerte (1 = schwach … 5 = sehr gut) für die Modellauswahl. */
data class Rating(val level: Int, @param:StringRes val label: Int)

object ModelRatings {
    /** Genauigkeit im Alltag aus der Wortfehlerrate unter Alltagsbedingungen. */
    fun accuracy(b: AsrBenchmark): Rating = when {
        b.werEverydayPct <= 18.0 -> Rating(5, R.string.rating_very_good)
        b.werEverydayPct <= 25.0 -> Rating(4, R.string.rating_good)
        b.werEverydayPct <= 40.0 -> Rating(3, R.string.rating_ok)
        b.werEverydayPct <= 60.0 -> Rating(2, R.string.rating_weak)
        else -> Rating(1, R.string.rating_poor)
    }

    /** Geschwindigkeit aus dem Echtzeitfaktor (kleiner = schneller). */
    fun speed(b: AsrBenchmark): Rating = when {
        b.realTimeFactor <= 0.05 -> Rating(5, R.string.rating_very_fast)
        b.realTimeFactor <= 0.10 -> Rating(4, R.string.rating_fast)
        b.realTimeFactor <= 0.20 -> Rating(3, R.string.rating_medium)
        b.realTimeFactor <= 0.30 -> Rating(2, R.string.rating_slow)
        else -> Rating(1, R.string.rating_very_slow)
    }

    const val MAX = 5
}
