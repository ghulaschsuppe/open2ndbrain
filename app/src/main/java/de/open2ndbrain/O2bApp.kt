package de.open2ndbrain

import android.app.Application
import de.open2ndbrain.core.AppContainer

class O2bApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
