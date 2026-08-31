package com.suixin.sx2libra.web

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.suixin.sx2libra.R
import kotlin.math.abs
import kotlin.math.max

/** Pull-to-refresh container shared by every H5 WebView entry point. */
open class LibraWebViewRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {
    private var webView: WebView? = null
    private var routePolicy: RoutePolicy = RoutePolicy()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDirection = TouchDirection.UNDECIDED
    private var parentInterceptDisallowed = false
    private var touchTargetCheckComplete = false
    private var refreshGestureBlocked = false
    private var touchSequence = 0L

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

    /**
     * Locks each gesture to its first meaningful direction.  A WebView sits
     * below both this refresh container and the post ViewPager2, so the
     * ViewPager2 must wait until a horizontal direction is explicit before it
     * is allowed to intercept the stream.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(event)
            MotionEvent.ACTION_MOVE -> {
                when (resolveTouchDirection(event)) {
                    TouchDirection.HORIZONTAL -> {
                        setParentInterceptDisallowed(false)
                        return false
                    }

                    TouchDirection.VERTICAL -> {
                        setParentInterceptDisallowed(true)
                        if (!touchTargetCheckComplete || refreshGestureBlocked) return false
                    }

                    TouchDirection.UNDECIDED -> return false
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val intercepted = super.onInterceptTouchEvent(event)
                endTouch()
                return intercepted
            }
        }

        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            endTouch()
        }
        return handled
    }

    /** Stops the indicator when the current page finishes or cannot load. */
    fun stopRefreshing() {
        if (isRefreshing) isRefreshing = false
    }

    private fun beginTouch(event: MotionEvent) {
        touchSequence += 1L
        initialTouchX = event.x
        initialTouchY = event.y
        touchDirection = TouchDirection.UNDECIDED
        // Do not let a fast diagonal move reach ViewPager2 before the
        // direction lock has enough distance to make a decision.
        setParentInterceptDisallowed(true)

        // The child receives ACTION_DOWN after this method returns.  Resolve
        // the DOM target asynchronously so a drag that starts on the page's
        // post action button cannot be converted into pull-to-refresh.
        touchTargetCheckComplete = false
        refreshGestureBlocked = true
        checkTouchTarget(touchSequence, event)
    }

    private fun resolveTouchDirection(event: MotionEvent): TouchDirection {
        if (touchDirection != TouchDirection.UNDECIDED) return touchDirection

        val dx = event.x - initialTouchX
        val dy = event.y - initialTouchY
        val distance = max(abs(dx), abs(dy))
        if (distance < touchSlop) return TouchDirection.UNDECIDED

        val horizontalDistance = abs(dx)
        val verticalDistance = abs(dy)
        touchDirection = when {
            horizontalDistance > verticalDistance * DIRECTION_LOCK_RATIO ->
                TouchDirection.HORIZONTAL
            verticalDistance > horizontalDistance * DIRECTION_LOCK_RATIO ->
                TouchDirection.VERTICAL
            else -> TouchDirection.UNDECIDED
        }
        return touchDirection
    }

    @Suppress("DEPRECATION")
    private fun checkTouchTarget(sequence: Long, event: MotionEvent) {
        val currentWebView = webView
        if (currentWebView == null) {
            touchTargetCheckComplete = true
            refreshGestureBlocked = false
            return
        }

        val scale = currentWebView.scale.takeIf { it > 0f } ?: 1f
        val x = event.x / scale
        val y = event.y / scale
        val script = """
            (function(x, y) {
                var target = document.elementFromPoint(x, y);
                if (!target || !target.closest) return false;
                var action = target.closest('a, button, [role="button"]');
                if (!action) return false;
                var href = action.getAttribute('href') || '';
                if (/\/post\/create(?:[/?#]|$)/.test(href)) return true;
                var label = [
                    action.getAttribute('aria-label'),
                    action.getAttribute('title'),
                    action.id,
                    typeof action.className === 'string' ? action.className : ''
                ].join(' ').toLowerCase();
                if (/(发帖|发布|创建帖子|新建帖子|compose|create post|new post)/.test(label)) {
                    return true;
                }
                return (action.textContent || '').trim() === '+';
            })($x, $y);
        """.trimIndent()

        runCatching {
            currentWebView.evaluateJavascript(script) { result ->
                if (sequence != touchSequence) return@evaluateJavascript
                touchTargetCheckComplete = true
                refreshGestureBlocked = result == "true" || isRefreshExcludedNativeHit(currentWebView)
            }
        }.onFailure {
            if (sequence != touchSequence) return@onFailure
            touchTargetCheckComplete = true
            refreshGestureBlocked = isRefreshExcludedNativeHit(currentWebView)
        }
    }

    private fun isRefreshExcludedNativeHit(webView: WebView): Boolean =
        webView.hitTestResult.type == WebView.HitTestResult.EDIT_TEXT_TYPE

    private fun setParentInterceptDisallowed(disallowed: Boolean) {
        if (parentInterceptDisallowed == disallowed) return
        parent?.requestDisallowInterceptTouchEvent(disallowed)
        parentInterceptDisallowed = disallowed
    }

    private fun endTouch() {
        touchSequence += 1L
        setParentInterceptDisallowed(false)
        touchDirection = TouchDirection.UNDECIDED
        touchTargetCheckComplete = false
        refreshGestureBlocked = false
    }

    private enum class TouchDirection {
        UNDECIDED,
        HORIZONTAL,
        VERTICAL,
    }

    private companion object {
        const val DIRECTION_LOCK_RATIO = 1.2f
    }
}
