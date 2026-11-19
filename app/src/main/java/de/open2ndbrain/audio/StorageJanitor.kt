package de.open2ndbrain.audio

import android.annotation.SuppressLint
import android.util.Log
import java.io.File

/**
 * Notlöschung bei knappem Speicher: Unter [lowBytes] frei werden die ältesten **abgeschlossenen**
 * Aufnahmen gelöscht, bis wieder [targetBytes] frei sind. Laufende und verwaiste `.part`-Dateien
 * bleiben unangetastet. (Die UI warnt bereits ab < 2 GB.)
 */
internal object StorageJanitor {
    const val LOW_BYTES = 1L * 1024 * 1024 * 1024
    const val TARGET_BYTES = 3L * 512 * 1024 * 1024 // 1,5 GB

    /** Rückgabe: gelöschte Audiodateien (älteste zuerst). `usableSpace` bewusst konservativ (ohne Cache). */
    @SuppressLint("UsableSpace")
    fun cleanup(
        root: File,
        freeBytes: () -> Long = { root.usableSpace },
        lowBytes: Long = LOW_BYTES,
        targetBytes: Long = TARGET_BYTES,
    ): List<File> {
        if (!root.isDirectory || freeBytes() >= lowBytes) return emptyList()
        val candidates = FileRecordingRepository(root).scan()
            .filter { !it.audio.name.endsWith(RecordingLayout.PART) && !RecordingSignals.isActive(it.audio) }
            .asReversed() // scan(): neueste zuerst
        val deleted = ArrayList<File>()
        for (rf in candidates) {
            if (freeBytes() >= targetBytes) break
            if (deleteRecordingFiles(root, rf.audio)) deleted += rf.audio
        }
        if (deleted.isNotEmpty()) {
            Log.w(TAG, "Speicher knapp: ${deleted.size} älteste Aufnahme(n) gelöscht, frei ${freeBytes() / MB} MB")
            RecordingSignals.notifyChanged()
        }
        return deleted
    }

    const val MB = 1024L * 1024
    private const val TAG = "O2B-Audio"
}
