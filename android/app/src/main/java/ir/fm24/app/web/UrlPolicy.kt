package ir.fm24.app.web

import java.net.URI
import java.util.Locale

/**
 * Decides where a URL may be opened. Only HTTPS pages on the 24FM domain stay inside the
 * WebView; everything else is either handed to another app or blocked outright.
 */
object UrlPolicy {

    const val HOME_URL = "https://24fm.ir/"
    const val ORIGIN = "https://24fm.ir"

    /** Origins allowed to run the injected bridge script and talk to the native side. */
    val trustedOrigins: Set<String> = setOf("https://24fm.ir", "https://www.24fm.ir")

    private val internalHosts = setOf("24fm.ir", "www.24fm.ir")
    private val externalSchemes = setOf("https", "http", "mailto", "tel", "tg")
    private const val MAX_URL_LENGTH = 2048

    sealed interface Decision {
        /** Load inside the WebView. [url] may differ from the input (HTTP upgraded to HTTPS). */
        data class Internal(val url: String, val rewritten: Boolean) : Decision

        /** Hand to the system (browser, Telegram, mail app...). */
        data class External(val url: String) : Decision

        /** Never open (javascript:, file:, intent:, malformed...). */
        data object Blocked : Decision
    }

    fun classify(rawUrl: String?): Decision {
        if (rawUrl.isNullOrBlank() || rawUrl.length > MAX_URL_LENGTH) return Decision.Blocked
        val uri = parse(rawUrl) ?: return Decision.Blocked
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.Blocked
        val host = uri.host?.lowercase(Locale.ROOT)

        if (host != null && host in internalHosts) {
            if (uri.rawUserInfo != null) return Decision.Blocked
            return when (scheme) {
                "https" -> Decision.Internal(rawUrl, rewritten = false)
                "http" -> Decision.Internal("https" + rawUrl.substring(scheme.length), rewritten = true)
                else -> Decision.Blocked
            }
        }
        return if (scheme in externalSchemes) Decision.External(rawUrl) else Decision.Blocked
    }

    /** True when [url] is an HTTPS page on the 24FM domain. */
    fun isInternal(url: String?): Boolean =
        (classify(url) as? Decision.Internal)?.rewritten == false

    /**
     * Validates a live-stream URL received from the trusted web page before the native
     * player fetches it: HTTPS only, a real host, no embedded credentials, bounded length.
     */
    fun isAcceptableStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > MAX_URL_LENGTH) return false
        val uri = parse(url) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (_: Exception) {
        null
    }
}
