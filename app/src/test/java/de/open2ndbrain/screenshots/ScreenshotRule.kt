package de.open2ndbrain.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import de.open2ndbrain.ui.theme.O2bTheme
import java.io.File

typealias ScreenshotComposeRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/**
 * Rendert [content] im App-Thema und legt das Bild als PNG unter docs/screenshots/ ab.
 * Die Uhr läuft manuell (Endlos-Animationen wie der atmende Ring würden sonst nie „ruhig“).
 */
fun ScreenshotComposeRule.snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
    mainClock.autoAdvance = false
    setContent {
        O2bTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
    repeat(30) { mainClock.advanceTimeByFrame() }
    mainClock.advanceTimeBy(1_200)
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val dir = File(System.getProperty("o2b.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    File(dir, "$name-${if (dark) "dunkel" else "hell"}.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
