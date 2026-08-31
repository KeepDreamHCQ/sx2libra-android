package com.suixin.sx2libra.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.suixin.sx2libra.data.repository.WebSessionRepositoryContract
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.AuthState
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.web.RoutePolicy
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ViewModel for the one login Activity; it owns no Context or WebView. */
class LoginViewModel(
    initialUrl: String = AuthContract.LOGIN_URL,
    private val sessionRepository: WebSessionRepositoryContract,
    private val routePolicy: RoutePolicy = RoutePolicy(),
) : ViewModel() {
    private val normalizedInitialUrl = routePolicy.normalize(initialUrl)
        ?.takeIf { routePolicy.isLoginUrl(it) }
        ?: throw IllegalArgumentException("LoginActivity initialUrl must be /auth/login")

    private val _uiState = MutableStateFlow(LoginUiState(normalizedInitialUrl))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onPageStarted(url: String?) {
        _uiState.value = _uiState.value.copy(
            currentUrl = routePolicy.normalize(url) ?: url,
            isLoading = true,
            error = null,
        )
    }

    fun onProgressChanged(progress: Int) {
        _uiState.value = _uiState.value.copy(progress = progress.coerceIn(0, 100))
    }

    fun onPageFinished(url: String?) {
        _uiState.value = _uiState.value.copy(
            currentUrl = routePolicy.normalize(url) ?: url,
            isLoading = false,
            progress = 100,
        )
        // OAuth provider pages are intermediate login-flow documents. Only a
        // same-origin landing page can prove that the site's login completed.
        if (routePolicy.classify(url).isSitePage) refreshAfterPage(url)
    }

    /**
     * Login success is accepted only after a non-/auth landing page and an
     * exact session-cookie refresh.  A cookie candidate alone is never sent
     * to the caller; only the success action leaves this ViewModel.
     */
    fun onPageCommitted(url: String?) {
        val route = routePolicy.classify(url)
        when (route.kind) {
            WebRouteKind.LOGIN -> {
                _uiState.value = _uiState.value.copy(currentUrl = route.url)
                refreshAfterPage(route.url)
            }

            WebRouteKind.EXTERNAL -> {
                if (!routePolicy.isAllowedLoginFlowUrl(route.url)) {
                    _uiState.value = _uiState.value.copy(error = LoginError.INVALID_REDIRECT)
                }
            }

            WebRouteKind.INVALID -> {
                _uiState.value = _uiState.value.copy(error = LoginError.INVALID_REDIRECT)
            }

            else -> {
                // /auth/* pages (for example a password-reset screen) are not
                // proof that the login flow completed.
                val isAuthRoute = route.url.removePrefix(AuthContract.SITE_ORIGIN)
                    .substringBefore('?')
                    .substringBefore('#')
                    .startsWith("/auth/")
                if (!isAuthRoute) refreshAfterPage(route.url)
            }
        }
    }

    fun onError(error: LoginError) {
        _uiState.value = _uiState.value.copy(isLoading = false, error = error)
    }

    fun onActionHandled(id: String) {
        if (_uiState.value.pendingAction?.id == id) {
            _uiState.value = _uiState.value.copy(pendingAction = null)
        }
    }

    private fun refreshAfterPage(url: String?) {
        viewModelScope.launch {
            val route = routePolicy.classify(url)
            if (!route.isSitePage) return@launch
            val authState = runCatching { sessionRepository.refreshAuthState() }
                .getOrDefault(AuthState.UNKNOWN)
            val isAuthRoute = route.url.removePrefix(AuthContract.SITE_ORIGIN)
                .substringBefore('?')
                .substringBefore('#')
                .startsWith("/auth/")
            if (authState == AuthState.LOGGED_IN && route.kind != WebRouteKind.LOGIN && !isAuthRoute) {
                sessionRepository.flushSession()
                emitCompletion()
            } else if (route.kind != WebRouteKind.LOGIN && !isAuthRoute) {
                _uiState.value = _uiState.value.copy(error = LoginError.SESSION_NOT_CONFIRMED)
            }
        }
    }

    private fun emitCompletion() {
        if (_uiState.value.pendingAction == null) {
            _uiState.value = _uiState.value.copy(
                pendingAction = LoginAction.Completed(UUID.randomUUID().toString()),
            )
        }
    }

    class Factory(
        private val initialUrl: String = AuthContract.LOGIN_URL,
        private val sessionRepository: WebSessionRepositoryContract,
        private val routePolicy: RoutePolicy = RoutePolicy(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(LoginViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
            }
            return LoginViewModel(initialUrl, sessionRepository, routePolicy) as T
        }
    }
}
