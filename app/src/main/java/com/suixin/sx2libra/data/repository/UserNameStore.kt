package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.model.AuthContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local username obtained from the authenticated 2Libra page. */
object UserNameStore {
    private val _username = MutableStateFlow<String?>(null)

    val username: StateFlow<String?> = _username.asStateFlow()

    fun update(value: String): Boolean {
        val normalized = value.trim()
        if (!AuthContract.isValidUsername(normalized)) return false
        _username.value = normalized
        return true
    }

    fun clear() {
        _username.value = null
    }
}
