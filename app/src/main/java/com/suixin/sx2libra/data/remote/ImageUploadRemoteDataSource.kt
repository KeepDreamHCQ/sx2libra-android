package com.suixin.sx2libra.data.remote

import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
import com.suixin.sx2libra.model.UploadTicket
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A cancellable request returned by the upload data source. */
fun interface UploadCall {
    fun cancel()
}

interface UploadCallback {
    fun onProgress(completedBytes: Long, totalBytes: Long)
    fun onSuccess(response: ImageUploadResponse)
    fun onFailure(error: UploadErrorCode)
}

interface ImageUploadRemoteDataSource {
    /**
     * Uploads one image. The caller owns [body] until this method returns; the
     * implementation closes it after the request finishes. [body] is never
     * retained after the call, which keeps content URI handles short-lived.
     */
    fun upload(
        ticket: UploadTicket,
        clientId: String,
        image: SelectedImage,
        body: InputStream,
        callback: UploadCallback
    ): UploadCall
}

data class ImageUploadResponse(
    val clientId: String,
    val uploadId: String,
    val url: String,
    val mimeType: String,
    val bytes: Long,
    val width: Int? = null,
    val height: Int? = null
)

/**
 * HTTP implementation for the first-party 2Libra proxy. It deliberately uses
 * no image-host SDK and has no provider credentials. The only Authorization
 * value accepted by this class is the in-memory, short-lived upload ticket.
 */
