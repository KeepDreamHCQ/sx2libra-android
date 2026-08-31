package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.WebRouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {
    private val policy = RoutePolicy()

    @Test
    fun classifiesKnownSiteRoutesAndPreservesQueryAndFragment() {
        assertEquals(WebRouteKind.HOME, policy.classify("https://2libra.com/").kind)
        assertEquals(
            WebRouteKind.POST_DETAIL,
            policy.classify("https://2libra.com/post/android/abc?commentId=9#reply").kind,
        )
        assertEquals(
            "https://2libra.com/post/android/abc?commentId=9#reply",
            policy.normalize("https://2libra.com/post/android/abc?commentId=9#reply"),
        )
        assertEquals(WebRouteKind.LOGIN, policy.classify("https://2libra.com/auth/login").kind)
        assertEquals(
            WebRouteKind.PROFILE,
            policy.classify("https://2libra.com/user/suixin/about").kind,
        )
        assertEquals(
            "https://2libra.com/user/suixin/about",
            com.suixin.sx2libra.model.AuthContract.profileUrl("suixin"),
        )
    }

    @Test
    fun rejectsUnsupportedTopLevelSchemesAndForgedHosts() {
        assertEquals(WebRouteKind.INVALID, policy.classify("http://2libra.com/").kind)
        assertEquals(WebRouteKind.INVALID, policy.classify("file:///tmp/page").kind)
        assertEquals(WebRouteKind.EXTERNAL, policy.classify("https://2libra.com.evil.example/").kind)
        assertFalse(policy.isAllowedPageUrl("https://2libra.com.evil.example/"))
        assertNull(policy.normalize("https://2libra.com.evil.example/"))
    }

    @Test
    fun preservesEncodedPathAndRejectsEncodedSeparatorsAndTraversal() {
        assertEquals(
            "https://2libra.com/node/a%20b",
            policy.normalize("https://2libra.com/node/a%20b"),
        )
        assertEquals(WebRouteKind.INVALID, policy.classify("https://2libra.com/node/a%2Fb").kind)
        assertEquals(WebRouteKind.INVALID, policy.classify("https://2libra.com/node/%2e%2e/admin").kind)
        assertEquals(WebRouteKind.INVALID, policy.classify("https://2libra.com/node/../admin").kind)
    }

    @Test
    fun rejectsCredentialsAndAlternateAuthenticatedPorts() {
        assertEquals(WebRouteKind.INVALID, policy.classify("https://user:pass@2libra.com/").kind)
        assertEquals(WebRouteKind.HOME, policy.classify("https://2libra.com:443/").kind)
        assertEquals(WebRouteKind.INVALID, policy.classify("https://2libra.com:8443/").kind)
        assertEquals(WebRouteKind.HOME, policy.classify("HTTPS://2LIBRA.COM/").kind)
    }

    @Test
    fun allowsOnlySameBusinessPathRedirect() {
        assertTrue(
            policy.isAllowedSamePageRedirect(
                "https://2libra.com/post/latest",
                "https://2libra.com/post/latest?page=2",
            ),
        )
        assertFalse(
            policy.isAllowedSamePageRedirect(
                "https://2libra.com/post/latest",
                "https://2libra.com/auth/login",
            ),
        )
        assertFalse(
            policy.isAllowedSamePageRedirect(
                "https://2libra.com/",
                "https://2libra.com/post/latest",
            ),
        )
    }

    @Test
    fun keepsValidPostListPaginationInTheCurrentWebView() {
        assertTrue(
            policy.isAllowedInlinePaginationNavigation(
                "https://2libra.com/",
                "https://2libra.com/?p=2",
            ),
        )
        assertTrue(
            policy.isAllowedInlinePaginationNavigation(
                "https://2libra.com/node/android?p=2",
                "https://2libra.com/node/android?p=5",
            ),
        )
        assertTrue(
            policy.isAllowedInlinePaginationNavigation(
                "https://2libra.com/post/latest?p=1",
                "https://2libra.com/post/latest",
            ),
        )
    }

    @Test
    fun resetsOnlyValidPaginatedPostListUrlsToPageOne() {
        assertEquals(
            "https://2libra.com/post/latest?p=1#top",
            policy.paginationPageOneUrl("https://2libra.com/post/latest?p=5#top"),
        )
        assertEquals(
            "https://2libra.com/node/android?p=1",
            policy.paginationPageOneUrl("https://2libra.com/node/android?p=2"),
        )
        assertNull(policy.paginationPageOneUrl("https://2libra.com/post/latest"))
        assertNull(policy.paginationPageOneUrl("https://2libra.com/post/latest?p=1"))
    }

    @Test
    fun rejectsUnsafePaginationNavigation() {
        val initial = "https://2libra.com/post/latest"
        assertFalse(
            policy.isAllowedInlinePaginationNavigation(
                initial,
                "https://2libra.com/post/hot/today?p=2",
            ),
        )
        assertFalse(
            policy.isAllowedInlinePaginationNavigation(
                initial,
                "https://example.com/post/latest?p=2",
            ),
        )
        listOf(
            "https://2libra.com/post/latest?p=0",
            "https://2libra.com/post/latest?p=-1",
            "https://2libra.com/post/latest?p=abc",
            "https://2libra.com/post/latest?p=2&p=3",
            "https://2libra.com/post/latest?p=2&sort=hot",
        ).forEach { target ->
            assertFalse(policy.isAllowedInlinePaginationNavigation(initial, target))
            assertNull(policy.paginationPageOneUrl(target))
        }
    }

    @Test
    fun keepsTheFiveProfileTabsInTheSameWebView() {
        val initial = "https://2libra.com/user/suixin/about"
        listOf("about", "post", "comment", "favorites", "history").forEach { tab ->
            assertTrue(
                policy.isAllowedInlineProfileNavigation(
                    initial,
                    "https://2libra.com/user/suixin/$tab",
                ),
            )
        }
        assertFalse(
            policy.isAllowedInlineProfileNavigation(
                initial,
                "https://2libra.com/user/another-user/post",
            ),
        )
        assertFalse(
            policy.isAllowedInlineProfileNavigation(
                initial,
                "https://2libra.com/post/android/abc",
            ),
        )
    }

    @Test
    fun limitsLoginFlowExternalNavigationToGoogleAuthorizationHost() {
        assertTrue(
            policy.isAllowedLoginFlowUrl(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=test",
            ),
        )
        assertTrue(policy.isAllowedLoginFlowUrl("https://2libra.com/auth/login"))
        assertFalse(policy.isAllowedLoginFlowUrl("https://example.com/"))
        assertFalse(policy.isAllowedLoginFlowUrl("http://accounts.google.com/"))
    }

    @Test
    fun scopesUploadCapabilityToComposerRoutes() {
        assertTrue(policy.isPostComposerUrl("https://2libra.com/post/create"))
        assertTrue(policy.isPostComposerUrl("https://2libra.com/post/android/abc/edit"))
        assertFalse(policy.isPostComposerUrl("https://2libra.com/post/android/abc"))
        assertFalse(policy.isPostComposerUrl("https://2libra.com/"))
        assertTrue(policy.isPostDetailUrl("https://2libra.com/post/android/abc"))
    }
}
