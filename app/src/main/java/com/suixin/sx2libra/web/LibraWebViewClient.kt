package com.suixin.sx2libra.web

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.suixin.sx2libra.data.repository.WebImageCacheRepositoryContract
import com.suixin.sx2libra.model.WebRoute

/** Callbacks converted by the Activity into ViewModel value-object intents. */
interface LibraWebViewClientListener {
    fun onMainFrameNavigationRequested(
        route: WebRoute,
        isRedirect: Boolean,
        hasUserGesture: Boolean,
    )

    /** Visual first-paint signal kept separate from semantic route handling. */
    fun onPageCommitVisible(url: String?) {}

    fun onPageStarted(url: String?) {}

    fun onPageCommitted(url: String?) {}

    fun onPageFinished(url: String?) {}

    fun onLoadingError(isMainFrame: Boolean, errorCode: Int, description: String?) {}

    fun onSslError() {}

    fun onRendererGone() {}
}

/**
 * WebViewClient that only classifies main-frame navigations.  It never starts
 * another page itself and never calls any WebView history/navigation method.
 */
class LibraWebViewClient(
    private val initialUrl: String,
    private val routePolicy: RoutePolicy = RoutePolicy(),
    private val listener: LibraWebViewClientListener,
    private val allowLoginFlowNavigation: Boolean = false,
    imageCache: WebImageCacheRepositoryContract? = null,
) : WebViewClient() {
    private val imageResourceInterceptor = imageCache?.let(::WebImageResourceInterceptor)

    init {
        require(routePolicy.isAllowedPageUrl(initialUrl)) {
            "initialUrl must be an allowed 2Libra page"
        }
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!request.isForMainFrame) return false
        val route = routePolicy.classify(request.url.toString())
        if (allowLoginFlowNavigation && routePolicy.isAllowedLoginFlowUrl(route.url)) return false
        if (routePolicy.isAllowedInlineProfileNavigation(initialUrl, route.url)) return false
        if (
            route.isSitePage &&
            routePolicy.isAllowedInlinePaginationNavigation(initialUrl, route.url)
        ) {
            return false
        }
        if (
            request.isRedirect &&
            route.isSitePage &&
            routePolicy.isAllowedSamePageRedirect(initialUrl, route.url)
        ) {
            return false
        }
        listener.onMainFrameNavigationRequested(
            route = route,
            isRedirect = request.isRedirect,
            hasUserGesture = request.hasGesture(),
        )
        return true
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        val route = routePolicy.classify(url)
        if (allowLoginFlowNavigation && routePolicy.isAllowedLoginFlowUrl(route.url)) return false
        if (routePolicy.isAllowedInlineProfileNavigation(initialUrl, route.url)) return false
        if (route.isSitePage && routePolicy.isAllowedInlinePaginationNavigation(initialUrl, route.url)) {
            return false
        }
        if (route.isSitePage && routePolicy.isAllowedSamePageRedirect(initialUrl, route.url)) {
            return false
        }
        listener.onMainFrameNavigationRequested(route, isRedirect = false, hasUserGesture = true)
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? = imageResourceInterceptor?.intercept(request)
        ?: super.shouldInterceptRequest(view, request)

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        listener.onPageStarted(url)
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        listener.onPageCommitVisible(url)
        // Keep the existing route/POST redirect callback for navigation policy.
        listener.onPageCommitted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        listener.onPageFinished(url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        listener.onLoadingError(
            isMainFrame = request.isForMainFrame,
            errorCode = error.errorCode,
            description = error.description?.toString(),
        )
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?,
    ) {
        listener.onLoadingError(true, errorCode, description)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never bypass certificate validation, even for a user gesture.
        handler.cancel()
        listener.onSslError()
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        listener.onRendererGone()
        return true
    }
}
