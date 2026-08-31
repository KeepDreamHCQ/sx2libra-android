package com.suixin.sx2libra.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy

/**
 * Thin platform adapter for the existing LibraWebMessageListener seam. The
 * reply proxy is used and discarded in this callback; it never crosses into a
 * ViewModel or repository.
 */
class NativeActionWebMessageListener(
    private val router: NativeActionRouter,
    private val userGestureProvider: () -> Boolean = { false },
) : LibraWebMessageListener {
    override fun onMessage(
        view: WebView,
        message: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val reply = router.route(
            rawMessage = message,
            source = BridgeSource(
                sourceOrigin = sourceOrigin.toString(),
                isMainFrame = isMainFrame,
                currentUrl = view.url.orEmpty(),
                hasUserGesture = userGestureProvider(),
            ),
        )
        runCatching { replyProxy.postMessage(reply.toJson()) }
    }
}

