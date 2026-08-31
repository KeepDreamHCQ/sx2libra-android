package com.suixin.sx2libra.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.ui.web.WebPageViewModel
import org.json.JSONObject
import java.util.UUID

/**
 * Minimal document-start bridge adapter for navigation actions only.  Media,
 * sharing and upload actions are deliberately left to their owning workers.
 */
class NavigationBridgeListener(
    private val viewModel: WebPageViewModel,
    private val routePolicy: RoutePolicy = RoutePolicy(),
) : LibraWebMessageListener {
    override fun onMessage(
        view: WebView,
        message: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val result = parse(message, sourceOrigin, isMainFrame)
        if (result == null) return
        val (requestId, action, url) = result
        if (
            url != null &&
            (action == ACTION_OPEN_PAGE || action == ACTION_OPEN_POST || action == ACTION_OPEN_EXTERNAL)
        ) {
            // This legacy listener is navigation-only and has no trusted
            // native gesture source. Never trust a payload.userGesture field.
            viewModel.onNavigationRequested(url, isRedirect = false, hasUserGesture = false)
            replyProxy.postMessage(success(requestId))
        } else {
            replyProxy.postMessage(error(requestId, "UNSUPPORTED_ACTION"))
        }
    }

    private fun parse(
        rawMessage: String,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
    ): ParsedMessage? {
        if (!isMainFrame || sourceOrigin.toString() != AuthContract.SITE_ORIGIN) return null
        if (rawMessage.length > MAX_MESSAGE_LENGTH) return null
        val json = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return null
        if (json.optInt("version", -1) != 1) return null
        val requestId = json.optString("requestId", "")
        if (!UUID_PATTERN.matches(requestId)) return null
        val action = json.optString("action", "")
        if (action.length > MAX_ACTION_LENGTH) return null
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val url = payload.optString("url", "").takeIf { it.length in 1..MAX_URL_LENGTH }
        // RoutePolicy performs the scheme/host/path validation again; this
        // early check prevents malformed URLs from reaching ViewModel state.
        val route = url?.let(routePolicy::classify)
        if (action == ACTION_OPEN_PAGE && (route == null || !route.isSitePage)) {
            return null
        }
        if (action == ACTION_OPEN_POST && (route == null || route.kind != com.suixin.sx2libra.model.WebRouteKind.POST_DETAIL)) {
            return null
        }
        if (action == ACTION_OPEN_EXTERNAL && (route == null || route.kind != com.suixin.sx2libra.model.WebRouteKind.EXTERNAL)) {
            return null
        }
        return ParsedMessage(requestId = requestId, action = action, url = url)
    }

    private fun success(requestId: String): String = JSONObject()
        .put("version", 1)
        .put("requestId", requestId)
        .put("ok", true)
        .toString()

    private fun error(requestId: String, error: String): String = JSONObject()
        .put("version", 1)
        .put("requestId", requestId)
        .put("ok", false)
        .put("error", error)
        .toString()

    private data class ParsedMessage(
        val requestId: String,
        val action: String,
        val url: String?,
    )

    companion object {
        private const val ACTION_OPEN_PAGE = "open_page"
        private const val ACTION_OPEN_POST = "open_post"
        private const val ACTION_OPEN_EXTERNAL = "open_external"
        private const val MAX_MESSAGE_LENGTH = 16_384
        private const val MAX_ACTION_LENGTH = 64
        private const val MAX_URL_LENGTH = 8_192
        private val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
        )
    }
}
