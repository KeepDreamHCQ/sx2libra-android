package com.suixin.sx2libra.web

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import com.suixin.sx2libra.ui.web.WebPageError
import com.suixin.sx2libra.ui.web.WebPageViewModel

/**
 * Embeddable secure WebView host for ForumMenuPageFragment and other page
 * containers.  It owns exactly one WebView and exposes only lifecycle/value
 * callbacks; it is not a second navigation implementation.
 */
class LibraWebViewHost(
    context: android.content.Context,
    initialUrl: String,
    routePolicy: RoutePolicy = RoutePolicy(),
    listener: LibraWebViewClientListener,
    chromeListener: LibraWebChromeClientListener? = null,
    messageListener: LibraWebMessageListener? = null,
    themeListener: LibraWebThemeListener? = null,
    private val actionController: NativeActionController? = null,
) : LibraWebViewRefreshLayout(context) {
    private val factory = LibraWebViewFactory(routePolicy)
    private val initialPageUrl = routePolicy.normalize(initialUrl)
        ?: throw IllegalArgumentException("initialUrl is not an allowed 2Libra page")
    private val webView: WebView = factory.create(
        context,
        actionController?.messageListener() ?: messageListener,
        themeListener,
    )
    private val themeDetector = themeListener?.let { listener ->
        WebThemeDetector(context, routePolicy) { theme ->
            listener.onThemeChanged(webView, theme)
        }
    }
    private var started = false
    private var destroyed = false

    init {
        bind(webView, routePolicy)
        actionController?.bind(webView)
        webView.webViewClient = LibraWebViewClient(
            initialPageUrl,
            routePolicy,
            listenerWithRefreshCompletion(listener),
        )
        webView.webChromeClient = LibraWebChromeClient(
            actionController?.chromeListener(chromeListener) ?: chromeListener,
        )
        addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun listenerWithRefreshCompletion(
        delegate: LibraWebViewClientListener,
    ): LibraWebViewClientListener = object : LibraWebViewClientListener {
        override fun onMainFrameNavigationRequested(
            route: com.suixin.sx2libra.model.WebRoute,
            isRedirect: Boolean,
            hasUserGesture: Boolean,
        ) {
            stopRefreshing()
            delegate.onMainFrameNavigationRequested(route, isRedirect, hasUserGesture)
        }

        override fun onPageCommitVisible(url: String?) = delegate.onPageCommitVisible(url)

        override fun onPageStarted(url: String?) = delegate.onPageStarted(url)

        override fun onPageCommitted(url: String?) = delegate.onPageCommitted(url)

        override fun onPageFinished(url: String?) {
            stopRefreshing()
            delegate.onPageFinished(url)
            themeDetector?.inspect(webView)
        }

        override fun onLoadingError(
            isMainFrame: Boolean,
            errorCode: Int,
            description: String?,
        ) {
            if (isMainFrame) stopRefreshing()
            delegate.onLoadingError(isMainFrame, errorCode, description)
        }

        override fun onSslError() {
            stopRefreshing()
            delegate.onSslError()
        }

        override fun onRendererGone() {
            stopRefreshing()
            delegate.onRendererGone()
        }
    }

    /**
     * Starts or restores this host.  Calling it more than once is a no-op, so
     * a Fragment cannot accidentally trigger a second load of its initial URL.
     */
    fun start(savedState: Bundle? = null) {
        if (started || destroyed) return
        started = true
        if (savedState == null || webView.restoreState(savedState) == null) {
            webView.loadUrl(initialPageUrl)
        }
    }

    fun saveState(outState: Bundle) {
        if (!destroyed) webView.saveState(outState)
    }

    fun webView(): WebView = webView

    fun destroy() {
        if (destroyed) return
        destroyed = true
        actionController?.onDestroy()
        factory.destroy(webView)
    }

    override fun onDetachedFromWindow() {
        // Fragment/Activity should call destroy explicitly after removing the
        // host.  Do not destroy merely because a temporary parent transition
        // detached the view; this would lose a recoverable WebView state.
        super.onDetachedFromWindow()
    }

    companion object {
        /** Convenience adapter used by page fragments with a WebPageViewModel. */
        fun listenerFor(
            viewModel: WebPageViewModel,
        ): LibraWebViewClientListener = ViewModelWebViewClientListener(
            viewModel,
        )

        fun chromeListenerFor(viewModel: WebPageViewModel): LibraWebChromeClientListener =
            ViewModelWebChromeClientListener(viewModel)

        fun messageListenerFor(viewModel: WebPageViewModel): LibraWebMessageListener =
            NavigationBridgeListener(viewModel, viewModel.routePolicyForBridge())
    }
}

private class ViewModelWebViewClientListener(
    private val viewModel: WebPageViewModel,
) : LibraWebViewClientListener {
    override fun onMainFrameNavigationRequested(
        route: com.suixin.sx2libra.model.WebRoute,
        isRedirect: Boolean,
        hasUserGesture: Boolean,
    ) {
        viewModel.onNavigationRequested(route.url, isRedirect, hasUserGesture)
    }

    override fun onPageStarted(url: String?) = viewModel.onPageStarted(url)

    override fun onPageCommitted(url: String?) = viewModel.onPageCommitted(url)

    override fun onPageFinished(url: String?) {
        viewModel.onPageFinished(url)
    }

    override fun onLoadingError(isMainFrame: Boolean, errorCode: Int, description: String?) {
        if (isMainFrame) {
            viewModel.onError(WebPageError.NETWORK_ERROR)
        }
    }

    override fun onSslError() {
        viewModel.onError(WebPageError.SSL_ERROR)
    }

    override fun onRendererGone() {
        viewModel.onError(WebPageError.RENDERER_GONE)
    }
}

private class ViewModelWebChromeClientListener(
    private val viewModel: WebPageViewModel,
) : LibraWebChromeClientListener {
    override fun onProgressChanged(progress: Int) = viewModel.onProgressChanged(progress)
}
