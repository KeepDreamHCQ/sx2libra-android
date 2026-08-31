package com.suixin.sx2libra

import com.suixin.sx2libra.model.ForumMenuSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumMenuSpecTest {
    @Test
    fun defaultMenusHaveStableOrderAndSiteUrls() {
        val menus = ForumMenuSpec.defaultMenus()
        assertEquals(listOf("default-home", "default-today", "default-recent", "default-latest"), menus.map { it.id })
        assertEquals("https://2libra.com/", menus.first().url)
        assertEquals("https://2libra.com/post/latest", menus.last().url)
    }

    @Test
    fun pathNormalizationOnlyAllowsRelativeSitePaths() {
        assertEquals("/node/android", ForumMenuSpec.normalizePath("node/android").getOrThrow())
        assertEquals("/", ForumMenuSpec.normalizePath("").getOrThrow())
        assertEquals("/foo%20bar", ForumMenuSpec.normalizePath("/foo%20bar").getOrThrow())
        assertEquals("https://2libra.com/foo%20bar", ForumMenuSpec.urlForPath("/foo%20bar"))
        assertEquals("https://2libra.com/%E4%B8%AD%E6%96%87", ForumMenuSpec.urlForPath("/中文"))

        listOf(
            "https://2libra.com/evil",
            "//evil.example/",
            "/foo//bar",
            "/foo\\bar",
            "/foo?next=evil",
            "/foo#fragment",
            "/../secret",
            "/foo/%2e%2e/secret",
            "/foo/%2fbar"
        ).forEach { path -> assertFalse("accepted $path", ForumMenuSpec.normalizePath(path).isSuccess) }
    }

    @Test
    fun namesAreTrimmedAndBounded() {
        assertEquals("标题", ForumMenuSpec.normalizeName("  标题 ").getOrThrow())
        assertTrue(ForumMenuSpec.normalizeName("a".repeat(20)).isSuccess)
        assertFalse(ForumMenuSpec.normalizeName("a".repeat(21)).isSuccess)
        assertFalse(ForumMenuSpec.normalizeName("one\ntwo").isSuccess)
    }
}
