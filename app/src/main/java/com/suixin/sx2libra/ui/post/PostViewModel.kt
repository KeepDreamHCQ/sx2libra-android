package com.suixin.sx2libra.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.suixin.sx2libra.data.repository.WebSessionRepositoryContract
import com.suixin.sx2libra.ui.web.WebPageViewModel
import com.suixin.sx2libra.web.RoutePolicy

/** Post detail uses the same navigation state machine with a strict URL gate. */
class PostViewModel(
    initialUrl: String,
    routePolicy: RoutePolicy = RoutePolicy(),
    sessionRepository: WebSessionRepositoryContract? = null,
) : WebPageViewModel(initialUrl, routePolicy, sessionRepository) {
    class Factory(
        private val initialUrl: String,
        private val routePolicy: RoutePolicy = RoutePolicy(),
        private val sessionRepository: WebSessionRepositoryContract? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(PostViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
            }
            return PostViewModel(initialUrl, routePolicy, sessionRepository) as T
        }
    }
}

typealias PostUiState = com.suixin.sx2libra.ui.web.WebPageUiState
