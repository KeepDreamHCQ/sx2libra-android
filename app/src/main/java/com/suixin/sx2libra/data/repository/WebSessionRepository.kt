package com.suixin.sx2libra.data.repository

import android.content.Context
import com.suixin.sx2libra.data.platform.CookieManagerWebCookieDataSource
import com.suixin.sx2libra.data.platform.WebCookieDataSource
import com.suixin.sx2libra.model.AuthState
import com.suixin.sx2libra.model.AuthContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Repository boundary consumed by MainViewModel, LoginViewModel and Web pages. */
interface WebSessionRepositoryContract {
    val authState: StateFlow<AuthState>

    fun observeAuthState(): StateFlow<AuthState>

    suspend fun refreshAuthState(): AuthState

    fun flushSession()

    suspend fun logout()

    fun markSessionExpired()
}

/**
 * Single source of truth for the locally observable WebView session state.
 *
 * This repository intentionally has no timer.  Callers refresh at lifecycle
 * and page-boundary events described in android-client-development.md.
 */
class WebSessionRepository(
    private val cookieDataSource: WebCookieDataSource,
) : WebSessionRepositoryContract {
    private val _authState = MutableStateFlow(AuthState.UNKNOWN)

    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override fun observeAuthState(): StateFlow<AuthState> = authState

    override suspend fun refreshAuthState(): AuthState {
        val state = cookieDataSource.readAuthState()
        _authState.value = state
        return state
    }

    override fun flushSession() {
        cookieDataSource.flush()
    }

    override suspend fun logout() {
        // Update immediately so protected-root gates cannot race a logout
        // request.  The data source clears the WebView profile afterwards.
        _authState.value = AuthState.LOGGED_OUT
        cookieDataSource.clearSession()
        cookieDataSource.flush()
    }

    override fun markSessionExpired() {
        _authState.value = AuthState.LOGGED_OUT
    }

    /** Called by WebView page callbacks at the authenticated boundary. */
    suspend fun onPageLoaded(url: String?): AuthState {
        val path = url
            ?.removePrefix(AuthContract.SITE_ORIGIN)
            ?.substringBefore('?')
            ?.substringBefore('#')
        if (path == "/auth/login") {
            markSessionExpired()
            return AuthState.LOGGED_OUT
        }
        return refreshAuthState()
    }

    /** Alias for redirect handling from WebPageViewModel. */
    fun onSessionExpired() = markSessionExpired()
}

/** Optional application seam for sharing one repository-scoped AuthState Flow. */
interface WebSessionRepositoryOwner {
    val webSessionRepository: WebSessionRepositoryContract
}

/**
 * Resolves the application-scoped repository when LibraApplication exposes
 * [WebSessionRepositoryOwner], with a safe platform fallback for isolated
 * Activity tests and pre-DI startup.
 */
object WebSessionRepositoryResolver {
    fun resolve(context: Context): WebSessionRepositoryContract {
        val owner = context.applicationContext as? WebSessionRepositoryOwner
        return owner?.webSessionRepository
            ?: WebSessionRepository(CookieManagerWebCookieDataSource())
    }
}
