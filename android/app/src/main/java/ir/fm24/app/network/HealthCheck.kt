package ir.fm24.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface HealthResult {
    data class Ok(val latencyMs: Long) : HealthResult
    data class HttpError(val code: Int) : HealthResult
    data object DnsFailure : HealthResult
    data object Timeout : HealthResult
    data object SslFailure : HealthResult
    data object ConnectFailure : HealthResult
}

/**
 * On-demand reachability probe of the 24FM origin. It requests an existing tiny static file
 * (robots.txt, ~136 bytes) with HEAD, so no backend change is needed. It is only called after
 * a failure or a connectivity change, never on a timer.
 */
class HealthCheck(
    private val url: String = "https://24fm.ir/robots.txt",
    private val timeoutMs: Int = 8_000,
) {
    suspend fun check(): HealthResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            val latency = (System.nanoTime() - started) / 1_000_000
            if (code in 200..399) HealthResult.Ok(latency) else HealthResult.HttpError(code)
        } catch (_: UnknownHostException) {
            HealthResult.DnsFailure
        } catch (_: SocketTimeoutException) {
            HealthResult.Timeout
        } catch (_: SSLException) {
            HealthResult.SslFailure
        } catch (_: IOException) {
            HealthResult.ConnectFailure
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /** Above this round-trip time the connection is considered degraded. */
        const val SLOW_LATENCY_MS = 4_000L
    }
}
