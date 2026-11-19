package de.open2ndbrain.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prozessweite, interne Verbindung zwischen Engine und Repository:
 * - [version] steigt, wenn die Engine eine Datei anlegt, abschließt oder wiederherstellt.
 * - [activeFiles] enthält die gerade geschriebenen Dateien (absolute Pfade); diese werden weder
 *   gelöscht noch von der Wiederherstellung angefasst.
 * - [activeSessions] zählt laufende Engine-Sitzungen (start … Writer beendet). Die Wiederherstellung
 *   läuft immer unter [recoveryLock]; die Engine schreibt erst nach ihrer eigenen (gesperrten)
 *   Wiederherstellung, daher kann eine Wiederherstellung des Repositorys nie mit Schreibzugriffen kollidieren.
 */
internal object RecordingSignals {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private val active = ConcurrentHashMap.newKeySet<String>()

    val activeFiles: Set<String> get() = active.toSet()

    fun notifyChanged() {
        synchronized(this) { _version.value = _version.value + 1 }
    }

    fun markActive(vararg files: File) {
        files.forEach { active += it.absolutePath }
    }

    fun markInactive(vararg files: File) {
        files.forEach { active -= it.absolutePath }
    }

    fun isActive(file: File): Boolean = file.absolutePath in active

    private val sessions = AtomicInteger(0)
    private val recoveryLock = Any()

    val activeSessions: Int get() = sessions.get()

    fun sessionStarted() {
        sessions.incrementAndGet()
    }

    fun sessionEnded() {
        sessions.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /** Wiederherstellung durch die Engine (vor dem ersten Schreiben). */
    fun recoverForEngine(root: File, zone: ZoneId = ZoneId.systemDefault()): Int =
        synchronized(recoveryLock) { PartFileRecovery.recover(root, zone, activeFiles) }

    /** Wiederherstellung durch das Repository – nur, wenn keine Engine-Sitzung läuft. */
    fun recoverIfIdle(root: File, zone: ZoneId = ZoneId.systemDefault()): Int =
        synchronized(recoveryLock) {
            if (sessions.get() > 0) 0 else PartFileRecovery.recover(root, zone, activeFiles)
        }.also { if (it > 0) notifyChanged() }
}
