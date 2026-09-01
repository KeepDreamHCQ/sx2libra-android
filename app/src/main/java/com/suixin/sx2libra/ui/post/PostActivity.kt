package com.suixin.sx2libra.ui.post

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suixin.sx2libra.LibraApplication
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.repository.WebSessionRepositoryResolver
import com.suixin.sx2libra.data.repository.WebSnapshotRepositoryContract
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.AuthState
import com.suixin.sx2libra.model.WebRoute
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.ui.web.WebPageAction
import com.suixin.sx2libra.ui.web.WebPageError
import com.suixin.sx2libra.ui.web.WebPageSnapshot
import com.suixin.sx2libra.ui.web.WebPageUiState
import com.suixin.sx2libra.web.LibraWebChromeClient
import com.suixin.sx2libra.web.LibraWebChromeClientListener
import com.suixin.sx2libra.web.LibraWebViewClient
import com.suixin.sx2libra.web.LibraWebViewClientListener
import com.suixin.sx2libra.web.LibraWebViewFactory
import com.suixin.sx2libra.web.LibraWebViewRefreshLayout
import com.suixin.sx2libra.web.LibraWebThemeListener
import com.suixin.sx2libra.web.NativeActionController
import com.suixin.sx2libra.web.NativeActionControllerRegistry
import com.suixin.sx2libra.web.NativeActionPage
import com.suixin.sx2libra.web.NavigationResult
import com.suixin.sx2libra.web.PageNavigator
import com.suixin.sx2libra.web.RoutePolicy
import com.suixin.sx2libra.web.WebThemeDetector
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import com.suixin.sx2libra.ui.theme.ThemeCoordinator
import kotlinx.coroutines.launch

/** Hosts one strictly validated `/post/{node}/{id}` page. */
open class PostActivity : AppCompatActivity() {
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
    private lateinit var themeObservation: ThemeCoordinator.Observation
    private lateinit var themeDetector: WebThemeDetector
    private lateinit var snapshotOverlay: View
    private lateinit var snapshotImage: ImageView
    private lateinit var snapshotChecking: View
    private lateinit var snapshotFallbackPanel: View
    private lateinit var snapshotRetry: View
    private var snapshotBitmap: Bitmap? = null
    private var acknowledgedFallbackId: Long? = null

    private val snapshotRepository: WebSnapshotRepositoryContract
        get() = (application as LibraApplication).appContainer.webSnapshotRepository

    private val snapshotUrl: String?
        get() = initialUrl.takeIf(routePolicy::isPostDetailSnapshotUrl)

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
        themeObservation = (application as LibraApplication).themeCoordinator.createObservation()
        setContentView(R.layout.activity_post)
        findViewById<View>(R.id.post_root).applySystemBarInsets(top = true)
        refreshLayout = findViewById(R.id.post_refresh)
        refreshLayout.applySystemBarInsets(bottom = true)
        webView = findViewById(R.id.post_web_view)
        refreshLayout.bind(webView)
        progressBar = findViewById(R.id.post_progress)
        errorText = findViewById(R.id.post_error)
        snapshotOverlay = findViewById(R.id.post_snapshot_overlay)
        snapshotImage = findViewById(R.id.post_snapshot_image)
        snapshotChecking = findViewById(R.id.post_snapshot_checking)
        snapshotFallbackPanel = findViewById(R.id.post_snapshot_fallback_panel)
        snapshotRetry = findViewById(R.id.post_snapshot_retry)
        viewModel = ViewModelProvider(
            this,
            PostViewModel.Factory(initialUrl, routePolicy, sessionRepository),
        )[PostViewModel::class.java]
        snapshotRetry.setOnClickListener {
            if (viewModel.uiState.value.snapshot != WebPageSnapshot.FALLBACK) {
                return@setOnClickListener
            }
            viewModel.onSnapshotRetry()
            webView.reload()
        }

