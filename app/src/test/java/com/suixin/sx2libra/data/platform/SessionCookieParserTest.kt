package com.suixin.sx2libra.data.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieParserTest {
    private val name = "2libra_session"

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
