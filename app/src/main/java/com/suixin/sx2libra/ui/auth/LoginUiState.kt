package com.suixin.sx2libra.ui.auth

enum class LoginError {
    INVALID_INITIAL_URL,
    INVALID_REDIRECT,
    NETWORK_ERROR,
    SSL_ERROR,
    SESSION_NOT_CONFIRMED,
}

sealed interface LoginAction {
    val id: String

    data class Completed(
        override val id: String,
    ) : LoginAction
}

data class LoginUiState(
    val initialUrl: String,
    val currentUrl: String? = null,
    val isLoading: Boolean = true,
    val progress: Int = 0,
    val error: LoginError? = null,
    val pendingAction: LoginAction? = null,
)
