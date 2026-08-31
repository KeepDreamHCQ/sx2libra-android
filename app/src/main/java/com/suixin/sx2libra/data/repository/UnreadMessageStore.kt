package com.suixin.sx2libra.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UnreadMessageState(
    val count: Int = 0,
    val acknowledgedCount: Int = 0,
) {
    val hasUnacknowledgedMessages: Boolean
        get() = count > 0 && count > acknowledgedCount
}

/** Process-local unread-message state reported by the authenticated page shell. */
object UnreadMessageStore {
    private val _state = MutableStateFlow(UnreadMessageState())

    val state: StateFlow<UnreadMessageState> = _state.asStateFlow()

    @Synchronized
    fun update(count: Int) {
        val normalized = count.coerceAtLeast(0)
        val current = _state.value
        _state.value = if (normalized == 0) {
            UnreadMessageState()
        } else {
            current.copy(
                count = normalized,
                acknowledgedCount = current.acknowledgedCount.coerceAtMost(normalized),
            )
        }
    }

    @Synchronized
    fun markRead() {
        val current = _state.value
        _state.value = current.copy(acknowledgedCount = current.count)
    }

    @Synchronized
    fun clear() {
        _state.value = UnreadMessageState()
    }
}
