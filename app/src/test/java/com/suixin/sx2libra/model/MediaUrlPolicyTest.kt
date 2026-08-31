package com.suixin.sx2libra.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlPolicyTest {
    @Test
    fun imagePreviewAcceptsAnyValidHttpsResource() {
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://r2.2libra.com/i/photo.webp"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://R2.2LIBRA.COM/i/photo.webp"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://r2.2libra.com/avatars/photo.webp"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://pub.mini-tools.uk/30-day/photo.jpg"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://picui.ogmua.cn/photo.webp?size=large"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://cdn.example.test:443/photo.png"))
        assertFalse(MediaUrlPolicy.isAllowedImageUrl("http://r2.2libra.com/i/photo.webp"))
        assertFalse(MediaUrlPolicy.isAllowedImageUrl("https://user:pass@example.test/photo.webp"))
        assertFalse(MediaUrlPolicy.isAllowedImageUrl("https://example.test:8443/photo.webp"))
        assertFalse(MediaUrlPolicy.isAllowedImageUrl("https://example.test"))
    }

    @Test
    fun imagePreviewAcceptsProxyVariantsAndDirectCommentImages() {
        val proxy = "https://wsrv.nl/?url=https%3A%2F%2Fi.mij.rip%2F2026%2F08%2F31%2Fcomment.png" +
            "&default=https%3A%2F%2Fi.mij.rip%2F2026%2F08%2F31%2Fcomment.png"

        assertTrue(MediaUrlPolicy.isAllowedImageUrl(proxy))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("$proxy&width=100"))
        assertTrue(MediaUrlPolicy.isAllowedImageUrl("https://i.mij.rip/2026/08/31/comment.png"))
    }

    @Test
    fun videoAndVttRequireVideoPathAndMime() {
        assertTrue(MediaUrlPolicy.isAllowedVideoUrl("https://r2.2libra.com/video/a.mp4", "video/mp4"))
        assertTrue(MediaUrlPolicy.isAllowedVttUrl("https://r2.2libra.com/video/a.vtt"))
        assertFalse(MediaUrlPolicy.isAllowedVideoUrl("https://r2.2libra.com/i/a.mp4", "video/mp4"))
        assertFalse(MediaUrlPolicy.isAllowedVttUrl("https://2libra.com/video/a.vtt"))
        assertFalse(MediaUrlPolicy.isAllowedVideoUrl("https://r2.2libra.com/video/a.mp4", "text/plain"))
    }
}