        webViewFactory = LibraWebViewFactory(
            routePolicy,
            (application as LibraApplication).appContainer.webImageCache,
        )
        webViewFactory.configure(webView)
        nativeActionController = NativeActionControllerRegistry.create(
            activity = this,
            page = NativeActionPage.POST_DETAIL,
            viewModel = viewModel,
            routePolicy = routePolicy,
        )
        nativeActionController.bind(webView)
        themeDetector = WebThemeDetector(this, routePolicy) { theme ->
            themeObservation.report(theme)
        }
        webViewFactory.installBridge(
            webView,
            nativeActionController.messageListener(),
            LibraWebThemeListener { _, theme -> themeObservation.report(theme) },
        )
        webView.webViewClient = webViewFactory.createClient(
            initialUrl,
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
                override fun onPageCommitVisible(url: String?) = onSnapshotPageCommitVisible(url)
                override fun onPageCommitted(url: String?) = viewModel.onPageCommitted(url)
                override fun onPageFinished(url: String?) {
                    refreshLayout.stopRefreshing()
                    viewModel.onPageFinished(url)
                    themeDetector.inspect(webView)
                    onSnapshotPageFinished(url)
                }
                override fun onLoadingError(isMainFrame: Boolean, errorCode: Int, description: String?) {
                    if (isMainFrame) {
                        refreshLayout.stopRefreshing()
                        if (!onSnapshotLoadFailure()) {
                            viewModel.onError(WebPageError.NETWORK_ERROR)
                        }
                    }
                }
                override fun onSslError() {
                    refreshLayout.stopRefreshing()
                    if (!onSnapshotLoadFailure()) {
                        viewModel.onError(WebPageError.SSL_ERROR)
                    }
                }
                override fun onRendererGone() {
                    refreshLayout.stopRefreshing()
                    if (!onSnapshotLoadFailure()) {
                        viewModel.onError(WebPageError.RENDERER_GONE)
                    }
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
        collectState()
        startWebView(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::themeObservation.isInitialized) themeObservation.activate()
    }

    override fun onPause() {
        if (::themeObservation.isInitialized) themeObservation.deactivate()
        super.onPause()
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
        if (::snapshotOverlay.isInitialized) snapshotOverlay.animate().cancel()
        releaseSnapshotBitmap()
        if (::themeObservation.isInitialized) themeObservation.close()
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
                    renderSnapshot(state)
                    val showingSnapshot = state.snapshot == WebPageSnapshot.CHECKING ||
                        state.snapshot == WebPageSnapshot.SHOWING ||
                        state.snapshot == WebPageSnapshot.FALLBACK
                    errorText.visibility = if (state.error != null && !showingSnapshot) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    errorText.text = getString(R.string.web_page_load_error)
                    state.pendingAction?.let(::handleAction)
                }
            }
        }
    }

    private fun startWebView(savedState: Bundle?) {
        if (savedState != null) {
            viewModel.disableSnapshot()
            if (webView.restoreState(savedState) != null) return
            // A failed restore follows the existing initial-navigation path,
            // but does not reintroduce a snapshot over a restoration attempt.
            webView.loadUrl(initialUrl)
            return
        }

        val url = snapshotUrl
        if (url == null) {
            viewModel.disableSnapshot()
            webView.loadUrl(initialUrl)
            return
        }

        viewModel.beginSnapshotCheck()
        renderSnapshot(viewModel.uiState.value)
        lifecycleScope.launch {
            val bitmap = readSnapshotIfSessionAllows(url)
            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }
            releaseSnapshotBitmap()
            if (bitmap != null) {
                snapshotBitmap = bitmap
                viewModel.onSnapshotAvailable()
            } else {
                viewModel.onSnapshotUnavailable()
            }
            // The sole initial navigation for this Activity instance.
            webView.loadUrl(initialUrl)
        }
    }

    private suspend fun readSnapshotIfSessionAllows(url: String): Bitmap? {
        val authState = try {
            sessionRepository.refreshAuthState()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            AuthState.UNKNOWN
        }
        if (authState != AuthState.LOGGED_IN) return null

        return try {
            snapshotRepository.read(
                url = url,
                targetWidth = resources.displayMetrics.widthPixels,
                targetHeight = resources.displayMetrics.heightPixels,
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun renderSnapshot(state: WebPageUiState) {
        val showingSnapshot = state.snapshot == WebPageSnapshot.CHECKING ||
            state.snapshot == WebPageSnapshot.SHOWING ||
            state.snapshot == WebPageSnapshot.FALLBACK
        if (showingSnapshot) {
            snapshotOverlay.animate().cancel()
            snapshotOverlay.alpha = 1f
            snapshotOverlay.visibility = View.VISIBLE
            snapshotOverlay.importantForAccessibility = if (
                state.snapshot == WebPageSnapshot.FALLBACK
            ) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            snapshotFallbackPanel.importantForAccessibility = if (
                state.snapshot == WebPageSnapshot.FALLBACK
            ) {
                View.IMPORTANT_FOR_ACCESSIBILITY_YES
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            snapshotImage.visibility = if (snapshotBitmap == null) View.GONE else View.VISIBLE
            snapshotChecking.visibility = if (state.snapshot == WebPageSnapshot.CHECKING) {
                View.VISIBLE
            } else {
                View.GONE
            }
            snapshotFallbackPanel.visibility = if (state.snapshot == WebPageSnapshot.FALLBACK) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (snapshotBitmap != null && snapshotImage.drawable == null) {
                snapshotImage.setImageBitmap(snapshotBitmap)
            }
        } else if (snapshotOverlay.visibility == View.VISIBLE) {
            snapshotOverlay.animate()
                .alpha(0f)
                .setDuration(SNAPSHOT_FADE_MILLIS)
                .withEndAction {
                    val currentSnapshot = viewModel.uiState.value.snapshot
                    if (currentSnapshot == WebPageSnapshot.NONE ||
                        currentSnapshot == WebPageSnapshot.DISABLED
                    ) {
                        snapshotOverlay.visibility = View.GONE
                        snapshotOverlay.alpha = 1f
                        releaseSnapshotBitmap()
                    }
                }
                .start()
        }

        val fallbackId = state.snapshotFallbackId
        if (fallbackId != null && fallbackId != acknowledgedFallbackId) {
            acknowledgedFallbackId = fallbackId
            Toast.makeText(
                this,
                R.string.web_snapshot_fallback,
                Toast.LENGTH_SHORT,
            ).show()
            viewModel.acknowledgeSnapshotFallback(fallbackId)
        }
    }

    private fun onSnapshotPageCommitVisible(url: String?) {
        if (routePolicy.normalize(url) == snapshotUrl) {
            viewModel.onSnapshotContentCommitted()
        }
    }

    private fun onSnapshotPageFinished(url: String?) {
        val expectedUrl = snapshotUrl ?: return
        if (routePolicy.normalize(url) != expectedUrl) return
        val state = viewModel.uiState.value
        if (state.error != null || state.snapshot == WebPageSnapshot.FALLBACK) return
        webView.post {
            if (isFinishing || isDestroyed || !webView.isAttachedToWindow) return@post
            if (routePolicy.normalize(webView.url) != expectedUrl) return@post
            if (sessionRepository.authState.value != AuthState.LOGGED_IN) return@post
            val bitmap = captureSnapshot() ?: return@post
            lifecycleScope.launch {
                try {
                    snapshotRepository.save(expectedUrl, bitmap)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    private fun onSnapshotLoadFailure(): Boolean {
        if (viewModel.uiState.value.snapshot != WebPageSnapshot.SHOWING) return false
        viewModel.onSnapshotLoadFailed()
        return true
    }

    private fun captureSnapshot(): Bitmap? {
        if (!webView.isAttachedToWindow || webView.width <= 0 || webView.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                webView.draw(Canvas(bitmap))
            }
        }.getOrNull()
    }

    private fun releaseSnapshotBitmap() {
        if (!::snapshotImage.isInitialized) return
        snapshotImage.setImageDrawable(null)
        snapshotBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        snapshotBitmap = null
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

    companion object {
        private const val SNAPSHOT_FADE_MILLIS = 150L
    }
}
