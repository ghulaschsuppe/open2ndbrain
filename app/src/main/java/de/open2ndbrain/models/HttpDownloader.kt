package de.open2ndbrain.models

import de.open2ndbrain.models.DownloadLogic.ResponseAction
import de.open2ndbrain.models.DownloadLogic.ResumePlan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Fehler mit HTTP-Status (für Retry-Entscheidungen). */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/**
 * Lädt eine Datei per HTTPS in eine `.part`-Datei und setzt abgebrochene Downloads per HTTP-Range fort.
 * Weiterleitungen (Hugging Face → CDN, GitHub → objects.githubusercontent.com) werden selbst verfolgt,
 * damit jede Station HTTPS ist und der Range-Header erhalten bleibt.
 */
class HttpDownloader(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) {
    /**
     * Lädt [url] nach [part], bis [expectedBytes] erreicht sind. [onProgress] erhält die Bytes in [part].
     * Bricht bei Coroutine-Abbruch zwischen zwei Blöcken ab (die Teildatei bleibt für die Fortsetzung erhalten).
     */
    suspend fun download(url: String, part: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        part.parentFile?.mkdirs()
        repeat(MAX_RESTARTS) {
            when (val plan = DownloadLogic.planResume(if (part.isFile) part.length() else 0L, expectedBytes)) {
                ResumePlan.VerifyOnly -> return
                is ResumePlan.Fetch -> {
                    if (plan.truncateFirst) truncate(part)
                    if (fetch(url, part, plan.from, expectedBytes, onProgress)) return
                }
            }
        }
        throw IOException("Download von ${part.name} konnte nicht fortgesetzt werden")
    }

    /** @return true = fertig (oder vollständig), false = neu beginnen. */
    private suspend fun fetch(url: String, part: File, from: Long, expected: Long, onProgress: (Long) -> Unit): Boolean {
        val conn = open(url, from)
        try {
            val action = DownloadLogic.interpretResponse(
                conn.responseCode,
                from,
                DownloadLogic.parseContentRange(conn.getHeaderField("Content-Range")),
                expected,
            )
            val append = when (action) {
                ResponseAction.Append -> true
                ResponseAction.Overwrite -> false
                ResponseAction.AlreadyComplete -> return true
                ResponseAction.RestartFromZero -> {
                    truncate(part)
                    return false
                }
                is ResponseAction.Fail -> throw HttpStatusException(action.httpCode, "HTTP ${action.httpCode} für ${part.name}")
            }
            var written = if (append) from else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (written + n > expected) throw IOException("Server liefert mehr Daten als erwartet (${part.name})")
                        out.write(buf, 0, n)
                        written += n
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_INTERVAL_NS) {
                            lastReport = now
                            onProgress(written)
                        }
                    }
                    out.fd.sync()
                }
            }
            onProgress(written)
            if (written != expected) throw IOException("Download unvollständig: $written von $expected Byte (${part.name})")
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        var uri = URI(url)
        DownloadLogic.requireHttps(uri)
        repeat(MAX_REDIRECTS) {
            val conn = uri.toURL().openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // Keine transparente Kompression: sonst stimmen Längen und Byte-Bereiche nicht.
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
            val code = conn.responseCode
            if (!DownloadLogic.isRedirect(code)) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            uri = DownloadLogic.resolveRedirect(uri, location)
        }
        throw IOException("Zu viele Weiterleitungen: $url")
    }

    private fun truncate(file: File) {
        if (file.exists()) RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

    private companion object {
        const val USER_AGENT = "open2ndbrain-model-download"
        const val BUFFER = 1 shl 16
        const val REPORT_INTERVAL_NS = 500_000_000L
        const val MAX_REDIRECTS = 10
        const val MAX_RESTARTS = 3
    }
}
