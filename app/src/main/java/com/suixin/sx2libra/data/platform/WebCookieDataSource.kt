package com.suixin.sx2libra.data.platform

import android.webkit.CookieManager
import com.suixin.sx2libra.model.AuthState
import com.suixin.sx2libra.model.SessionCookieConfig

/** Platform boundary around the process-wide WebView cookie profile. */
interface WebCookieDataSource {
    /** Returns only an AuthState; the cookie header never leaves this boundary. */
    fun readAuthState(): AuthState

    /** Flushes pending WebView cookie writes at a state boundary. */
    fun flush()

    /** Clears the WebView session after the server-side logout flow completes. */
    fun clearSession()
}

/**
 * CookieManager-backed implementation used by every WebView screen.
 *
 * Do not replace [readAuthState] with hasCookies() or a broad cookie check:
 * unrelated theme, analytics and CSRF cookies are also present while logged
 * out.  The configured name is matched exactly and its value is not retained.
 */
class CookieManagerWebCookieDataSource(
    private val config: SessionCookieConfig = SessionCookieConfig(),
    private val cookieManager: CookieManager = CookieManager.getInstance(),
) : WebCookieDataSource {

    constructor(
        cookieManager: CookieManager,
        config: SessionCookieConfig,
    ) : this(config, cookieManager)

    init {
        cookieManager.setAcceptCookie(true)
    }

    override fun readAuthState(): AuthState {
        val cookieHeader = cookieManager.getCookie(config.cookieUrl)
        return if (SessionCookieParser.hasNonEmptyExactCookie(cookieHeader, config.cookieName)) {
            AuthState.LOGGED_IN
        } else {
            AuthState.LOGGED_OUT
        }
    }

    override fun flush() {
        cookieManager.flush()
    }

    override fun clearSession() {
        // Logout is initiated by the website first.  Clearing the complete
        // WebView profile afterwards also removes CSRF/session-adjacent state
        // without ever copying individual cookie values into app storage.
        cookieManager.removeAllCookies(null)
    }
}

/** Descriptive alias for callers that prefer an Android-specific class name. */
typealias AndroidWebCookieDataSource = CookieManagerWebCookieDataSource
typealias DefaultWebCookieDataSource = CookieManagerWebCookieDataSource

/** Pure, side-effect-free exact cookie-header parser for JVM tests. */
object SessionCookieParser {
    fun hasNonEmptyExactCookie(cookieHeader: String?, expectedName: String): Boolean {
        if (cookieHeader.isNullOrBlank() || expectedName.isBlank()) return false
        return cookieHeader.split(';').asSequence().any { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@any false
            val name = token.substring(0, separator).trim()
            if (name != expectedName) return@any false
            token.substring(separator + 1).trim().isNotEmpty()
        }
    }
}
