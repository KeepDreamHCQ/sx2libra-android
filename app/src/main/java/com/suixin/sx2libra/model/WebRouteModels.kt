package com.suixin.sx2libra.model

/** Classification produced by RoutePolicy for a top-level navigation request. */
enum class WebRouteKind {
    HOME,
    POST_LIST,
    NOTIFICATIONS,
    PROFILE,
    LOGIN,
    CREATE_POST,
    POST_DETAIL,
    SITE_PAGE,
    EXTERNAL,
    INVALID,
}

/**
 * A URL after it has been parsed and normalized by RoutePolicy.
 *
 * The value contains no WebView, Context or cookie data, so it can safely cross
 * the View/WebView boundary and be stored in a ViewModel state.
 */
data class WebRoute(
    val kind: WebRouteKind,
    val url: String,
    val nodeSlug: String? = null,
    val postId: String? = null,
    val reason: String? = null,
) {
    val isValid: Boolean
        get() = kind != WebRouteKind.INVALID

    val isSitePage: Boolean
        get() = kind.isSitePage

    companion object {
        fun invalid(reason: String, originalUrl: String = ""): WebRoute = WebRoute(
            kind = WebRouteKind.INVALID,
            url = "",
            reason = reason,
        )
    }
}

private val WebRouteKind.isSitePage: Boolean
    get() = this == WebRouteKind.HOME ||
        this == WebRouteKind.POST_LIST ||
        this == WebRouteKind.NOTIFICATIONS ||
        this == WebRouteKind.PROFILE ||
        this == WebRouteKind.LOGIN ||
        this == WebRouteKind.CREATE_POST ||
        this == WebRouteKind.POST_DETAIL ||
        this == WebRouteKind.SITE_PAGE
