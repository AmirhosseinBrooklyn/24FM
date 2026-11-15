package ir.fm24.app.web

import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import ir.fm24.app.playback.PlaybackSnapshot

/**
 * Connects the website's player to the native player. The page script (assets/bridge.js) is
 * injected at document start and the message channel is registered with an origin allowlist,
 * so neither is ever exposed to other sites or to iframes. Unlike addJavascriptInterface,
 * nothing is reachable through reflection.
 */
class WebBridge(
    private val webView: WebView,
    private val onMessage: (BridgeMessage) -> Unit,
) {
    private var replyProxy: JavaScriptReplyProxy? = null
    private var scriptHandler: ScriptHandler? = null

    /** Returns false (and installs nothing) on WebView builds without the required features. */
    fun install(script: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return false
        }
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT_NAME, UrlPolicy.trustedOrigins) { _, message, sourceOrigin, isMainFrame, proxy ->
            // Defense in depth: the allowlist already restricts this to the 24FM origin.
            if (!isMainFrame || sourceOrigin.toString() !in UrlPolicy.trustedOrigins) return@addWebMessageListener
            val parsed = BridgeProtocol.parse(message.data) ?: return@addWebMessageListener
            replyProxy = proxy
            onMessage(parsed)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, UrlPolicy.trustedOrigins)
        }
        return scriptHandler != null
    }

    /** A new document is loading; its script will say hello with a fresh reply channel. */
    fun onNavigation() {
        replyProxy = null
    }

    fun push(snapshot: PlaybackSnapshot) {
        val proxy = replyProxy ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        proxy.postMessage(BridgeProtocol.stateMessage(snapshot.state, snapshot.url, snapshot.label))
    }

    fun release() {
        scriptHandler?.remove()
        scriptHandler = null
        replyProxy = null
    }

    private companion object {
        const val JS_OBJECT_NAME = "FM24Native"
    }
}
