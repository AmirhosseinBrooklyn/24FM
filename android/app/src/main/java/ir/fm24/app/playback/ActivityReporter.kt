package ir.fm24.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Reports the service's actual playback, independently of the Activity/WebView lifetime. */
class ActivityReporter(
    private val endpoint: String = "https://24fm.ir/api/live-users.php",
    private val heartbeatMs: Long = 30_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = UUID.randomUUID().toString()
    private var sequence = 0L
    private var attempt = ""
    private var wantsPlayback = false
    private var stream = ""
    private var live = false
    private var genre = ""
    private var stationLabel = ""
    private var heartbeat: Job? = null

    @Synchronized fun userIntent(wants: Boolean) {
        if (wants && !wantsPlayback) attempt = UUID.randomUUID().toString()
        wantsPlayback = wants
    }

    @Synchronized fun update(playing: Boolean, url: String, label: String, genreKey: String) {
        if ((stream != url && url.isNotEmpty()) || (playing && attempt.isEmpty())) attempt = UUID.randomUUID().toString()
        val changed = live != playing || stream != url || genre != genreKey.ifEmpty { label }
        live = playing
        stream = url
        genre = genreKey.ifEmpty { label }
        stationLabel = label
        if (changed) { val data = snapshot(); scope.launch { post(data) } }
        if (playing && heartbeat == null) {
            heartbeat = scope.launch {
                while (isActive) { delay(heartbeatMs); post(snapshot()) }
            }
        } else if (!playing) {
            heartbeat?.cancel()
            heartbeat = null
        }
    }

    @Synchronized private fun snapshot(closed: Boolean = false): String = JSONObject()
        .put("client_id", client).put("sequence", ++sequence).put("source", "android")
        .put("state", if (live && !closed) "live" else "off").put("closed", closed)
        .put("attempt_id", attempt).put("genre", genre).put("genre_label", stationLabel).toString()

    fun close() {
        heartbeat?.cancel()
        val data = snapshot(closed = true)
        scope.launch { try { post(data) } finally { scope.cancel() } }
    }

    private fun post(data: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode in 200..299) connection.inputStream.close()
            else connection.errorStream?.close()
        } catch (_: Exception) {
            // Presence expires on the server if the device cannot send another heartbeat.
        } finally { connection?.disconnect() }
    }
}
