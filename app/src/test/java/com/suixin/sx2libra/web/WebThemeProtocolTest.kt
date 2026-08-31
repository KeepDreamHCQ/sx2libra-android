package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.WebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebThemeProtocolTest {
    private val requestId = "123e4567-e89b-42d3-a456-426614174000"
    private val source = BridgeSource(
        sourceOrigin = "https://2libra.com",
        isMainFrame = true,
        currentUrl = "https://2libra.com/post/latest",
        hasUserGesture = false,
    )

    @Test
    fun acceptsOnlyTheStrictLightAndDarkThemeMessages() {
        val dark = message("dark")
        val light = message("light")

        assertTrue(WebThemeProtocol.isThemeMessage(dark))
        assertEquals(WebTheme.DARK, WebThemeProtocol.parse(dark, source))
        assertEquals(WebTheme.LIGHT, WebThemeProtocol.parse(light, source))
    }

    @Test
    fun acceptsThemeMessagesOnTheLoginPageButNotOAuthPages() {
        val loginSource = source.copy(currentUrl = "https://2libra.com/auth/login")
        val oauthSource = source.copy(currentUrl = "https://accounts.google.com/o/oauth2/auth")

        assertEquals(WebTheme.DARK, WebThemeProtocol.parse(message("dark"), loginSource))
        assertNull(WebThemeProtocol.parse(message("dark"), oauthSource))
    }

    @Test
    fun rejectsWrongOriginFrameAndPageUrl() {
        val wrongOrigin = source.copy(sourceOrigin = "https://2libra.com/")
        val childFrame = source.copy(isMainFrame = false)
        val forgedHost = source.copy(currentUrl = "https://2libra.com.evil.test/")
        val httpPage = source.copy(currentUrl = "http://2libra.com/")

        assertNull(WebThemeProtocol.parse(message("dark"), wrongOrigin))
        assertNull(WebThemeProtocol.parse(message("dark"), childFrame))
        assertNull(WebThemeProtocol.parse(message("dark"), forgedHost))
        assertNull(WebThemeProtocol.parse(message("dark"), httpPage))
    }

    @Test
    fun rejectsMalformedIdsModesVersionsAndExtraFields() {
        assertNull(WebThemeProtocol.parse(message("blue"), source))
        assertNull(WebThemeProtocol.parse(message("dark", version = 2), source))
        assertNull(WebThemeProtocol.parse(message("dark", requestId = "not-a-uuid"), source))
        assertNull(
            WebThemeProtocol.parse(
                message("dark", payloadSuffix = ",\"extra\":true"),
                source,
            ),
        )
        assertNull(
            WebThemeProtocol.parse(
                message("dark", rootSuffix = ",\"extra\":true"),
                source,
            ),
        )
        assertNull(
            WebThemeProtocol.parse(
                "{\"version\":1,\"requestId\":\"$requestId\",\"requestId\":\"$requestId\",\"action\":\"theme_changed\",\"payload\":{\"mode\":\"dark\"}}",
                source,
            ),
        )
        assertFalse(WebThemeProtocol.isThemeMessage("{not-json}"))
    }

    private fun message(
        mode: String,
        version: Int = 1,
        requestId: String = this.requestId,
        payloadSuffix: String = "",
        rootSuffix: String = "",
    ): String =
        "{\"version\":$version,\"requestId\":\"$requestId\",\"action\":\"theme_changed\",\"payload\":{\"mode\":\"$mode\"$payloadSuffix}$rootSuffix}"
}
