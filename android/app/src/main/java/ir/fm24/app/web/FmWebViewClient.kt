package ir.fm24.app.web

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import ir.fm24.app.network.ErrorClassifier

/** Navigation policy, error reporting and TLS enforcement for the 24FM WebView. */
class FmWebViewClient(private val callbacks: Callbacks) : WebViewClientCompat() {

    interface Callbacks {
        fun onPageStarted()
        fun onPageVisible()
        /** [failed] is true when the main page errored and WebView is showing its own error page. */
        fun onPageFinished(failed: Boolean)
        fun onMainFrameError(errorCode: Int)
        fun onMainFrameHttpError(statusCode: Int)
        fun onSslError(isMainFrame: Boolean)
        fun openExternally(url: String)
        fun onBlockedUrl()
        fun onRenderProcessGone(view: WebView): Boolean
    }

    private var mainFrameFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString()
        return when (val decision = UrlPolicy.classify(url)) {
            is UrlPolicy.Decision.Internal -> {
                if (decision.rewritten) view.loadUrl(decision.url)
                decision.rewritten
            }
            is UrlPolicy.Decision.External -> {
                // Only user-initiated top-level navigations may leave the app.
                if (request.isForMainFrame) callbacks.openExternally(decision.url)
                true
            }
            UrlPolicy.Decision.Blocked -> {
                callbacks.onBlockedUrl()
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        mainFrameFailed = false
        callbacks.onPageStarted()
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (!mainFrameFailed) callbacks.onPageVisible()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        callbacks.onPageFinished(mainFrameFailed)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceErrorCompat) {
        if (!request.isForMainFrame) return
        mainFrameFailed = true
        val code = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
            error.errorCode
        } else {
            ErrorClassifier.WEB_ERROR_UNKNOWN
        }
        callbacks.onMainFrameError(code)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) callbacks.onMainFrameHttpError(errorResponse.statusCode)
    }

    /** Certificate problems are never bypassed. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        val isMainFrame = UrlPolicy.isInternal(error.url)
        if (isMainFrame) mainFrameFailed = true
        callbacks.onSslError(isMainFrame)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        callbacks.onRenderProcessGone(view)
}
