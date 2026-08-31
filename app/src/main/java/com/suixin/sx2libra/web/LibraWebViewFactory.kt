package com.suixin.sx2libra.web

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.WebTheme
import androidx.core.content.ContextCompat
import com.suixin.sx2libra.R
import java.io.IOException

/** Listener seam for bridge integrations owned by media/upload workers. */
fun interface LibraWebMessageListener {
    fun onMessage(
        view: WebView,
        message: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    )
}

/** Listener seam for the value-only current page theme channel. */
fun interface LibraWebThemeListener {
    fun onThemeChanged(view: WebView, theme: WebTheme)
}

/**
 * Creates every application WebView with the same security and cookie profile.
 * The factory never loads a page; the owning Activity is responsible for one
 * initial load (or restoreState) only.
 */
class LibraWebViewFactory(
    private val routePolicy: RoutePolicy = RoutePolicy(),
) {
    fun create(
        context: Context,
        messageListener: LibraWebMessageListener? = null,
        themeListener: LibraWebThemeListener? = null,
    ): WebView {
        val webView = WebView(context)
        configure(webView)
        installBridge(webView, messageListener, themeListener)
        return webView
    }

    fun configure(webView: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            // Keep the normal system UA for compatibility and add a stable
            // app-shell marker for the forum's mobile navigation suppression.
            if (!userAgentString.contains(APP_UA_MARKER)) {
                userAgentString = "$userAgentString $APP_UA_MARKER"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }
        }
        webView.setBackgroundColor(ContextCompat.getColor(webView.context, R.color.surface_background))
        val isDebuggable = (webView.context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
    }

    /**
     * Installs document-start navigation interception and the restricted
     * AndroidX WebMessage entry point when the current WebView implementation
     * supports them.  No generic JavascriptInterface is exposed.
     */
    fun installBridge(
        webView: WebView,
        messageListener: LibraWebMessageListener?,
        themeListener: LibraWebThemeListener? = null,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = loadBridgeScript(webView.context) ?: return
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            script,
            setOf(AuthContract.SITE_ORIGIN),
        )

        if (
            (messageListener != null || themeListener != null) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            WebViewCompat.addWebMessageListener(
                webView,
                JS_OBJECT_NAME,
                setOf(AuthContract.SITE_ORIGIN),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        val text = message.data ?: return
                        if (WebThemeProtocol.isThemeMessage(text)) {
                            if (themeListener != null) {
                                WebThemeProtocol.parse(
                                    rawMessage = text,
                                    source = BridgeSource(
                                        sourceOrigin = sourceOrigin.toString(),
                                        isMainFrame = isMainFrame,
                                        currentUrl = view.url.orEmpty(),
                                        hasUserGesture = false,
                                    ),
                                )?.let { theme ->
                                    runCatching { themeListener.onThemeChanged(view, theme) }
                                }
                            }
                            return
                        }
                        if (messageListener == null) return
                        // Origin and frame checks are repeated by the concrete
                        // action router.  The factory only supplies the narrow
                        // WebMessage boundary and never interprets payloads.
                        runCatching {
                            messageListener.onMessage(
                                view,
                                text,
                                sourceOrigin,
                                isMainFrame,
                                replyProxy,
                            )
                        }
                    }
                },
            )
        }
    }

    /** Removes the WebView from its parent and releases its page resources. */
    fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.removeAllViews()
        webView.destroy()
    }

    private fun loadBridgeScript(context: Context): String? = try {
        val themeScript = context.assets.open(THEME_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val bridgeScript = context.assets.open(BRIDGE_ASSET_PATH)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        "$themeScript\n$bridgeScript"
    } catch (_: IOException) {
        null
    }

    companion object {
        // Must match the document-start bridge asset and the AndroidX injected
        // WebMessage object name.
        const val JS_OBJECT_NAME: String = "libraNative"
        const val APP_UA_MARKER: String = "2LibraAndroid/1.0"
        private const val THEME_ASSET_PATH: String = "web/libra-theme.js"
        private const val BRIDGE_ASSET_PATH: String = "web/libra-bridge.js"
    }
}
