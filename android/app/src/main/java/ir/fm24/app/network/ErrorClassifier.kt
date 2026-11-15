package ir.fm24.app.network

/**
 * Pure mapping from low-level failures to user-facing [StatusKind]s. The key rule: a failure
 * is only reported as "no internet" when the device itself has no usable network; otherwise
 * it is attributed to DNS, TLS, the 24FM server or the stream.
 */
object ErrorClassifier {

    // android.webkit.WebViewClient.ERROR_* values (stable public API constants).
    const val WEB_ERROR_UNKNOWN = -1
    const val WEB_ERROR_HOST_LOOKUP = -2
    const val WEB_ERROR_CONNECT = -6
    const val WEB_ERROR_IO = -7
    const val WEB_ERROR_TIMEOUT = -8
    const val WEB_ERROR_FAILED_SSL_HANDSHAKE = -11

    fun fromWebError(errorCode: Int, networkAvailable: Boolean): StatusKind {
        if (!networkAvailable) return StatusKind.NO_INTERNET
        return when (errorCode) {
            WEB_ERROR_HOST_LOOKUP -> StatusKind.DNS
            WEB_ERROR_TIMEOUT -> StatusKind.TIMEOUT
            WEB_ERROR_FAILED_SSL_HANDSHAKE -> StatusKind.SSL
            WEB_ERROR_CONNECT -> StatusKind.SERVER
            else -> StatusKind.PAGE_FAILED
        }
    }

    fun fromHttpStatus(statusCode: Int): StatusKind? = when (statusCode) {
        in 500..599 -> StatusKind.SERVER
        in 400..499 -> StatusKind.PAGE_FAILED
        else -> null
    }

    /**
     * Refines a page failure with the result of the on-demand health check against the
     * 24FM origin. A healthy origin means the failure was local to that page load.
     */
    fun refineWithHealth(initial: StatusKind, health: HealthResult, networkAvailable: Boolean): StatusKind {
        if (!networkAvailable) return StatusKind.NO_INTERNET
        return when (health) {
            is HealthResult.Ok -> if (initial == StatusKind.SSL) StatusKind.SSL else StatusKind.PAGE_FAILED
            HealthResult.DnsFailure -> StatusKind.DNS
            HealthResult.SslFailure -> StatusKind.SSL
            HealthResult.Timeout -> StatusKind.TIMEOUT
            HealthResult.ConnectFailure -> StatusKind.SERVER
            is HealthResult.HttpError -> fromHttpStatus(health.code) ?: StatusKind.SERVER
        }
    }

    /** Short technical label shown in small print under friendly error messages. */
    fun webErrorLabel(errorCode: Int): String {
        val name = when (errorCode) {
            WEB_ERROR_HOST_LOOKUP -> "ERR_HOST_LOOKUP"
            WEB_ERROR_CONNECT -> "ERR_CONNECT"
            WEB_ERROR_IO -> "ERR_IO"
            WEB_ERROR_TIMEOUT -> "ERR_TIMEOUT"
            WEB_ERROR_FAILED_SSL_HANDSHAKE -> "ERR_SSL_HANDSHAKE"
            else -> "ERR_WEBVIEW"
        }
        return "$name ($errorCode)"
    }

    fun httpErrorLabel(statusCode: Int): String = "HTTP $statusCode"

    const val SSL_CERTIFICATE_LABEL = "ERR_SSL_CERTIFICATE"

    /** A stream failure is never blamed on the user's connection while the device is online. */
    fun fromPlaybackError(networkAvailable: Boolean): StatusKind =
        if (networkAvailable) StatusKind.STREAM_UNAVAILABLE else StatusKind.NO_INTERNET
}
