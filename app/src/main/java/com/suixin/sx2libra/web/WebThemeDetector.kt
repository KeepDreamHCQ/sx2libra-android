package com.suixin.sx2libra.web

import android.content.Context
import android.webkit.WebView
import com.suixin.sx2libra.model.WebTheme
import java.io.IOException

/** Runs the same page-theme detector used by the document-start bridge. */
class WebThemeDetector(
    private val context: Context,
    private val routePolicy: RoutePolicy = RoutePolicy(),
    private val onThemeChanged: (WebTheme) -> Unit,
) {
    private var lastTheme = WebTheme.UNKNOWN
    private var inspectionGeneration = 0L

    /** Performs one guarded inspection of the current allowed site page. */
    fun inspect(webView: WebView) {
        val pageUrl = webView.url ?: return
        if (!routePolicy.isAllowedPageUrl(pageUrl)) return
        val script = detectorScript ?: return
        val generation = ++inspectionGeneration
        val expression = "$script\nwindow.LibraThemeDetector.detect();"
        runCatching {
            webView.evaluateJavascript(expression) { rawResult ->
                if (generation != inspectionGeneration) return@evaluateJavascript
                if (routePolicy.normalize(webView.url) != routePolicy.normalize(pageUrl)) return@evaluateJavascript
                val theme = parseResult(rawResult) ?: return@evaluateJavascript
                if (theme == lastTheme) return@evaluateJavascript
                lastTheme = theme
                onThemeChanged(theme)
            }
        }
    }

    private val detectorScript: String? by lazy {
        try {
            context.assets.open(DETECTOR_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) {
            null
        }
    }

    private fun parseResult(rawResult: String?): WebTheme? = when (rawResult?.trim()) {
        "\"light\"" -> WebTheme.LIGHT
        "\"dark\"" -> WebTheme.DARK
        else -> null
    }

    private companion object {
        const val DETECTOR_ASSET_PATH = "web/libra-theme.js"
    }
}
