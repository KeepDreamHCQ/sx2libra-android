package com.suixin.sx2libra.ui.post

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.repository.WebSessionRepositoryResolver
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.WebRoute
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.ui.web.WebPageAction
import com.suixin.sx2libra.ui.web.WebPageError
import com.suixin.sx2libra.web.LibraWebChromeClient
import com.suixin.sx2libra.web.LibraWebChromeClientListener
import com.suixin.sx2libra.web.LibraWebViewClient
import com.suixin.sx2libra.web.LibraWebViewClientListener
import com.suixin.sx2libra.web.LibraWebViewFactory
import com.suixin.sx2libra.web.LibraWebViewRefreshLayout
import com.suixin.sx2libra.web.NativeActionController
import com.suixin.sx2libra.web.NativeActionControllerRegistry
import com.suixin.sx2libra.web.NativeActionPage
import com.suixin.sx2libra.web.NavigationResult
import com.suixin.sx2libra.web.PageNavigator
import com.suixin.sx2libra.web.RoutePolicy
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import kotlinx.coroutines.launch

/** Hosts one strictly validated `/post/{node}/{id}` page. */
open class PostActivity : FragmentActivity() {
    private val routePolicy = RoutePolicy()
    private val sessionRepository by lazy { WebSessionRepositoryResolver.resolve(this) }

    private lateinit var webView: WebView
    private lateinit var refreshLayout: LibraWebViewRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var viewModel: PostViewModel
    private lateinit var webViewFactory: LibraWebViewFactory
    private lateinit var initialUrl: String
    private lateinit var nativeActionController: NativeActionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars()
        val candidate = intent?.getStringExtra(AuthContract.EXTRA_INITIAL_URL)
        val route = routePolicy.classify(candidate)
        if (route.kind != WebRouteKind.POST_DETAIL) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        initialUrl = route.url
        setContentView(R.layout.activity_post)
        findViewById<View>(R.id.post_root).applySystemBarInsets(top = true)
        refreshLayout = findViewById(R.id.post_refresh)
        refreshLayout.applySystemBarInsets(bottom = true)
        webView = findViewById(R.id.post_web_view)
        refreshLayout.bind(webView)
        progressBar = findViewById(R.id.post_progress)
        errorText = findViewById(R.id.post_error)
        viewModel = ViewModelProvider(
            this,
            PostViewModel.Factory(initialUrl, routePolicy, sessionRepository),
        )[PostViewModel::class.java]

        webViewFactory = LibraWebViewFactory(routePolicy)
        webViewFactory.configure(webView)
        nativeActionController = NativeActionControllerRegistry.create(
            activity = this,
            page = NativeActionPage.POST_DETAIL,
            viewModel = viewModel,
            routePolicy = routePolicy,
        )
        nativeActionController.bind(webView)
        webViewFactory.installBridge(webView, nativeActionController.messageListener())
        webView.webViewClient = LibraWebViewClient(
            initialUrl,
            routePolicy,
            object : LibraWebViewClientListener {
                override fun onMainFrameNavigationRequested(
                    route: WebRoute,
                    isRedirect: Boolean,
                    hasUserGesture: Boolean,
                ) {
                    refreshLayout.stopRefreshing()
                    viewModel.onNavigationRequested(route.url, isRedirect, hasUserGesture)
                }

                override fun onPageStarted(url: String?) = viewModel.onPageStarted(url)
                override fun onPageCommitted(url: String?) = viewModel.onPageCommitted(url)
                override fun onPageFinished(url: String?) {
                    refreshLayout.stopRefreshing()
                    viewModel.onPageFinished(url)
                }
                override fun onLoadingError(isMainFrame: Boolean, errorCode: Int, description: String?) {
                    if (isMainFrame) {
                        refreshLayout.stopRefreshing()
                        viewModel.onError(WebPageError.NETWORK_ERROR)
                    }
                }
                override fun onSslError() {
                    refreshLayout.stopRefreshing()
                    viewModel.onError(WebPageError.SSL_ERROR)
                }
                override fun onRendererGone() {
                    refreshLayout.stopRefreshing()
                    viewModel.onError(WebPageError.RENDERER_GONE)
                }
            },
        )
        webView.webChromeClient = LibraWebChromeClient(
            nativeActionController.chromeListener(
                object : LibraWebChromeClientListener {
                    override fun onProgressChanged(progress: Int) =
                        viewModel.onProgressChanged(progress)
                },
            ),
        )
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            // The sole initial navigation for this Activity instance.
            webView.loadUrl(initialUrl)
        }
        collectState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        // Return closes this detail Activity; do not reuse WebView history.
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::nativeActionController.isInitialized) nativeActionController.onDestroy()
        if (::webView.isInitialized) webViewFactory.destroy(webView)
        super.onDestroy()
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    progressBar.progress = state.progress
                    errorText.visibility = if (state.error == null) View.GONE else View.VISIBLE
                    errorText.text = getString(R.string.web_page_load_error)
                    state.pendingAction?.let(::handleAction)
                }
            }
        }
    }

    private fun handleAction(action: WebPageAction) {
        val navigator = PageNavigator(routePolicy)
        val result = when (action) {
            is WebPageAction.OpenPage -> navigator.navigate(this, action.route)
            is WebPageAction.OpenExternal -> navigator.navigate(this, action.url)
            is WebPageAction.SessionExpired -> navigator.navigate(this, action.loginUrl)
            is WebPageAction.Rejected -> null
        }
        if (result != null) {
            viewModel.onActionHandled(action.id)
            if (
                result is NavigationResult.Started &&
                ((action is WebPageAction.OpenPage && action.replaceCurrent) ||
                    (action is WebPageAction.OpenExternal && action.replaceCurrent) ||
                    (action is WebPageAction.SessionExpired && action.replaceCurrent))
            ) {
                finish()
            }
        } else {
            viewModel.onActionHandled(action.id)
        }
    }
}
