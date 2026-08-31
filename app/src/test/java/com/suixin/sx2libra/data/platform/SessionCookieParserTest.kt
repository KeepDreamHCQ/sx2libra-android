package com.suixin.sx2libra.data.platform

import com.suixin.sx2libra.model.SessionCookieConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieParserTest {
    private val name = "2libra_session"

    @Test
    fun defaultConfigurationRecognizesThe2LibraSessionCookie() {
        val config = SessionCookieConfig()

        assertEquals("access_token", config.cookieName)
        assertTrue(
            SessionCookieParser.hasNonEmptyExactCookie(
                "access_token=opaque",
                config.cookieName,
            ),
        )
        assertFalse(
            SessionCookieParser.hasNonEmptyExactCookie(
                "session=opaque",
                config.cookieName,
            ),
        )
    }

    @Test
    fun matchesOnlyTheExactNonEmptyCookieName() {
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie(null, name))
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie("theme=dark", name))
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie("my_2libra_session=x", name))
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie("$name=", name))
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie("$name=   ", name))
        assertTrue(SessionCookieParser.hasNonEmptyExactCookie("theme=dark; $name=opaque", name))
    }

    @Test
    fun splitsOnlyAtTheFirstEquals() {
        assertTrue(SessionCookieParser.hasNonEmptyExactCookie("$name=a=b=c", name))
        assertFalse(SessionCookieParser.hasNonEmptyExactCookie("$name = ; other=1", name))
    }
}