class HttpImageUploadRemoteDataSource(
    endpoint: String = DEFAULT_ENDPOINT,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : ImageUploadRemoteDataSource {
    private val endpointUrl: URL = validateEndpoint(endpoint)

    override fun upload(
        ticket: UploadTicket,
        clientId: String,
        image: SelectedImage,
        body: InputStream,
        callback: UploadCallback
    ): UploadCall {
        val cancelled = AtomicBoolean(false)
        val connectionRef = arrayOfNulls<HttpURLConnection>(1)

        fun cancelRequest() {
            cancelled.set(true)
            connectionRef[0]?.disconnect()
        }

        try {
            if (!ticket.isUsable() || ticket.expiresAtEpochMillis <= System.currentTimeMillis()) {
                body.close()
                callback.onFailure(UploadErrorCode.TICKET_EXPIRED)
                return UploadCall(::cancelRequest)
            }
            val normalizedMime = image.normalizedMimeType
            if (!image.isStructurallyValid() || normalizedMime == null || !ticket.allows(normalizedMime, image.bytes)) {
                body.close()
                callback.onFailure(
                    if (normalizedMime != null && image.bytes > minOf(
                            ticket.effectiveMaxBytes(),
                            ImageUploadLimits.maxBytesForMime(normalizedMime),
                        )
                    ) {
                        UploadErrorCode.FILE_TOO_LARGE
                    } else {
                        UploadErrorCode.INVALID_IMAGE
                    }
                )
                return UploadCall(::cancelRequest)
            }

            val boundary = "----2Libra-${UUID.randomUUID()}"
            val connection = connectionFactory(endpointUrl).also { connectionRef[0] = it }
            connection.requestMethod = "POST"
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "UploadTicket ${ticket.opaqueValue}")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setChunkedStreamingMode(STREAM_BUFFER_BYTES)

            callback.onProgress(0L, image.bytes)
            connection.outputStream.use { output ->
                writeUtf8(output, "--$boundary\r\n")
                writeUtf8(
                    output,
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${safeFileName(image.displayName)}\"\r\n"
                )
                writeUtf8(output, "Content-Type: $normalizedMime\r\n\r\n")
                copyWithProgress(body, output, image.bytes, cancelled, callback)
                writeUtf8(output, "\r\n--$boundary\r\n")
                writeUtf8(output, "Content-Disposition: form-data; name=\"clientId\"\r\n\r\n")
                writeUtf8(output, "$clientId\r\n--$boundary--\r\n")
            }
            body.close()

            if (cancelled.get()) {
                callback.onFailure(UploadErrorCode.USER_CANCELLED)
                return UploadCall(::cancelRequest)
            }
            val responseCode = connection.responseCode
            val result = when {
                responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    responseCode == HttpURLConnection.HTTP_FORBIDDEN -> Result.failure(UploadException(UploadErrorCode.TICKET_EXPIRED))
                responseCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> Result.failure(UploadException(UploadErrorCode.FILE_TOO_LARGE))
                responseCode !in 200..299 -> Result.failure(
                    UploadException(
                        if (responseCode >= 500) UploadErrorCode.NETWORK_ERROR else UploadErrorCode.UPLOAD_REJECTED
                    )
                )
                else -> parseResponse(connection.inputStream, clientId)
            }
            result.fold(
                onSuccess = callback::onSuccess,
                onFailure = { callback.onFailure((it as? UploadException)?.error ?: UploadErrorCode.UPLOAD_REJECTED) }
            )
        } catch (error: UploadException) {
            callback.onFailure(error.error)
        } catch (_: UploadCancelledException) {
            callback.onFailure(UploadErrorCode.USER_CANCELLED)
        } catch (_: IOException) {
            callback.onFailure(if (cancelled.get()) UploadErrorCode.USER_CANCELLED else UploadErrorCode.NETWORK_ERROR)
        } catch (_: RuntimeException) {
            // Keep parser/URL failures opaque to the page.
            callback.onFailure(UploadErrorCode.UPLOAD_REJECTED)
        } finally {
            body.closeQuietly()
            connectionRef[0]?.disconnect()
        }

        return UploadCall(::cancelRequest)
    }

    private fun copyWithProgress(
        source: InputStream,
        target: OutputStream,
        expectedBytes: Long,
        cancelled: AtomicBoolean,
        callback: UploadCallback
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var copied = 0L
        BufferedInputStream(source, STREAM_BUFFER_BYTES).use { input ->
            while (true) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    throw UploadCancelledException()
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied += read
                if (copied > expectedBytes) throw UploadException(UploadErrorCode.INVALID_IMAGE)
                target.write(buffer, 0, read)
                callback.onProgress(copied, expectedBytes)
            }
        }
        if (copied != expectedBytes) throw UploadException(UploadErrorCode.INVALID_IMAGE)
    }

    private fun parseResponse(input: InputStream, expectedClientId: String): Result<ImageUploadResponse> {
        val bytes = readLimited(input, MAX_RESPONSE_BYTES)
        val root = try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (_: RuntimeException) {
            return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        }
        if (root.optInt("code", Int.MIN_VALUE) != 0) {
            return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        }
        val data = root.optJSONObject("data")
            ?: return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        val responseClientId = data.optString("clientId", "")
        val uploadId = data.optString("uploadId", "")
        val url = data.optString("url", "")
        val mime = ImageMimeTypes.normalize(data.optString("mimeType", ""))
        val responseBytes = data.optLong("bytes", -1L)
        if (responseClientId != expectedClientId ||
            uploadId.length !in 1..256 ||
            !MediaUrlPolicy.isTrustedCdnUrl(url) ||
            mime == null ||
            responseBytes <= 0L
        ) {
            return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        }
        return Result.success(
            ImageUploadResponse(
                clientId = responseClientId,
                uploadId = uploadId,
                url = url,
                mimeType = mime,
                bytes = responseBytes,
                width = data.optionalPositiveInt("width"),
                height = data.optionalPositiveInt("height")
            )
        )
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw UploadException(UploadErrorCode.UPLOAD_REJECTED)
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun safeFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .take(120)
        .ifEmpty { "image" }

    private fun writeUtf8(output: OutputStream, value: String) =
        output.write(value.toByteArray(Charsets.UTF_8))

    private class UploadException(val error: UploadErrorCode) : IOException()
    private class UploadCancelledException : IOException()

    private companion object {
        const val DEFAULT_ENDPOINT = "https://2libra.com/api/app/image-uploads"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val MAX_RESPONSE_BYTES = 128 * 1024

        fun validateEndpoint(value: String): URL {
            val uri = try {
                URI(value)
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid upload endpoint")
            }
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.host.equals(MediaUrlPolicy.SITE_HOST, ignoreCase = true))
            require(uri.userInfo == null && (uri.port == -1 || uri.port == 443))
            return URL(value)
        }

        fun JSONObject.optionalPositiveInt(name: String): Int? {
            val value = optInt(name, -1)
            return value.takeIf { it >= 0 }
        }

        fun InputStream.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // The original request result is more useful than close noise.
            }
        }
    }
}
