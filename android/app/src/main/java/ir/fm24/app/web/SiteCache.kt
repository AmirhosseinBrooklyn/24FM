package ir.fm24.app.web

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import ir.fm24.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Decides whether the WebView may keep serving its cached copy of 24fm.ir.
 *
 * The site has no service worker and its CSS/JS are cached for a week, so a plain HTTP cache
 * can show an old design long after a deploy. Instead, we fingerprint the home page and its
 * core files with HEAD requests (ETag / Last-Modified / Content-Length: no bodies are
 * downloaded) and only throw the cache away when that fingerprint changes.
 *
 * Only the WebView's HTTP cache is ever cleared; cookies, localStorage and the user's
 * settings are left alone. Every failure here is silent: the worst case is "use the cache".
 */
class SiteCache(context: Context) {

    enum class Decision {
        /** Fingerprint unchanged: serve the cache as-is so the app opens fast. */
        USE_CACHE,

        /** The site changed: clear the cache and fetch the new version. */
        REFRESH,

        /** Nothing cached yet: load normally and remember the fingerprint. */
        FIRST_LOAD,

        /** Couldn't check (offline, weak network, server down): show whatever is cached. */
        UNREACHABLE,
    }

    data class Result(val decision: Decision, val fingerprint: String?)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Blocking HEAD requests can't be cancelled, so they run here, detached from the caller:
    // a timeout returns at once and a slow probe simply finishes in the background.
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Checking ─────────────────────────────────────────────────────────────────

    /** Runs the change check, bounded by [CHECK_TIMEOUT_MS] so a weak network can't stall startup. */
    suspend fun check(): Result {
        val pending = probeScope.async { fetchFingerprint() }
        val fresh = runCatching { withTimeoutOrNull(CHECK_TIMEOUT_MS) { pending.await() } }.getOrNull()
        prefs.edit { putLong(KEY_CHECKED_AT, System.currentTimeMillis()) }
        val stored = prefs.getString(KEY_FINGERPRINT, null)
        val decision = if (isOversized()) Decision.REFRESH else decide(stored, fresh)
        if (BuildConfig.DEBUG) Log.d(TAG, "decision=$decision changed=${stored != fresh} reachable=${fresh != null}")
        return Result(decision, fresh)
    }

    /** True when the last check is old enough to be worth repeating (e.g. on returning to the app). */
    fun isCheckDue(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - prefs.getLong(KEY_CHECKED_AT, 0L) >= RECHECK_INTERVAL_MS

    /** Called once a page load succeeds, so the cache now matches this fingerprint. */
    fun commit(fingerprint: String) {
        prefs.edit { putString(KEY_FINGERPRINT, fingerprint) }
    }

    /** Probes run one after another so they share a single keep-alive TLS connection. */
    private fun fetchFingerprint(): String? {
        val probes = ArrayList<String>(PATHS.size)
        for (path in PATHS) {
            // An unreachable home page, or any probe that fails outright, means "don't know".
            val result = probe(ORIGIN + path) ?: return null
            if (probes.isEmpty() && !result.startsWith("2")) return null
            probes += result
        }
        return fingerprintOf(probes)
    }

    /**
     * HEAD request summarised as "status|etag|last-modified|length", or null on network failure.
     * The connection isn't disconnected on success, so the next probe can reuse it.
     */
    private fun probe(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            listOf(
                code.toString(),
                connection.getHeaderField("ETag").orEmpty(),
                connection.getHeaderField("Last-Modified").orEmpty(),
                connection.getHeaderField("Content-Length").orEmpty(),
            ).joinToString("|")
        } catch (_: IOException) {
            connection?.disconnect() // a broken connection must not go back into the pool
            null
        }
    }

    // ── Cache size ───────────────────────────────────────────────────────────────

    /** Chromium caps its own cache, but we also reset it if it ever grows past our limit. */
    private suspend fun isOversized(): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(appContext.cacheDir, "WebView").walkBottomUp().sumOf { if (it.isFile) it.length() else 0L } }
            .getOrDefault(0L) > MAX_CACHE_BYTES
    }

    companion object {
        private const val TAG = "SiteCache"
        private const val PREFS = "site_cache"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_CHECKED_AT = "checked_at"

        private const val ORIGIN = "https://24fm.ir"

        /** The home page first, then the files that make up the site's look and player. */
        private val PATHS = listOf(
            "/",
            "/css/style.css",
            "/js/popup.js",
            "/js/player.js",
            "/js/app.js",
            "/js/analytics.js",
            "/assets/hls.min.js",
        )

        private const val CHECK_TIMEOUT_MS = 4_000L
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val RECHECK_INTERVAL_MS = 30 * 60_000L
        private const val MAX_CACHE_BYTES = 40L * 1024 * 1024

        fun decide(stored: String?, fresh: String?): Decision = when {
            fresh == null -> if (stored == null) Decision.FIRST_LOAD else Decision.UNREACHABLE
            stored == null -> Decision.FIRST_LOAD
            stored == fresh -> Decision.USE_CACHE
            else -> Decision.REFRESH
        }

        fun fingerprintOf(probes: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(probes.joinToString("\n").toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
