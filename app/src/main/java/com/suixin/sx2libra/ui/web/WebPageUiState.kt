package com.suixin.sx2libra.ui.web

import com.suixin.sx2libra.model.WebRoute

enum class WebPageError {
    INVALID_INITIAL_URL,
    INVALID_NAVIGATION,
    SSL_ERROR,
    NETWORK_ERROR,
    RENDERER_GONE,
}

/** Visual state of the optional non-interactive page snapshot overlay. */
enum class WebPageSnapshot {
    DISABLED,
    CHECKING,
    NONE,
    SHOWING,
    FALLBACK,
}

sealed interface WebPageAction {
    val id: String

    data class OpenPage(
        override val id: String,
        val route: WebRoute,
        /** True when a committed POST redirect replaced this Activity's page. */
        val replaceCurrent: Boolean = false,
    ) : WebPageAction

    data class OpenExternal(
        override val id: String,
        val url: String,
        val replaceCurrent: Boolean = false,
    ) : WebPageAction

    data class SessionExpired(
        override val id: String,
        val loginUrl: String,
        val replaceCurrent: Boolean = false,
    ) : WebPageAction

    data class Rejected(
        override val id: String,
        val reason: String,
    ) : WebPageAction
}

data class WebPageUiState(
    val initialUrl: String,
    val currentUrl: String? = null,
    val isLoading: Boolean = true,
    val progress: Int = 0,
    val error: WebPageError? = null,
    val pendingAction: WebPageAction? = null,
    val snapshot: WebPageSnapshot = WebPageSnapshot.DISABLED,
    val snapshotFallbackId: Long? = null,
)
