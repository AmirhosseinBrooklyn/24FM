package ir.fm24.app.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ir.fm24.app.BuildConfig
import ir.fm24.app.FmApplication
import ir.fm24.app.network.CountryCheck
import ir.fm24.app.network.CountryResult
import ir.fm24.app.network.ErrorClassifier
import ir.fm24.app.network.HealthCheck
import ir.fm24.app.network.HealthResult
import ir.fm24.app.network.NetState
import ir.fm24.app.network.StatusKind
import ir.fm24.app.playback.PlaybackSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the full-screen error view shows: a friendly message picked from [kind], plus a small code. */
data class PageFailure(val kind: StatusKind, val code: String)

/** Screen state for [MainActivity]: banners, the full-screen error view and the country notice. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Event {
        /** Connectivity returned after the page failed to load: reload it. */
        data object ReloadPage : Event
    }

    private val monitor = (app as FmApplication).connectivity
    private val healthCheck = HealthCheck()
    private val countryCheck = CountryCheck(BuildConfig.IPINFO_TOKEN)

    val net: StateFlow<NetState> = monitor.state

    private val webIssue = MutableStateFlow<StatusKind?>(null)
    private val streamIssue = MutableStateFlow<StatusKind?>(null)
    private val transient = MutableStateFlow<StatusKind?>(null)
    private var transientJob: Job? = null

    /** The single banner to show, or null. Offline always wins; otherwise highest priority. */
    val banner: StateFlow<StatusKind?> = combine(net, webIssue, streamIssue, transient) { n, web, stream, temp ->
        val persistent = if (!n.available) StatusKind.NO_INTERNET else listOfNotNull(web, stream).maxByOrNull { it.priority }
        persistent ?: temp
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pageFailure = MutableStateFlow<PageFailure?>(null)

    /** Non-null when the site couldn't be shown at all, not even from the cache. */
    val pageFailure: StateFlow<PageFailure?> = _pageFailure.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var healthJob: Job? = null
    private val rebufferTimes = ArrayDeque<Long>()

    private var countryJob: Job? = null
    private var lastCountryCheckAt = 0L
    private var vpnNoticeShown = false

    init {
        viewModelScope.launch {
            var previous: NetState? = null
            net.collect { current ->
                val before = previous
                previous = current
                if (before == null) return@collect
                if (!before.available && current.available) {
                    showTransient(StatusKind.RESTORED)
                    if (_pageFailure.value != null) _events.tryEmit(Event.ReloadPage)
                }
                if (current.available && current.networkId != before.networkId && loadingFinished) {
                    checkCountry(force = false)
                }
            }
        }
    }

    // ── Startup ────────────────────────────────────────────────────────────────────

    private var loadingFinished = false

    /** The loading screen has gone; only now is it worth asking about the user's country. */
    fun onLoadingFinished() {
        if (loadingFinished) return
        loadingFinished = true
        checkCountry(force = true)
    }

    // ── Web page events ───────────────────────────────────────────────────────────

    fun onPageVisible() {
        webIssue.value = null
        _pageFailure.value = null
    }

    fun onMainFrameError(errorCode: Int) {
        val online = net.value.available
        val kind = ErrorClassifier.fromWebError(errorCode, online)
        webIssue.value = kind
        _pageFailure.value = PageFailure(kind, ErrorClassifier.webErrorLabel(errorCode))
        if (online) refineWithHealth(kind, updatePageFailure = true)
    }

    fun onMainFrameHttpError(statusCode: Int) {
        val kind = ErrorClassifier.fromHttpStatus(statusCode) ?: return
        webIssue.value = kind
        if (kind == StatusKind.SERVER) refineWithHealth(kind, updatePageFailure = false)
    }

    fun onSslError(isMainFrame: Boolean) {
        webIssue.value = StatusKind.SSL
        if (isMainFrame) _pageFailure.value = PageFailure(StatusKind.SSL, ErrorClassifier.SSL_CERTIFICATE_LABEL)
    }

    fun onReloadRequested() {
        webIssue.value = null
    }

    private fun refineWithHealth(initial: StatusKind, updatePageFailure: Boolean) {
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            val result = healthCheck.check()
            val refined = ErrorClassifier.refineWithHealth(initial, result, net.value.available)
            if (webIssue.value != null) webIssue.value = refined
            _pageFailure.value?.let { if (updatePageFailure) _pageFailure.value = it.copy(kind = refined) }
            if (result is HealthResult.Ok && result.latencyMs > HealthCheck.SLOW_LATENCY_MS) {
                showTransient(StatusKind.DEGRADED)
            }
        }
    }

    // ── Playback events ───────────────────────────────────────────────────────────

    fun onPlayback(snapshot: PlaybackSnapshot) {
        streamIssue.value = when {
            snapshot.retrying -> StatusKind.RETRYING
            snapshot.hasError -> ErrorClassifier.fromPlaybackError(net.value.available)
            else -> null
        }
    }

    /** Several rebuffers within a minute while playing means the connection is degraded. */
    fun onRebuffer() {
        val now = SystemClock.elapsedRealtime()
        rebufferTimes.addLast(now)
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > REBUFFER_WINDOW_MS) rebufferTimes.removeFirst()
        if (rebufferTimes.size >= REBUFFER_THRESHOLD) {
            rebufferTimes.clear()
            showTransient(StatusKind.DEGRADED)
        }
    }

    // ── Country notice ────────────────────────────────────────────────────────────

    private fun checkCountry(force: Boolean) {
        if (!countryCheck.isEnabled || vpnNoticeShown || countryJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCountryCheckAt < COUNTRY_RECHECK_MS) return
        lastCountryCheckAt = now
        countryJob = viewModelScope.launch {
            val result = countryCheck.lookup()
            if (BuildConfig.DEBUG) Log.d(TAG, "Country lookup: $result")
            if (result is CountryResult.Known && !result.isIran) {
                vpnNoticeShown = true
                showTransient(StatusKind.VPN_NOTICE)
            }
        }
    }

    // ── Transient banners ──────────────────────────────────────────────────────────

    private fun showTransient(kind: StatusKind) {
        transientJob?.cancel()
        transient.value = kind
        transientJob = viewModelScope.launch {
            delay(kind.autoDismissMs)
            transient.value = null
        }
    }

    fun dismissTransient() {
        transientJob?.cancel()
        transient.value = null
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val REBUFFER_WINDOW_MS = 60_000L
        const val REBUFFER_THRESHOLD = 3
        const val COUNTRY_RECHECK_MS = 10 * 60_000L
    }
}
