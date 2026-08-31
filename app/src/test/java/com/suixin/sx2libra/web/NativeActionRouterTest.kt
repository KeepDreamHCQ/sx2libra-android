package com.suixin.sx2libra.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeActionRouterTest {
    @Test
    fun retryIsDelegatedWithRequestAndClientId() {
        val requestId = "123e4567-e89b-42d3-a456-426614174000"
        val clientId = "223e4567-e89b-42d3-a456-426614174000"
        var delegated: Pair<String, String>? = null
        val navigation = object : NavigationActionDelegate {
            override fun openPage(requestId: String, url: String) = true
            override fun openPost(requestId: String, url: String) = true
            override fun sharePost(requestId: String, url: String, title: String?) = true
            override fun openExternal(requestId: String, url: String) = true
        }
        val media = object : MediaActionDelegate {
            override fun previewImages(requestId: String, urls: List<String>, initialIndex: Int) = true
            override fun playVideo(
                requestId: String,
                url: String,
                mimeType: String,
                title: String?,
                posterUrl: String?,
                previewVttUrl: String?,
            ) = true
        }
        val uploads = object : ImageUploadActionDelegate, RetryableImageUploadActionDelegate {
            override fun pickAndUploadImages(requestId: String, uploadTicket: String) = true
            override fun retryImageUpload(requestId: String, clientId: String): Boolean {
                delegated = requestId to clientId
                return true
            }
        }
        val router = NativeActionRouter(navigation, media, uploads)
        val reply = router.route(
            "{\"version\":1,\"requestId\":\"$requestId\",\"action\":\"retry_image_upload\",\"payload\":{\"clientId\":\"$clientId\"}}",
            BridgeSource(
                sourceOrigin = "https://2libra.com",
                isMainFrame = true,
                currentUrl = "https://2libra.com/post/create",
                hasUserGesture = true,
            ),
        )
        assertTrue(reply.ok)
        assertEquals(requestId to clientId, delegated)
    }

    @Test
    fun gestureGatedNavigationRejectsBridgeHistoryWithoutNativeGesture() {
        val requestId = "123e4567-e89b-42d3-a456-426614174000"
        var openPageCalls = 0
        val navigation = object : NavigationActionDelegate {
            override fun openPage(requestId: String, url: String): Boolean {
                openPageCalls++
                return true
            }

            override fun openPost(requestId: String, url: String) = true
            override fun sharePost(requestId: String, url: String, title: String?) = true
            override fun openExternal(requestId: String, url: String) = true
        }
        val media = object : MediaActionDelegate {
            override fun previewImages(requestId: String, urls: List<String>, initialIndex: Int) = true
            override fun playVideo(
                requestId: String,
                url: String,
                mimeType: String,
                title: String?,
                posterUrl: String?,
                previewVttUrl: String?,
            ) = true
        }
        val uploads = object : ImageUploadActionDelegate {
            override fun pickAndUploadImages(requestId: String, uploadTicket: String) = true
        }
        val router = NativeActionRouter(
            navigation = navigation,
            media = media,
            uploads = uploads,
            requireUserGestureForNavigation = true,
        )

        val reply = router.route(
            "{\"version\":1,\"requestId\":\"$requestId\",\"action\":\"open_page\",\"payload\":{\"url\":\"https://2libra.com/post/hot/today\"}}",
            BridgeSource(
                sourceOrigin = "https://2libra.com",
                isMainFrame = true,
                currentUrl = "https://2libra.com/",
                hasUserGesture = false,
            ),
        )

        assertEquals(BridgeErrorCode.USER_GESTURE_REQUIRED, reply.error)
        assertEquals(0, openPageCalls)
    }
}
