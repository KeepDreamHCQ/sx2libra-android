package com.suixin.sx2libra.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suixin.sx2libra.data.repository.WebSessionRepositoryContract
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.WebRoute
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.web.RoutePolicy
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Screen state for one business page.  The ViewModel never owns or calls a
 * WebView; it only turns platform callbacks into immutable actions.
 */
open class WebPageViewModel(
    initialUrl: String,
    protected val routePolicy: RoutePolicy = RoutePolicy(),
    protected val sessionRepository: WebSessionRepositoryContract? = null,
) : ViewModel() {
    private val normalizedInitialUrl = routePolicy.normalize(initialUrl)
        ?: throw IllegalArgumentException("initialUrl is not an allowed 2Libra page")

    private val _uiState = MutableStateFlow(
        WebPageUiState(initialUrl = normalizedInitialUrl),
    )
    val uiState: StateFlow<WebPageUiState> = _uiState.asStateFlow()

    /** Policy seam for the embeddable WebView host's navigation bridge. */
    fun routePolicyForBridge(): RoutePolicy = routePolicy

    private var lastCommittedUrl: String = normalizedInitialUrl
    // WebView may report the same navigation again after the Activity was
    // already launched. Keep that handoff consumed for later non-gesture
    // callbacks; a new explicit gesture is still allowed below.
    private var lastHandledNavigationUrl: String? = null

    fun onPageStarted(url: String?) {
        val normalized = routePolicy.normalize(url)
        _uiState.value = _uiState.value.copy(
            currentUrl = normalized ?: url,
            isLoading = true,
            error = null,
        )
    }

    fun onProgressChanged(progress: Int) {
        _uiState.value = _uiState.value.copy(progress = progress.coerceIn(0, 100))
    }

    fun onPageFinished(url: String?) {
        val normalized = routePolicy.normalize(url)
        if (normalized != null) lastCommittedUrl = normalized
        _uiState.value = _uiState.value.copy(
            currentUrl = normalized ?: url,
            isLoading = false,
            progress = 100,
        )
        // This is event driven (not polling) and lets an expired server
        // session be corrected after an authenticated page redirects.
        sessionRepository?.let { repository ->
            viewModelScope.launch { repository.refreshAuthState() }
        }
    }

    /**
     * Converts a main-frame URL callback into an action.  Returning an action
     * to the View is the only navigation side effect; this method never calls
     * loadUrl(), reload(), goBack() or goForward().
     */
    fun onNavigationRequested(
        rawUrl: String?,
        isRedirect: Boolean = false,
        hasUserGesture: Boolean = true,
    ) {
        val route = routePolicy.classify(rawUrl)
        if (
            route.isSitePage &&
            routePolicy.isAllowedInlinePaginationNavigation(lastCommittedUrl, route.url)
        ) {
            // Pagination is the one same-WebView business navigation. The
            // WebView owns the actual load; this callback must not emit an
            // Activity action.
            return
        }
        if (!hasUserGesture && navigationDestination(route) == lastHandledNavigationUrl) {
            return
        }
        when (route.kind) {
            WebRouteKind.LOGIN -> {
                sessionRepository?.markSessionExpired()
                emitAction(
                    WebPageAction.SessionExpired(
                        id = newActionId(),
                        loginUrl = AuthContract.LOGIN_URL,
                        replaceCurrent = isRedirect,
                    ),
                )
            }

            WebRouteKind.EXTERNAL -> {
                // External URLs remain HTTPS-only in RoutePolicy and are opened
                // outside the authenticated WebView.
                emitAction(
                    WebPageAction.OpenExternal(
                        id = newActionId(),
                        url = route.url,
                        replaceCurrent = isRedirect,
                    ),
                )
            }

            WebRouteKind.INVALID -> {
                emitAction(
                    WebPageAction.Rejected(
                        id = newActionId(),
                        reason = route.reason ?: WebPageError.INVALID_NAVIGATION.name,
                    ),
                )
            }

            else -> {
                if (isRedirect && routePolicy.isAllowedSamePageRedirect(lastCommittedUrl, route.url)) {
                    // The WebView may finish a same-page server redirect as
                    // part of the initial request; it is not a new Activity.
                    return
                }
                if (route.url == lastCommittedUrl && !hasUserGesture) return
                emitAction(
                    WebPageAction.OpenPage(
                        id = newActionId(),
                        route = route,
                        replaceCurrent = isRedirect,
                    ),
                )
            }
        }
    }

    /** Handles a POST/form redirect observed after the WebView committed it. */
    fun onPageCommitted(url: String?) {
        val route = routePolicy.classify(url)
        if (
            route.isSitePage &&
            routePolicy.isAllowedInlineProfileNavigation(normalizedInitialUrl, route.url)
        ) {
            lastCommittedUrl = route.url
            _uiState.value = _uiState.value.copy(currentUrl = route.url)
            return
        }
        if (
            route.isSitePage &&
            routePolicy.isAllowedInlinePaginationNavigation(lastCommittedUrl, route.url)
        ) {
            lastCommittedUrl = route.url
            _uiState.value = _uiState.value.copy(currentUrl = route.url)
            return
        }
        if (route.isValid && navigationDestination(route) == lastHandledNavigationUrl) {
            if (route.isSitePage) {
                lastCommittedUrl = route.url
                _uiState.value = _uiState.value.copy(currentUrl = route.url)
            }
            return
        }
        when (route.kind) {
            WebRouteKind.LOGIN -> {
                sessionRepository?.markSessionExpired()
                emitAction(
                    WebPageAction.SessionExpired(
                        id = newActionId(),
                        loginUrl = AuthContract.LOGIN_URL,
                        replaceCurrent = true,
                    ),
                )
            }

            WebRouteKind.EXTERNAL -> emitAction(
                WebPageAction.OpenExternal(newActionId(), route.url, replaceCurrent = true),
            )

            WebRouteKind.INVALID -> onError(WebPageError.INVALID_NAVIGATION)

            else -> {
                val wasInitialPage = route.url == normalizedInitialUrl
                val wasSameCommittedPage = route.url == lastCommittedUrl
                lastCommittedUrl = route.url
                _uiState.value = _uiState.value.copy(currentUrl = route.url)
                if (
                    !wasInitialPage &&
                    !wasSameCommittedPage
                ) {
                    emitAction(WebPageAction.OpenPage(newActionId(), route, replaceCurrent = true))
                }
            }
        }
    }

    fun onSessionExpired() {
        sessionRepository?.markSessionExpired()
        emitAction(WebPageAction.SessionExpired(newActionId(), AuthContract.LOGIN_URL))
    }

    fun onError(error: WebPageError) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = error,
        )
    }

    /** Marks one rendered action complete; stale IDs cannot clear a newer action. */
    fun onActionHandled(id: String) {
        val action = _uiState.value.pendingAction
        if (action?.id == id) {
            navigationDestination(action)?.let { lastHandledNavigationUrl = it }
            _uiState.value = _uiState.value.copy(pendingAction = null)
        }
    }

    /** Descriptive alias used by Activity integrations. */
    fun acknowledgeAction(id: String) = onActionHandled(id)

    private fun emitAction(action: WebPageAction) {
        val current = _uiState.value.pendingAction
        if (current != null) {
            // One pending action at a time prevents rapid taps and lifecycle
            // re-delivery from creating duplicate Activity instances.
            if (sameDestination(current, action)) return
            return
        }
        _uiState.value = _uiState.value.copy(pendingAction = action)
    }

    private fun sameDestination(first: WebPageAction, second: WebPageAction): Boolean = when {
        first is WebPageAction.OpenPage && second is WebPageAction.OpenPage ->
            first.route.url == second.route.url
        first is WebPageAction.OpenExternal && second is WebPageAction.OpenExternal ->
            first.url == second.url
        first is WebPageAction.SessionExpired && second is WebPageAction.SessionExpired -> true
        first is WebPageAction.Rejected && second is WebPageAction.Rejected ->
            first.reason == second.reason
        else -> false
    }

    private fun navigationDestination(route: WebRoute): String =
        if (route.kind == WebRouteKind.LOGIN) AuthContract.LOGIN_URL else route.url

    private fun navigationDestination(action: WebPageAction): String? = when (action) {
        is WebPageAction.OpenPage -> action.route.url
        is WebPageAction.OpenExternal -> action.url
        is WebPageAction.SessionExpired -> action.loginUrl
        is WebPageAction.Rejected -> null
    }

    private fun newActionId(): String = UUID.randomUUID().toString()

    class Factory(
        private val initialUrl: String,
        private val routePolicy: RoutePolicy = RoutePolicy(),
        private val sessionRepository: WebSessionRepositoryContract? = null,
        private val create: (String, RoutePolicy, WebSessionRepositoryContract?) -> WebPageViewModel =
            { url, policy, repository -> WebPageViewModel(url, policy, repository) },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(WebPageViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
            }
            return create(initialUrl, routePolicy, sessionRepository) as T
        }
    }
}
