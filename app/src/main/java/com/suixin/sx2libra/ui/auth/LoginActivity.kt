package com.suixin.sx2libra.ui.auth

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.repository.WebSessionRepositoryResolver
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.web.LibraWebChromeClient
import com.suixin.sx2libra.web.LibraWebChromeClientListener
import com.suixin.sx2libra.web.LibraWebViewClient
import com.suixin.sx2libra.web.LibraWebViewClientListener
import com.suixin.sx2libra.web.LibraWebViewFactory
import com.suixin.sx2libra.web.LibraWebViewRefreshLayout
import com.suixin.sx2libra.web.RoutePolicy
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import kotlinx.coroutines.launch

/** Isolated login page.  The only top-level load is the validated login URL. */
class LoginActivity : ComponentActivity() {
    private val routePolicy = RoutePolicy()
    private val sessionRepository by lazy { WebSessionRepositoryResolver.resolve(this) }

    private lateinit var webView: WebView
    private lateinit var refreshLayout: LibraWebViewRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var viewModel: LoginViewModel
    private lateinit var factory: LibraWebViewFactory
    private var initialUrl: String = AuthContract.LOGIN_URL
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars()
        val candidate = intent?.getStringExtra(AuthContract.EXTRA_INITIAL_URL)
            ?: AuthContract.LOGIN_URL
        val route = routePolicy.classify(candidate)
        if (route.kind != com.suixin.sx2libra.model.WebRouteKind.LOGIN) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        initialUrl = route.url
        setContentView(R.layout.activity_auth_login)
        findViewById<View>(R.id.auth_login_root).applySystemBarInsets(top = true)
        refreshLayout = findViewById(R.id.auth_login_refresh)
        refreshLayout.applySystemBarInsets(bottom = true)
        webView = findViewById(R.id.auth_login_web_view)
        refreshLayout.bind(webView)
        progressBar = findViewById(R.id.auth_login_progress)
        errorText = findViewById(R.id.auth_login_error)
        viewModel = ViewModelProvider(
            this,
            LoginViewModel.Factory(initialUrl, sessionRepository, routePolicy),
        )[LoginViewModel::class.java]

        factory = LibraWebViewFactory(routePolicy)
        // Reuse the view supplied by the layout while applying the same secure
        // settings/profile as all other page hosts.
        factory.configure(webView)
        webView.webViewClient = LibraWebViewClient(
            initialUrl,
            routePolicy,
            object : LibraWebViewClientListener {
                override fun onMainFrameNavigationRequested(
                    route: com.suixin.sx2libra.model.WebRoute,
                    isRedirect: Boolean,
                    hasUserGesture: Boolean,
                ) {
                    refreshLayout.stopRefreshing()
                    // Login and OAuth provider pages remain in this WebView.
                    // Non-allowlisted external URLs are rejected by the
                    // ViewModel instead of being handed to the system browser.
                    viewModel.onPageCommitted(route.url)
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
                        viewModel.onError(LoginError.NETWORK_ERROR)
                    }
                }
                override fun onSslError() {
                    refreshLayout.stopRefreshing()
                    viewModel.onError(LoginError.SSL_ERROR)
                }
            },
            allowLoginFlowNavigation = true,
        )
        webView.webChromeClient = LibraWebChromeClient(object : LibraWebChromeClientListener {
            override fun onProgressChanged(progress: Int) = viewModel.onProgressChanged(progress)
        })
        factory.installBridge(webView, null)

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(initialUrl)
        }
        collectState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        // Login is a separate Activity; returning closes it and never exposes
        // WebView history or calls goBack().
        setResultIfNeeded(Activity.RESULT_CANCELED)
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            factory.destroy(webView)
        }
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
                    when (val action = state.pendingAction) {
                        is LoginAction.Completed -> {
                            if (!resultSent) {
                                resultSent = true
                                val result = intent?.getStringExtra(AuthContract.EXTRA_PROTECTED_TAB)
                                val data = android.content.Intent().apply {
                                    putExtra(AuthContract.EXTRA_LOGIN_RESULT, AuthContract.LOGIN_RESULT_SUCCESS)
                                    if (result != null) putExtra(AuthContract.EXTRA_PROTECTED_TAB, result)
                                }
                                setResult(Activity.RESULT_OK, data)
                                viewModel.onActionHandled(action.id)
                                finish()
                            }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }

    private fun setResultIfNeeded(resultCode: Int) {
        if (!resultSent) {
            resultSent = true
            setResult(resultCode)
        }
    }
}
