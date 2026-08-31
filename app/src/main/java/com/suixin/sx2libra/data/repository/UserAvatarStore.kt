package com.suixin.sx2libra.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local avatar URL obtained from the authenticated 2Libra page. */
object UserAvatarStore {
    private val _avatarUrl = MutableStateFlow<String?>(null)

    val avatarUrl: StateFlow<String?> = _avatarUrl.asStateFlow()

    fun update(url: String) {
        _avatarUrl.value = url
    }

    fun clear() {
        _avatarUrl.value = null
    }
}
