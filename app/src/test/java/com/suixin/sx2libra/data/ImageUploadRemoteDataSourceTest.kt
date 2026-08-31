package com.suixin.sx2libra.data

import com.suixin.sx2libra.data.remote.HttpImageUploadRemoteDataSource
import com.suixin.sx2libra.data.remote.UploadCallback
import com.suixin.sx2libra.model.ImageHost
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ImageUploadRemoteDataSourceTest {
    @Test
    fun http413IsExposedAsStableFileTooLargeError() {
        var failure: UploadErrorCode? = null
        val remote = HttpImageUploadRemoteDataSource(
            imageHost = ImageHost.TIKOLU,
            connectionFactory = { url -> EntityTooLargeConnection(url) },
        )

        remote.upload(
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

    @Test
    fun tikoluSendsUploadFlagAndFileAndReturnsHttpsUrl() {
        val connection = RecordingConnection(
            URL(ImageHost.TIKOLU.uploadEndpoint),
            responseCode = HttpURLConnection.HTTP_OK,
            responseBody = """{"status":"uploaded","id":"abc-123"}""",
        )
        var success: com.suixin.sx2libra.data.remote.ImageUploadResponse? = null
        var failure: UploadErrorCode? = null
        val remote = HttpImageUploadRemoteDataSource(ImageHost.TIKOLU) { connection }

        remote.upload(
            clientId = "client-id",
            image = image(ImageMimeTypes.JPEG, 3L, "photo.jpg"),
            body = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
            callback = callback(
                onSuccess = { success = it },
                onFailure = { failure = it },
            ),
        )

        val body = connection.requestBody().toString(Charsets.UTF_8)
        assertEquals(null, failure)
        assertNotNull(success)
        assertEquals("https://tikolu.net/i/abc-123", success?.url)
        assertTrue(body.contains("name=\"upload\""))
        assertTrue(body.contains("\r\n\r\ntrue\r\n"))
        assertTrue(body.contains("name=\"file\"; filename=\"photo.jpg\""))
        assertTrue(body.contains("\r\n\u0001\u0002\u0003\r\n"))
    }

    @Test
    fun photoLilySendsOnlyFileAndNormalizesRelativeSource() {
        val connection = RecordingConnection(
            URL(ImageHost.PHOTO_LILY.uploadEndpoint),
            responseCode = HttpURLConnection.HTTP_OK,
            responseBody = """[{"src":"/uploads/photo.webp"}]""",
        )
        var success: com.suixin.sx2libra.data.remote.ImageUploadResponse? = null
        var failure: UploadErrorCode? = null
        val remote = HttpImageUploadRemoteDataSource(ImageHost.PHOTO_LILY) { connection }

        remote.upload(
            clientId = "client-id",
            image = image(ImageMimeTypes.WEBP, 2L, "photo.webp"),
            body = ByteArrayInputStream(byteArrayOf(4, 5)),
            callback = callback(
                onSuccess = { success = it },
                onFailure = { failure = it },
            ),
        )

        val body = connection.requestBody().toString(Charsets.UTF_8)
        assertEquals(null, failure)
        assertEquals("https://photo.lily.lat/uploads/photo.webp", success?.url)
        assertTrue(!body.contains("name=\"upload\""))
        assertTrue(body.contains("name=\"file\"; filename=\"photo.webp\""))
    }

    @Test
    fun providerRejectsForeignOrMalformedReturnedUrls() {
        var tikoluFailure: UploadErrorCode? = null
        val tikolu = HttpImageUploadRemoteDataSource(ImageHost.TIKOLU) { url ->
            RecordingConnection(
                url,
                HttpURLConnection.HTTP_OK,
                """{"status":"uploaded","id":"../foreign"}""",
            )
        }
        tikolu.upload(
            clientId = "client-id",
            image = image(ImageMimeTypes.PNG, 1L, "photo.png"),
            body = ByteArrayInputStream(byteArrayOf(1)),
            callback = callback(onFailure = { tikoluFailure = it }),
        )

        var photoLilyFailure: UploadErrorCode? = null
        val photoLily = HttpImageUploadRemoteDataSource(ImageHost.PHOTO_LILY) { url ->
            RecordingConnection(
                url,
                HttpURLConnection.HTTP_OK,
                """[{"src":"https://evil.test/image.png"}]""",
            )
        }
        photoLily.upload(
            clientId = "client-id",
            image = image(ImageMimeTypes.PNG, 1L, "photo.png"),
            body = ByteArrayInputStream(byteArrayOf(1)),
            callback = callback(onFailure = { photoLilyFailure = it }),
        )

        assertEquals(UploadErrorCode.UPLOAD_REJECTED, tikoluFailure)
        assertEquals(UploadErrorCode.UPLOAD_REJECTED, photoLilyFailure)
    }

    @Test
    fun networkFailureIsOpaqueAndStable() {
        var failure: UploadErrorCode? = null
        val remote = HttpImageUploadRemoteDataSource(ImageHost.TIKOLU) { url ->
            object : RecordingConnection(url, HttpURLConnection.HTTP_OK, "") {
                override fun getResponseCode(): Int = throw IOException("hidden network detail")
            }
        }

        remote.upload(
            clientId = "client-id",
            image = image(ImageMimeTypes.PNG, 1L, "photo.png"),
            body = ByteArrayInputStream(byteArrayOf(1)),
            callback = callback(onFailure = { failure = it }),
        )

        assertEquals(UploadErrorCode.NETWORK_ERROR, failure)
    }

    private fun image(mimeType: String, bytes: Long, name: String) =
        SelectedImage(
            contentUri = "content://test/image",
            mimeType = mimeType,
            bytes = bytes,
            displayName = name,
            selectionIndex = 0,
        )

    private fun callback(
        onSuccess: (com.suixin.sx2libra.data.remote.ImageUploadResponse) -> Unit = {},
        onFailure: (UploadErrorCode) -> Unit = {},
    ) = object : UploadCallback {
        override fun onProgress(completedBytes: Long, totalBytes: Long) = Unit
        override fun onSuccess(response: com.suixin.sx2libra.data.remote.ImageUploadResponse) =
            onSuccess(response)
        override fun onFailure(error: UploadErrorCode) = onFailure(error)
    }

    private open class RecordingConnection(
        url: URL,
        private val responseCode: Int,
        private val responseBody: String,
    ) : HttpURLConnection(url) {
        private val output = ByteArrayOutputStream()

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
        override fun getOutputStream() = output
        override fun getResponseCode(): Int = responseCode
        override fun getInputStream() = ByteArrayInputStream(responseBody.toByteArray(Charsets.UTF_8))
        fun requestBody(): ByteArray = output.toByteArray()
    }

    private class EntityTooLargeConnection(url: URL) : RecordingConnection(
        url,
        HTTP_ENTITY_TOO_LARGE,
        "",
    ) {
    }
}
