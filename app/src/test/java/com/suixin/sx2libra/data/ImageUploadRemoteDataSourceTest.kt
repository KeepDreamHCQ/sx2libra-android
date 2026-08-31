package com.suixin.sx2libra.data

import com.suixin.sx2libra.data.remote.HttpImageUploadRemoteDataSource
import com.suixin.sx2libra.data.remote.UploadCallback
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
import com.suixin.sx2libra.model.UploadTicket
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ImageUploadRemoteDataSourceTest {
    @Test
    fun http413IsExposedAsStableFileTooLargeError() {
        var failure: UploadErrorCode? = null
        val remote = HttpImageUploadRemoteDataSource(
            connectionFactory = { url -> EntityTooLargeConnection(url) },
        )

        remote.upload(
            ticket = UploadTicket(
                opaqueValue = "ticket",
                expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                maxBytesPerFile = 1L,
                maxFiles = 1,
            ),
            clientId = "client-id",
            image = SelectedImage(
                contentUri = "content://test/image",
                mimeType = ImageMimeTypes.JPEG,
                bytes = 1L,
                displayName = "image.jpg",
                selectionIndex = 0,
            ),
            body = ByteArrayInputStream(byteArrayOf(1)),
            callback = object : UploadCallback {
                override fun onProgress(completedBytes: Long, totalBytes: Long) = Unit
                override fun onSuccess(response: com.suixin.sx2libra.data.remote.ImageUploadResponse) = Unit
                override fun onFailure(error: UploadErrorCode) {
                    failure = error
                }
            },
        )

        assertEquals(UploadErrorCode.FILE_TOO_LARGE, failure)
    }

    private class EntityTooLargeConnection(url: URL) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getOutputStream() = output
        override fun getResponseCode(): Int = HTTP_ENTITY_TOO_LARGE
    }
}
