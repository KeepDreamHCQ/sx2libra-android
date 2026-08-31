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
    private var routePolicy: RoutePolicy = RoutePolicy()

    init {
        setColorSchemeResources(R.color.libra_primary, R.color.libra_accent)
        setProgressBackgroundColorSchemeResource(R.color.surface_subtle)
        setOnRefreshListener {
            val currentWebView = webView
            if (currentWebView == null) {
                setRefreshing(false)
                return@setOnRefreshListener
            }
            val pageOneUrl = routePolicy.paginationPageOneUrl(currentWebView.url)
            if (pageOneUrl != null) {
                currentWebView.loadUrl(pageOneUrl)
            } else {
                currentWebView.reload()
            }
        }
    }

    /** Connects the page WebView and URL policy used by the refresh gesture. */
    fun bind(webView: WebView, routePolicy: RoutePolicy = RoutePolicy()) {
        require(webView.parent == null || webView.parent === this) {
            "The WebView must be a child of LibraWebViewRefreshLayout"
        }
        this.webView = webView
        this.routePolicy = routePolicy
    }

    /** Stops the indicator when the current page finishes or cannot load. */
    fun stopRefreshing() {
        if (isRefreshing) isRefreshing = false
    }
}
