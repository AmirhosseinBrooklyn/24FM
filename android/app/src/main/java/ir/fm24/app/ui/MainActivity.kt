package ir.fm24.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import ir.fm24.app.BuildConfig
import ir.fm24.app.R
import ir.fm24.app.network.StatusKind
import ir.fm24.app.playback.PlayerConnection
import ir.fm24.app.playback.Station
import ir.fm24.app.store.StoreInfo
import ir.fm24.app.web.BridgeMessage
import ir.fm24.app.web.FmWebViewClient
import ir.fm24.app.web.SiteCache
import ir.fm24.app.web.UrlPolicy
import ir.fm24.app.web.WebBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), FmWebViewClient.Callbacks {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var player: PlayerConnection
    private lateinit var siteCache: SiteCache

    private lateinit var webContainer: ViewGroup
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var errorView: View
    private lateinit var loadingOverlay: View
    private lateinit var loadingDisc: View
    private lateinit var banner: StatusBanner

    private var webView: WebView? = null
    private var bridge: WebBridge? = null
    private var bridgeActive = false
    private var canRefresh = true
    private var popupOpen = false
    private var refreshStartedAt = 0L

    // Smart cache: the load in flight and the fingerprint to save once it succeeds.
    private var cacheJob: Job? = null
    private var pendingFingerprint: String? = null
    private var lastDecision: SiteCache.Decision? = null

    // Loading screen state.
    private var loadingActive = false
    private var loadingShownAt = 0L
    private var discSpin: ObjectAnimator? = null

    private val backPolicy = BackPressPolicy(clock = SystemClock::elapsedRealtime)
    private val stopRefreshRunnable = Runnable { swipeRefresh.isRefreshing = false }
    private val revealFallbackRunnable = Runnable { revealSite() }

    // ── Setup ────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Our loading screen draws the same gramophone in the same spot, so the system
        // splash can leave instantly and the hand-over is invisible.
        splash.setOnExitAnimationListener { it.remove() }

        player = PlayerConnection(this)
        player.onRebuffer = { viewModel.onRebuffer() }
        siteCache = SiteCache(this)

        bindViews()
        applyInsets()
        setUpRefresh()
        setUpBackHandling()
        showLoading()
        createWebView(savedInstanceState)
        observeState()
    }

    private fun bindViews() {
        webContainer = findViewById(R.id.web_container)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        progress = findViewById(R.id.page_progress)
        errorView = findViewById(R.id.error_view)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingDisc = findViewById(R.id.loading_disc)
        banner = findViewById(R.id.status_banner)

        errorView.findViewById<MaterialButton>(R.id.error_retry).setOnClickListener {
            showLoading()
            loadSite(fromScratch = true)
        }
        banner.onRetry = { kind -> onBannerRetry(kind) }
        banner.onDismissed = { kind -> if (kind.isTransient) viewModel.dismissTransient() }
    }

    /**
     * Insets go on each child rather than the root, so the loading screen can stay full-screen
     * (centred exactly like the system splash) while content keeps clear of bars and cutouts.
     */
    private fun applyInsets() {
        val root = findViewById<View>(android.R.id.content)
        val gap = resources.getDimensionPixelSize(R.dimen.banner_gap)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            swipeRefresh.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            errorView.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            progress.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top
                leftMargin = bars.left
                rightMargin = bars.right
            }
            banner.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + gap
                leftMargin = bars.left + gap
                rightMargin = bars.right + gap
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    // ── WebView ──────────────────────────────────────────────────────────────────

    private fun createWebView(savedInstanceState: Bundle?) {
        val view = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(ContextCompat.getColor(context, R.color.fm_bg_dark))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            contentDescription = getString(R.string.cd_web_content)
        }
        configureSettings(view)
        view.webViewClient = FmWebViewClient(this)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
                // The spinning disc already says "loading"; the thin bar is for later reloads.
                progress.isVisible = newProgress in 1..99 && !loadingActive
            }
        }
        view.setDownloadListener { url, _, _, _, _ ->
            if (UrlPolicy.classify(url) !is UrlPolicy.Decision.Blocked) openExternally(url)
        }

        val webBridge = WebBridge(view, ::onBridgeMessage)
        bridgeActive = webBridge.install(readAsset("bridge.js"))
        if (!bridgeActive) Log.w(TAG, "WebView lacks document-start scripts; the site will play audio itself")
        bridge = webBridge

        webContainer.addView(view)
        webView = view

        val restored = savedInstanceState?.let { view.restoreState(it) } != null
        if (!restored) loadSite(fromScratch = true)
    }

    private fun configureSettings(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true // the 24FM player is JavaScript
            domStorageEnabled = true // the site keeps the volume in localStorage
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            setSupportZoom(false)
            userAgentString = "$userAgentString 24FM-Android/${BuildConfig.VERSION_NAME} (${StoreInfo.ID})"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(view.settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            // The site is already dark; don't let WebView re-colour it.
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    private fun destroyWebView() {
        bridge?.release()
        bridge = null
        webView?.let { view ->
            webContainer.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    // ── Smart cache ──────────────────────────────────────────────────────────────

    /**
     * Every load goes through the change check first: unchanged site → serve the cache,
     * changed → clear it and fetch the new version, unreachable → show whatever is cached.
     */
    private fun loadSite(fromScratch: Boolean) {
        viewModel.onReloadRequested()
        cacheJob?.cancel()
        cacheJob = lifecycleScope.launch {
            val view = webView ?: return@launch
            applyCacheDecision(view, siteCache.check())
            if (fromScratch || view.url.isNullOrEmpty() || errorView.isVisible) {
                view.loadUrl(UrlPolicy.HOME_URL)
            } else {
                view.reload()
            }
        }
    }

    /**
     * Re-checks after the app returns from the background (at most every half hour), or right
     * after startup when the first check couldn't reach the site in time.
     */
    private fun recheckSiteIfDue(force: Boolean = false) {
        val view = webView ?: return
        if (loadingActive || errorView.isVisible || cacheJob?.isActive == true) return
        if (!force && !siteCache.isCheckDue()) return
        cacheJob = lifecycleScope.launch {
            val result = siteCache.check()
            if (result.decision == SiteCache.Decision.REFRESH) {
                applyCacheDecision(view, result)
                view.reload()
            }
        }
    }

    private fun applyCacheDecision(view: WebView, result: SiteCache.Result) {
        lastDecision = result.decision
        pendingFingerprint = null
        view.settings.cacheMode = when (result.decision) {
            SiteCache.Decision.USE_CACHE,
            SiteCache.Decision.UNREACHABLE,
            -> WebSettings.LOAD_CACHE_ELSE_NETWORK
            SiteCache.Decision.REFRESH -> {
                view.clearCache(true) // HTTP cache only: cookies and localStorage survive
                pendingFingerprint = result.fingerprint
                WebSettings.LOAD_DEFAULT
            }
            SiteCache.Decision.FIRST_LOAD -> {
                pendingFingerprint = result.fingerprint
                WebSettings.LOAD_DEFAULT
            }
        }
    }

    // ── Loading screen ───────────────────────────────────────────────────────────

    private fun showLoading() {
        revealHandlerReset()
        loadingActive = true
        loadingShownAt = SystemClock.elapsedRealtime()
        loadingOverlay.animate().cancel()
        loadingOverlay.alpha = 1f
        loadingOverlay.scaleX = 1f
        loadingOverlay.scaleY = 1f
        loadingOverlay.isVisible = true
        progress.isVisible = false
        startDiscSpin()
    }

    /** One steady, endless turn of the record; it never pauses or restarts while loading. */
    private fun startDiscSpin() {
        if (discSpin?.isStarted == true) return
        loadingDisc.doOnLayout { disc ->
            // Spin around the record's own centre (138,150 on the 288 canvas), not the view's.
            disc.pivotX = disc.width * DISC_CENTER_X
            disc.pivotY = disc.height * DISC_CENTER_Y
        }
        discSpin = ObjectAnimator.ofFloat(loadingDisc, View.ROTATION, 0f, 360f).apply {
            duration = DISC_TURN_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** The site has fully loaded (or failed): grow and fade the record away to reveal it. */
    private fun revealSite() {
        if (!loadingActive) return
        loadingActive = false
        revealHandlerReset()
        // A near-instant load would just flash the disc; let it be seen for a moment first.
        val wait = (MIN_LOADING_MS - (SystemClock.elapsedRealtime() - loadingShownAt)).coerceAtLeast(0L)
        // Scaling the whole layer is enough: its plain background just grows past the edges.
        loadingOverlay.animate()
            .scaleX(REVEAL_SCALE).scaleY(REVEAL_SCALE)
            .alpha(0f)
            .setStartDelay(wait).setDuration(REVEAL_MS).setInterpolator(REVEAL_EASING)
            .withEndAction {
                if (loadingActive) return@withEndAction
                loadingOverlay.isVisible = false
                discSpin?.cancel()
                discSpin = null
                viewModel.onLoadingFinished()
                if (lastDecision == SiteCache.Decision.UNREACHABLE) recheckSiteIfDue(force = true)
            }
            .start()
    }

    private fun revealHandlerReset() {
        loadingOverlay.removeCallbacks(revealFallbackRunnable)
    }

    // ── Bridge ───────────────────────────────────────────────────────────────────

    private fun onBridgeMessage(message: BridgeMessage) {
        when (message) {
            BridgeMessage.Hello -> bridge?.push(player.snapshot.value)
            is BridgeMessage.Play -> player.play(Station(message.url, message.label, message.genreKey))
            BridgeMessage.Pause -> player.pause()
            BridgeMessage.Stop -> player.stop()
            is BridgeMessage.Volume -> player.setVolume(message.value)
            is BridgeMessage.Ui -> {
                canRefresh = message.canRefresh
                popupOpen = message.popupOpen
            }
        }
    }

    // ── State ────────────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // The full-screen error already says what's wrong; a banner on top would only
                // repeat it and cover the error code.
                launch {
                    combine(viewModel.banner, viewModel.pageFailure) { kind, failure -> kind.takeIf { failure == null } }
                        .collect { banner.show(it) }
                }
                launch { viewModel.pageFailure.collect { showPageFailure(it) } }
                launch {
                    viewModel.events.collect { event ->
                        if (event == MainViewModel.Event.ReloadPage) loadSite(fromScratch = true)
                    }
                }
                launch {
                    player.snapshot.collect { snapshot ->
                        viewModel.onPlayback(snapshot)
                        bridge?.push(snapshot)
                    }
                }
            }
        }
    }

    // ── Errors ───────────────────────────────────────────────────────────────────

    private fun showPageFailure(failure: PageFailure?) {
        if (failure == null) {
            errorView.isVisible = false
            return
        }
        val (title, body) = when (failure.kind) {
            StatusKind.NO_INTERNET -> R.string.err_offline_title to R.string.err_offline_body
            StatusKind.DNS, StatusKind.TIMEOUT -> R.string.err_weak_title to R.string.err_weak_body
            StatusKind.SSL -> R.string.err_secure_title to R.string.err_secure_body
            StatusKind.PAGE_FAILED -> R.string.err_page_title to R.string.err_page_body
            else -> R.string.err_server_title to R.string.err_server_body
        }
        errorView.findViewById<TextView>(R.id.error_title).setText(title)
        errorView.findViewById<TextView>(R.id.error_body).setText(body)
        errorView.findViewById<TextView>(R.id.error_code).text = getString(R.string.err_code, failure.code)
        errorView.isVisible = true
        finishRefresh()
        revealSite()
    }

    private fun onBannerRetry(kind: StatusKind) {
        when (kind) {
            StatusKind.STREAM_UNAVAILABLE -> player.retry()
            else -> loadSite(fromScratch = false)
        }
    }

    // ── Pull to refresh ──────────────────────────────────────────────────────────

    private fun setUpRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.fm_amber, R.color.fm_amber_bright)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.fm_bg_light)
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val view = webView
            view == null || view.scrollY > 0 || view.canScrollVertically(-1) || !canRefresh || errorView.isVisible
        }
        swipeRefresh.setOnRefreshListener { onPullToRefresh() }
    }

    private fun onPullToRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - refreshStartedAt < MIN_REFRESH_INTERVAL_MS) return // same gesture fired twice
        refreshStartedAt = now
        val view = webView ?: run { swipeRefresh.isRefreshing = false; return }
        if (!bridgeActive) {
            // Without the bridge the page plays the audio itself, so never reload under it.
            view.evaluateJavascript("!!(window.Player && window.Player.isPlaying())") { result ->
                if (result == "true") swipeRefresh.isRefreshing = false else startRefresh()
            }
        } else {
            startRefresh()
        }
    }

    private fun startRefresh() {
        swipeRefresh.removeCallbacks(stopRefreshRunnable)
        swipeRefresh.postDelayed(stopRefreshRunnable, REFRESH_TIMEOUT_MS)
        loadSite(fromScratch = false)
    }

    private fun finishRefresh() {
        swipeRefresh.removeCallbacks(stopRefreshRunnable)
        swipeRefresh.isRefreshing = false
    }

    // ── Back navigation ──────────────────────────────────────────────────────────

    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                when {
                    popupOpen && view != null && bridgeActive -> {
                        view.evaluateJavascript("window.__fm24ClosePopup && window.__fm24ClosePopup()", null)
                        popupOpen = false
                    }
                    view != null && view.canGoBack() && !errorView.isVisible -> view.goBack()
                    else -> when (backPolicy.onRootBackPressed()) {
                        BackPressPolicy.Action.SHOW_HINT ->
                            Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                        // Finishing only closes the screen; PlaybackService keeps the radio on.
                        BackPressPolicy.Action.EXIT -> finish()
                    }
                }
            }
        })
    }

    // ── WebView callbacks ────────────────────────────────────────────────────────

    override fun onPageStarted() {
        bridge?.onNavigation()
    }

    override fun onPageVisible() {
        viewModel.onPageVisible()
        webView?.evaluateJavascript(readAsset("layout.js"), null)
        // Normally we wait for the full load; this only rescues a page whose last
        // resource never finishes, so the user isn't stuck on the loading screen.
        if (loadingActive) {
            loadingOverlay.removeCallbacks(revealFallbackRunnable)
            loadingOverlay.postDelayed(revealFallbackRunnable, STUCK_LOAD_REVEAL_MS)
        }
    }

    override fun onPageFinished(failed: Boolean) {
        finishRefresh()
        progress.isVisible = false
        if (failed) return
        pendingFingerprint?.let { siteCache.commit(it) }
        pendingFingerprint = null
        revealSite()
    }

    override fun onMainFrameError(errorCode: Int) {
        finishRefresh()
        viewModel.onMainFrameError(errorCode)
    }

    override fun onMainFrameHttpError(statusCode: Int) {
        viewModel.onMainFrameHttpError(statusCode)
    }

    override fun onSslError(isMainFrame: Boolean) {
        finishRefresh()
        viewModel.onSslError(isMainFrame)
    }

    override fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBlockedUrl() {
        Toast.makeText(this, R.string.link_blocked, Toast.LENGTH_SHORT).show()
    }

    override fun onRenderProcessGone(view: WebView): Boolean {
        // The renderer crashed or was reclaimed for memory. Rebuild the WebView rather than
        // let the whole app die; the radio keeps playing in its own service meanwhile.
        if (view != webView) return true
        destroyWebView()
        createWebView(null)
        return true
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        player.connect()
        webView?.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        recheckSiteIfDue()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Audio lives in PlaybackService, so the page's JavaScript can sleep in the background.
        if (bridgeActive) webView?.pauseTimers()
        player.release()
        CookieManager.getInstance().flush()
        backPolicy.reset()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        swipeRefresh.removeCallbacks(stopRefreshRunnable)
        loadingOverlay.removeCallbacks(revealFallbackRunnable)
        discSpin?.cancel()
        destroyWebView()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val REFRESH_TIMEOUT_MS = 15_000L
        const val MIN_REFRESH_INTERVAL_MS = 800L

        const val DISC_TURN_MS = 2_400L
        const val DISC_CENTER_X = 138f / 288f
        const val DISC_CENTER_Y = 150f / 288f
        const val MIN_LOADING_MS = 600L
        const val REVEAL_MS = 420L
        const val REVEAL_SCALE = 1.35f
        const val STUCK_LOAD_REVEAL_MS = 15_000L
        val REVEAL_EASING = PathInterpolator(0.2f, 0f, 0f, 1f) // gentle "emphasized decelerate"
    }
}
