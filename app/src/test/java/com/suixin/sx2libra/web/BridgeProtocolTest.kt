package com.suixin.sx2libra.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    private val requestId = "123e4567-e89b-42d3-a456-426614174000"
    private val source = BridgeSource(
        sourceOrigin = "https://2libra.com",
        isMainFrame = true,
        currentUrl = "https://2libra.com/post/create",
        hasUserGesture = true
    )

    @Test
    fun validPostAndImageActionsAreTyped() {
        val post = parse("open_post", "{\"url\":\"https://2libra.com/post/android/abc123\"}")
        assertTrue(post is BridgeParseResult.Accepted)
        assertTrue((post as BridgeParseResult.Accepted).request.payload is BridgePayload.OpenPost)

        val preview = parse(
            "preview_images",
            "{\"urls\":[\"https://r2.2libra.com/i/a.webp\"],\"initialIndex\":0}"
        )
        assertTrue(preview is BridgeParseResult.Accepted)

        val commentImage = parse(
            "preview_images",
            "{\"urls\":[\"https://wsrv.nl/?url=https%3A%2F%2Fi.mij.rip%2F2026%2F08%2F31%2Fcomment.png\"],\"initialIndex\":0}"
        )
        assertTrue(commentImage is BridgeParseResult.Accepted)
    }

    @Test
    fun originFrameAndHostAreCheckedBeforeDelegation() {
        val origin = BridgeProtocol.parse(
            message("open_post", "{\"url\":\"https://2libra.com/post/a/b\"}"),
            source.copy(sourceOrigin = "https://2libra.com/")
        )
        assertEquals(BridgeErrorCode.INVALID_ORIGIN, (origin as BridgeParseResult.Rejected).error)

        val frame = BridgeProtocol.parse(
            message("open_post", "{\"url\":\"https://2libra.com/post/a/b\"}"),
            source.copy(isMainFrame = false)
        )
        assertEquals(BridgeErrorCode.INVALID_FRAME, (frame as BridgeParseResult.Rejected).error)

        val host = parse("open_page", "{\"url\":\"https://2libra.com.evil.test/post/a/b\"}")
        assertTrue(host is BridgeParseResult.Rejected)
    }

    @Test
    fun uploadRequiresNativeGestureAndStrictPayload() {
        val noGesture = BridgeProtocol.parse(
            message("pick_and_upload_images", "{\"uploadTicket\":\"short-ticket\"}"),
            source.copy(hasUserGesture = false)
        )
        assertEquals(BridgeErrorCode.USER_GESTURE_REQUIRED, (noGesture as BridgeParseResult.Rejected).error)

        val invalidTicket = parse(
            "pick_and_upload_images",
            "{\"uploadTicket\":\"ticket with spaces\"}"
        )
        assertTrue(invalidTicket is BridgeParseResult.Rejected)

        val detail = BridgeProtocol.parse(
            message("pick_and_upload_images", "{\"uploadTicket\":\"short-ticket\"}"),
            source.copy(currentUrl = "https://2libra.com/post/android/abc123"),
        )
        assertTrue(detail is BridgeParseResult.Accepted)

        val wrongPage = BridgeProtocol.parse(
            message("pick_and_upload_images", "{\"uploadTicket\":\"short-ticket\"}"),
            source.copy(currentUrl = "https://2libra.com/notifications")
        )
        assertEquals(BridgeErrorCode.INVALID_PAYLOAD, (wrongPage as BridgeParseResult.Rejected).error)
    }

    @Test
    fun retryUploadUsesSamePageGestureAndCanonicalClientId() {
        val clientId = "223e4567-e89b-42d3-a456-426614174000"
        val accepted = BridgeProtocol.parse(
            message("retry_image_upload", "{\"clientId\":\"$clientId\"}"),
            source,
        )
        assertTrue(accepted is BridgeParseResult.Accepted)
        assertTrue((accepted as BridgeParseResult.Accepted).request.payload is BridgePayload.RetryImageUpload)

        val noGesture = BridgeProtocol.parse(
            message("retry_image_upload", "{\"clientId\":\"$clientId\"}"),
            source.copy(hasUserGesture = false),
        )
        assertEquals(BridgeErrorCode.USER_GESTURE_REQUIRED, (noGesture as BridgeParseResult.Rejected).error)

        val malformed = BridgeProtocol.parse(
            message("retry_image_upload", "{\"clientId\":\"not-a-uuid\"}"),
            source,
        )
        assertEquals(BridgeErrorCode.INVALID_PAYLOAD, (malformed as BridgeParseResult.Rejected).error)
    }

    @Test
    fun mediaPayloadAcceptsAllHttpsImagesButKeepsExternalNavigationSeparate() {
        val avatar = parse(
            "preview_images",
            "{\"urls\":[\"https://r2.2libra.com/avatars/a.webp\"],\"initialIndex\":0}"
        )
        assertTrue(avatar is BridgeParseResult.Accepted)

        val externalImage = parse(
            "preview_images",
            "{\"urls\":[\"https://pic.mxpy.cn/upload/a.jpg?token=1\"],\"initialIndex\":0}"
        )
        assertTrue(externalImage is BridgeParseResult.Accepted)

        val external = parse("open_external", "{\"url\":\"http://example.test/\"}")
        assertFalse(external is BridgeParseResult.Accepted)
    }

    @Test
    fun malformedAndDuplicateFieldsAreRejectedWithoutEmptyUrlUuidBranch() {
        val malformed = BridgeProtocol.parse(
            "{\"version\":1,\"requestId\":\"$requestId\",\"action\":\"open_page\",\"payload\":{\"url\":}",
            source
        )
        assertEquals(BridgeErrorCode.INVALID_MESSAGE, (malformed as BridgeParseResult.Rejected).error)

        val duplicateRequestId = BridgeProtocol.parse(
            "{\"version\":1,\"requestId\":\"$requestId\",\"requestId\":\"$requestId\",\"action\":\"open_page\",\"payload\":{\"url\":\"https://2libra.com/\"}}",
            source
        )
        assertEquals(BridgeErrorCode.INVALID_MESSAGE, (duplicateRequestId as BridgeParseResult.Rejected).error)

        val emptyUrlUuid = BridgeProtocol.parse(
            message("open_page", "{\"url\":\"https://2libra.com/\"}").replace(requestId, ""),
            source
        )
        assertEquals(BridgeErrorCode.INVALID_REQUEST_ID, (emptyUrlUuid as BridgeParseResult.Rejected).error)
    }

    private fun parse(action: String, payload: String): BridgeParseResult =
        BridgeProtocol.parse(message(action, payload), source)

    private fun message(action: String, payload: String): String =
        "{\"version\":1,\"requestId\":\"$requestId\",\"action\":\"$action\",\"payload\":$payload}"
}
