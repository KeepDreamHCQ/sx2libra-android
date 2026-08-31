package com.suixin.sx2libra.web

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suixin.sx2libra.R

/** Pull-to-refresh container shared by every H5 WebView entry point. */
open class LibraWebViewRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {
    private var webView: WebView? = null

    init {
        setColorSchemeResources(R.color.libra_primary, R.color.libra_accent)
        setProgressBackgroundColorSchemeResource(R.color.surface_subtle)
        setOnRefreshListener {
            webView?.reload() ?: setRefreshing(false)
        }
    }

    /** Connects the page WebView that should be reloaded by the gesture. */
    fun bind(webView: WebView) {
        require(webView.parent == null || webView.parent === this) {
            "The WebView must be a child of LibraWebViewRefreshLayout"
        }
        this.webView = webView
    }

    /** Stops the indicator when the current page finishes or cannot load. */
    fun stopRefreshing() {
        if (isRefreshing) isRefreshing = false
    }
}
