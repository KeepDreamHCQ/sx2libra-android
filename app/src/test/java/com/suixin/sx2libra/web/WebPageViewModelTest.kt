package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.ui.web.WebPageAction
import com.suixin.sx2libra.ui.web.WebPageViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageViewModelTest {
    @Test
    fun navigationCreatesOneFreshActivityActionAndDeduplicatesUntilHandled() {
        val viewModel = WebPageViewModel("https://2libra.com/")

        viewModel.onNavigationRequested("https://2libra.com/post/node/id")
        val first = viewModel.uiState.value.pendingAction
        assertTrue(first is WebPageAction.OpenPage)
        assertEquals(WebRouteKind.POST_DETAIL, (first as WebPageAction.OpenPage).route.kind)

        viewModel.onNavigationRequested("https://2libra.com/post/node/id")
        assertEquals(first, viewModel.uiState.value.pendingAction)

        viewModel.onActionHandled(first.id)
        assertEquals(null, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun lateNonGestureCallbackAfterNavigationIsHandledDoesNotOpenTheSamePageAgain() {
        val viewModel = WebPageViewModel("https://2libra.com/")
        val target = "https://2libra.com/post/node/id"

        viewModel.onNavigationRequested(target, hasUserGesture = true)
        val action = viewModel.uiState.value.pendingAction!!
        viewModel.onActionHandled(action.id)

        viewModel.onNavigationRequested(target, hasUserGesture = false)
        assertEquals(null, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun lateCommitCallbackAfterNavigationIsHandledDoesNotOpenTheSamePageAgain() {
        val viewModel = WebPageViewModel("https://2libra.com/")
        val target = "https://2libra.com/post/node/id"

        viewModel.onNavigationRequested(target, hasUserGesture = true)
        val action = viewModel.uiState.value.pendingAction!!
        viewModel.onActionHandled(action.id)

        viewModel.onPageCommitted(target)
        assertEquals(null, viewModel.uiState.value.pendingAction)
    }

    @Test
    fun explicitGestureCanOpenTheSamePageAgainAfterThePreviousActionWasHandled() {
        val viewModel = WebPageViewModel("https://2libra.com/")
        val target = "https://2libra.com/post/node/id"

        viewModel.onNavigationRequested(target, hasUserGesture = true)
        val first = viewModel.uiState.value.pendingAction!!
        viewModel.onActionHandled(first.id)

        viewModel.onNavigationRequested(target, hasUserGesture = true)
        assertTrue(viewModel.uiState.value.pendingAction is WebPageAction.OpenPage)
    }

    @Test
    fun committedPostRedirectMarksSourceForReplacement() {
        val viewModel = WebPageViewModel("https://2libra.com/post/create")

        viewModel.onPageCommitted("https://2libra.com/post/node/id")
        val action = viewModel.uiState.value.pendingAction
        assertNotNull(action)
        assertTrue(action is WebPageAction.OpenPage)
        assertTrue((action as WebPageAction.OpenPage).replaceCurrent)
    }

    @Test
    fun invalidAndExternalRoutesNeverBecomeWebPageInitialUrls() {
        val viewModel = WebPageViewModel("https://2libra.com/")

        viewModel.onNavigationRequested("https://2libra.com.evil.example/")
        assertTrue(viewModel.uiState.value.pendingAction is WebPageAction.OpenExternal)

        viewModel.onActionHandled(viewModel.uiState.value.pendingAction!!.id)
        viewModel.onNavigationRequested("http://2libra.com/")
        assertTrue(viewModel.uiState.value.pendingAction is WebPageAction.Rejected)
        assertFalse(viewModel.uiState.value.pendingAction is WebPageAction.OpenPage)
    }
}
