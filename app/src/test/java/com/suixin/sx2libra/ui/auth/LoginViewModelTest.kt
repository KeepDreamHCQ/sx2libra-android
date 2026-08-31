package com.suixin.sx2libra.ui.auth

import com.suixin.sx2libra.data.platform.WebCookieDataSource
import com.suixin.sx2libra.data.repository.WebSessionRepository
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun googleOAuthIntermediatePageStaysInLoginFlow() = runTest {
        val viewModel = LoginViewModel(
            sessionRepository = WebSessionRepository(FakeCookieSource(AuthState.LOGGED_IN)),
        )

        val providerUrl = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        viewModel.onPageCommitted(providerUrl)
        viewModel.onPageFinished(providerUrl)

        assertNull(viewModel.uiState.value.pendingAction)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun confirmedSameOriginLandingEmitsCompletionAction() = runTest {
        val viewModel = LoginViewModel(
            sessionRepository = WebSessionRepository(FakeCookieSource(AuthState.LOGGED_IN)),
        )

        viewModel.onPageCommitted(AuthContract.SITE_HOME_URL)

        assertTrue(viewModel.uiState.value.pendingAction is LoginAction.Completed)
    }

    @Test
    fun unrelatedExternalPageIsRejectedWithoutLaunchingBrowser() = runTest {
        val viewModel = LoginViewModel(
            sessionRepository = WebSessionRepository(FakeCookieSource(AuthState.LOGGED_IN)),
        )

        viewModel.onPageCommitted("https://example.com/")

        assertEquals(LoginError.INVALID_REDIRECT, viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.pendingAction)
    }

    private class FakeCookieSource(
        private val state: AuthState,
    ) : WebCookieDataSource {
        override fun readAuthState(): AuthState = state
        override fun flush() = Unit
        override fun clearSession() = Unit
    }
}
