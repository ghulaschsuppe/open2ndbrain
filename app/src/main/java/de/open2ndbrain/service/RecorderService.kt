package de.open2ndbrain.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** PLATZHALTER – wird vom App-Agenten implementiert. */
class RecorderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
