package com.suixin.sx2libra.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeActionControllerTest {
    @Test
    fun trustedGestureIsAOneShotNativeWindow() {
        val window = TrustedGestureWindow(windowMillis = 1_000L)
        window.record(eventTimeMillis = 10_000L)

        assertTrue(window.peek(nowMillis = 10_999L))
        assertTrue(window.consume(nowMillis = 10_999L))
        assertFalse(window.peek(nowMillis = 10_999L))
        assertFalse(window.consume(nowMillis = 11_000L))
    }

    @Test
    fun expiredGestureCannotAuthorizeUpload() {
        val window = TrustedGestureWindow(windowMillis = 1_000L)
        window.record(eventTimeMillis = 10_000L)

        assertFalse(window.peek(nowMillis = 11_001L))
        assertFalse(window.consume(nowMillis = 11_001L))
    }

    @Test
    fun bridgeDoesNotAcceptPayloadGestureWithoutNativeGesture() {
        val requestId = "123e4567-e89b-42d3-a456-426614174000"
        val raw = """
            {"version":1,"requestId":"$requestId","action":"pick_and_upload_images","payload":{}}
        """.trimIndent()
        val result = BridgeProtocol.parse(
            raw,
            BridgeSource(
                sourceOrigin = "https://2libra.com",
                isMainFrame = true,
                currentUrl = "https://2libra.com/post/create",
                hasUserGesture = false,
            ),
        )

        assertTrue(result is BridgeParseResult.Rejected)
        assertTrue((result as BridgeParseResult.Rejected).error == BridgeErrorCode.USER_GESTURE_REQUIRED)
    }
}
