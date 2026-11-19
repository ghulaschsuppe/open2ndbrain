package de.open2ndbrain.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Prozessweite, interne Verbindung zwischen Engine und Repository:
 * - [version] steigt, wenn die Engine eine Datei anlegt, abschließt oder wiederherstellt.
 * - [activeFiles] enthält die gerade geschriebenen Dateien (absolute Pfade); diese werden weder
 *   gelöscht noch von der Wiederherstellung angefasst.
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
}
