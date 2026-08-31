package com.suixin.sx2libra.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadModelsTest {
    @Test
    fun staticImagesUseSixMiBLimitAndGifsUseTenMiBLimit() {
        assertTrue(
            ImageUploadLimits.maxBytesForMime(ImageMimeTypes.JPEG) ==
                6L * 1024L * 1024L
        )
        assertTrue(
            ImageUploadLimits.maxBytesForMime(ImageMimeTypes.GIF) ==
                10L * 1024L * 1024L
        )
        assertFalse(ImageUploadLimits.maxBytesForMime("image/avif") > 0L)
    }

    @Test
    fun selectedImageValidationUsesMimeSpecificLimit() {
        val staticImage = image(
            mimeType = ImageMimeTypes.PNG,
            bytes = ImageUploadLimits.MAX_STATIC_IMAGE_BYTES + 1L,
        )
        val gif = image(
            mimeType = ImageMimeTypes.GIF,
            bytes = ImageUploadLimits.MAX_STATIC_IMAGE_BYTES + 1L,
        )

        assertFalse(staticImage.isStructurallyValid())
        assertTrue(gif.isStructurallyValid())
        assertFalse(
            image(
                mimeType = ImageMimeTypes.GIF,
                bytes = ImageUploadLimits.MAX_GIF_BYTES + 1L,
            ).isStructurallyValid()
        )
    }

    @Test
    fun imageHostFallsBackToTikoluAndAcceptsOnlyItsHttpsUrls() {
        assertEquals(ImageHost.TIKOLU, ImageHost.fromStoredKey(null))
        assertEquals(ImageHost.TIKOLU, ImageHost.fromStoredKey("unknown"))
        assertTrue(ImageHost.TIKOLU.isAllowedImageUrl("https://tikolu.net/i/abc-123"))
        assertFalse(ImageHost.TIKOLU.isAllowedImageUrl("http://tikolu.net/i/abc-123"))
        assertFalse(ImageHost.TIKOLU.isAllowedImageUrl("https://evil.test/i/abc-123"))
        assertTrue(ImageHost.PHOTO_LILY.isAllowedImageUrl("https://photo.lily.lat/uploads/a.webp"))
    }

    private fun image(mimeType: String, bytes: Long) = SelectedImage(
        contentUri = "content://test/image",
        mimeType = mimeType,
        bytes = bytes,
        displayName = "image.${ImageMimeTypes.extensionFor(mimeType)}",
        selectionIndex = 0,
    )
}
