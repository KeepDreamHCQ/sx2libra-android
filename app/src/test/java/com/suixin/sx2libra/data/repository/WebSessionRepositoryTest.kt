package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.platform.WebCookieDataSource
import com.suixin.sx2libra.model.AuthState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionRepositoryTest {
    @Test
    fun refreshAndExpiryUpdateTheSingleAuthStateFlow() = runBlocking {
        val source = FakeCookieSource(AuthState.LOGGED_IN)
        val repository = WebSessionRepository(source)

        assertEquals(AuthState.UNKNOWN, repository.authState.value)
        assertEquals(AuthState.LOGGED_IN, repository.refreshAuthState())
        assertEquals(AuthState.LOGGED_IN, repository.authState.value)
        repository.markSessionExpired()
        assertEquals(AuthState.LOGGED_OUT, repository.authState.value)
    }

    @Test
    fun logoutClearsAndFlushesWithoutExposingCookieValues() = runBlocking {
        val source = FakeCookieSource(AuthState.LOGGED_IN)
        val repository = WebSessionRepository(source)

        repository.logout()

        assertEquals(AuthState.LOGGED_OUT, repository.authState.value)
        assertTrue(source.cleared)
        assertEquals(1, source.flushCount)
    }

    private class FakeCookieSource(
        private val state: AuthState,
    ) : WebCookieDataSource {
        var cleared = false
        var flushCount = 0

        override fun readAuthState(): AuthState = state
        override fun flush() { flushCount++ }
        override fun clearSession() { cleared = true }
    }
}
