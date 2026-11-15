package ir.fm24.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorClassifierTest {

    @Test
    fun `offline device always reports no internet`() {
        listOf(
            ErrorClassifier.WEB_ERROR_HOST_LOOKUP,
            ErrorClassifier.WEB_ERROR_TIMEOUT,
            ErrorClassifier.WEB_ERROR_CONNECT,
            ErrorClassifier.WEB_ERROR_FAILED_SSL_HANDSHAKE,
        ).forEach { assertEquals(StatusKind.NO_INTERNET, ErrorClassifier.fromWebError(it, networkAvailable = false)) }
        assertEquals(StatusKind.NO_INTERNET, ErrorClassifier.fromPlaybackError(networkAvailable = false))
    }

    @Test
    fun `online failures are attributed to the right layer`() {
        assertEquals(StatusKind.DNS, ErrorClassifier.fromWebError(ErrorClassifier.WEB_ERROR_HOST_LOOKUP, true))
        assertEquals(StatusKind.TIMEOUT, ErrorClassifier.fromWebError(ErrorClassifier.WEB_ERROR_TIMEOUT, true))
        assertEquals(StatusKind.SSL, ErrorClassifier.fromWebError(ErrorClassifier.WEB_ERROR_FAILED_SSL_HANDSHAKE, true))
        assertEquals(StatusKind.SERVER, ErrorClassifier.fromWebError(ErrorClassifier.WEB_ERROR_CONNECT, true))
        assertEquals(StatusKind.PAGE_FAILED, ErrorClassifier.fromWebError(ErrorClassifier.WEB_ERROR_IO, true))
        assertEquals(StatusKind.STREAM_UNAVAILABLE, ErrorClassifier.fromPlaybackError(networkAvailable = true))
    }

    @Test
    fun `http status mapping`() {
        assertEquals(StatusKind.SERVER, ErrorClassifier.fromHttpStatus(503))
        assertEquals(StatusKind.PAGE_FAILED, ErrorClassifier.fromHttpStatus(404))
        assertNull(ErrorClassifier.fromHttpStatus(200))
    }

    @Test
    fun `health check refines page failures`() {
        assertEquals(StatusKind.PAGE_FAILED, ErrorClassifier.refineWithHealth(StatusKind.SERVER, HealthResult.Ok(120), true))
        assertEquals(StatusKind.DNS, ErrorClassifier.refineWithHealth(StatusKind.SERVER, HealthResult.DnsFailure, true))
        assertEquals(StatusKind.SERVER, ErrorClassifier.refineWithHealth(StatusKind.TIMEOUT, HealthResult.HttpError(502), true))
        assertEquals(StatusKind.SERVER, ErrorClassifier.refineWithHealth(StatusKind.TIMEOUT, HealthResult.ConnectFailure, true))
        assertEquals(StatusKind.SSL, ErrorClassifier.refineWithHealth(StatusKind.SSL, HealthResult.Ok(50), true))
        assertEquals(StatusKind.NO_INTERNET, ErrorClassifier.refineWithHealth(StatusKind.SERVER, HealthResult.Timeout, false))
    }

    @Test
    fun `technical labels are readable`() {
        assertEquals("ERR_HOST_LOOKUP (-2)", ErrorClassifier.webErrorLabel(ErrorClassifier.WEB_ERROR_HOST_LOOKUP))
        assertEquals("ERR_TIMEOUT (-8)", ErrorClassifier.webErrorLabel(ErrorClassifier.WEB_ERROR_TIMEOUT))
        assertEquals("ERR_WEBVIEW (-15)", ErrorClassifier.webErrorLabel(-15))
        assertEquals("HTTP 503", ErrorClassifier.httpErrorLabel(503))
    }

    @Test
    fun `offline outranks every other banner`() {
        val highest = StatusKind.entries.maxBy { it.priority }
        assertEquals(StatusKind.NO_INTERNET, highest)
    }
}
