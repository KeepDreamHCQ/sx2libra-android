package com.suixin.sx2libra.model

/**
 * The locally observable authentication state.
 *
 * [LOGGED_IN] is only a cookie candidate.  The server remains the authority and
 * a redirect to the login route changes the state back to [LOGGED_OUT].
 */
enum class AuthState {
    UNKNOWN,
    LOGGED_OUT,
    LOGGED_IN,
}

/** The protected root pages exposed by the native bottom navigation. */
enum class ProtectedRootTab {
    NOTIFICATIONS,
    PROFILE,
}

/**
 * Server-provided session-cookie configuration.
 *
 * The cookie name must be the exact name used by the 2Libra server.  It is
 * intentionally kept in one value object so a future server rename cannot
 * result in scattered, broad cookie checks.
 */
data class SessionCookieConfig(
    val cookieName: String = DEFAULT_SESSION_COOKIE_NAME,
    val cookieUrl: String = AuthContract.COOKIE_SCOPE_URL,
) {
    init {
        require(cookieName.isNotBlank()) { "cookieName must not be blank" }
        require(cookieName.none { it == ';' || it == '=' || it.isWhitespace() }) {
            "cookieName must be a single cookie token"
        }
        require(cookieUrl == AuthContract.SITE_ORIGIN || cookieUrl == AuthContract.COOKIE_SCOPE_URL) {
            "session cookies must be checked only at the 2Libra origin"
        }
    }

    companion object {
        /** The exact session cookie set by 2Libra after authentication. */
        const val DEFAULT_SESSION_COOKIE_NAME: String = "access_token"
    }
}

/** Stable keys shared by native navigation and LoginActivity. */
object AuthContract {
    const val SITE_ORIGIN: String = "https://2libra.com"
    const val COOKIE_SCOPE_URL: String = "$SITE_ORIGIN/"
    const val SITE_HOME_URL: String = "$SITE_ORIGIN/"
    const val LOGIN_URL: String = "$SITE_ORIGIN/auth/login"
    const val NOTIFICATIONS_URL: String = "$SITE_ORIGIN/notifications"
    /** Legacy settings route retained for callers that do not have a user yet. */
    const val PROFILE_URL: String = "$SITE_ORIGIN/user/setting/profile"
    const val MAX_USERNAME_LENGTH: Int = 64

    const val EXTRA_INITIAL_URL: String =
        "com.suixin.sx2libra.auth.extra.INITIAL_URL"
    const val EXTRA_PROTECTED_TAB: String =
        "com.suixin.sx2libra.auth.extra.PROTECTED_TAB"
    const val EXTRA_LOGIN_RESULT: String =
        "com.suixin.sx2libra.auth.extra.LOGIN_RESULT"
    const val LOGIN_RESULT_SUCCESS: String = "success"

    fun profileUrl(username: String): String {
        require(isValidUsername(username)) { "username is not a valid 2Libra path segment" }
        return "$SITE_ORIGIN/user/$username/about"
    }

    fun isValidUsername(username: String): Boolean =
        username.length in 1..MAX_USERNAME_LENGTH &&
            username != "." &&
            username != ".." &&
            username.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '~' }

    fun urlFor(tab: ProtectedRootTab, username: String? = null): String = when (tab) {
        ProtectedRootTab.NOTIFICATIONS -> NOTIFICATIONS_URL
        ProtectedRootTab.PROFILE -> username?.let(::profileUrl) ?: PROFILE_URL
    }
}
