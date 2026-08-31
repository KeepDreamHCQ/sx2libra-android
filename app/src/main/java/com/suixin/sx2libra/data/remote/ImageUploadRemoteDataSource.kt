package com.suixin.sx2libra.data.remote

import com.suixin.sx2libra.model.ImageHost
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadErrorCode
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
     * Uploads one image to the fixed provider selected for the current batch.
     * The implementation consumes and closes [body] before returning.
     */
    fun upload(
        clientId: String,
        image: SelectedImage,
        body: InputStream,
        callback: UploadCallback
    ): UploadCall
}

/** Builds only the two allowlisted provider adapters used by the app. */
object ImageUploadRemoteDataSourceFactory {
    fun create(imageHost: ImageHost): ImageUploadRemoteDataSource =
        HttpImageUploadRemoteDataSource(imageHost)
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
 * Multipart client for Tikolu and Photo Lily. Provider selection is injected
 * as a fixed [ImageHost] value when a batch starts; no page URL or credential
 * participates in the request.
 */
class HttpImageUploadRemoteDataSource(
    private val imageHost: ImageHost = ImageHost.TIKOLU,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) : ImageUploadRemoteDataSource {
    private val endpointUrl: URL = validateEndpoint(imageHost.uploadEndpoint)

    override fun upload(
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
            val normalizedMime = image.normalizedMimeType
            if (!image.isStructurallyValid() || normalizedMime == null) {
                body.close()
                callback.onFailure(
                    if (normalizedMime != null &&
                        image.bytes > ImageUploadLimits.maxBytesForMime(normalizedMime)
                    ) {
                        UploadErrorCode.FILE_TOO_LARGE
                    } else {
                        UploadErrorCode.INVALID_IMAGE
                    }
                )
                return UploadCall(::cancelRequest)
            }

            val boundary = "----2Libra-" + UUID.randomUUID()
            val connection = connectionFactory(endpointUrl).also { connectionRef[0] = it }
            connection.requestMethod = "POST"
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            val prefix = multipartPrefix(
                imageHost = imageHost,
                boundary = boundary,
                fileName = safeFileName(image.displayName),
                mimeType = normalizedMime,
            )
            val suffix = multipartSuffix(boundary)
            connection.setFixedLengthStreamingMode(
                prefix.size.toLong() + image.bytes + suffix.size.toLong()
            )

            callback.onProgress(0L, image.bytes)
            connection.outputStream.use { output ->
                output.write(prefix)
                copyWithProgress(body, output, image.bytes, cancelled, callback)
                output.write(suffix)
            }
            body.close()

            if (cancelled.get()) {
                callback.onFailure(UploadErrorCode.USER_CANCELLED)
                return UploadCall(::cancelRequest)
            }

            val responseCode = connection.responseCode
            if (Thread.currentThread().isInterrupted) throw UploadCancelledException()
            val result = when {
                responseCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE ->
                    Result.failure(UploadException(UploadErrorCode.FILE_TOO_LARGE))
                responseCode !in 200..299 -> Result.failure(
                    UploadException(
                        if (responseCode >= 500) {
                            UploadErrorCode.NETWORK_ERROR
                        } else {
                            UploadErrorCode.UPLOAD_REJECTED
                        }
                    )
                )
                else -> parseResponse(connection.inputStream, clientId, image, normalizedMime)
            }
            result.fold(
                onSuccess = callback::onSuccess,
                onFailure = {
                    callback.onFailure(
                        (it as? UploadException)?.error ?: UploadErrorCode.UPLOAD_REJECTED
                    )
                }
            )
        } catch (_: UploadCancelledException) {
            callback.onFailure(UploadErrorCode.USER_CANCELLED)
        } catch (error: UploadException) {
            callback.onFailure(error.error)
        } catch (_: IOException) {
            callback.onFailure(
                if (cancelled.get()) UploadErrorCode.USER_CANCELLED
                else UploadErrorCode.NETWORK_ERROR
            )
        } catch (_: RuntimeException) {
            // Keep parser, URL and provider response details opaque to H5.
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

    private fun parseResponse(
        input: InputStream,
        expectedClientId: String,
        image: SelectedImage,
        normalizedMime: String,
    ): Result<ImageUploadResponse> {
        val bytes = readLimited(input, MAX_RESPONSE_BYTES)
        val url = when (imageHost) {
            ImageHost.TIKOLU -> parseTikoluUrl(bytes)
            ImageHost.PHOTO_LILY -> parsePhotoLilyUrl(bytes)
        } ?: return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        if (!imageHost.isAllowedImageUrl(url)) {
            return Result.failure(UploadException(UploadErrorCode.UPLOAD_REJECTED))
        }
        return Result.success(
            ImageUploadResponse(
                clientId = expectedClientId,
                uploadId = uploadIdFor(url),
                url = url,
                mimeType = normalizedMime,
                bytes = image.bytes,
            )
        )
    }

    private fun parseTikoluUrl(response: ByteArray): String? {
        val text = response.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        if (jsonStringField(text, "status") != "uploaded") return null
        val id = jsonStringField(text, "id")?.trim().orEmpty()
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,256}"))) return null
        return "https://tikolu.net/i/" + id
    }

    private fun parsePhotoLilyUrl(response: ByteArray): String? {
        val text = response.toString(Charsets.UTF_8).trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return null
        val firstObjectStart = text.indexOf('{')
        val firstObjectEnd = text.indexOf('}', firstObjectStart + 1)
        if (firstObjectStart < 0 || firstObjectEnd <= firstObjectStart) return null
        val source = jsonStringField(
            text.substring(firstObjectStart, firstObjectEnd + 1),
            "src",
        )?.trim().orEmpty()
        if (source.isEmpty()) return null
        return when {
            source.startsWith("https://", ignoreCase = true) -> source
            source.startsWith("/") -> "https://photo.lily.lat" + source
            else -> "https://photo.lily.lat/" + source
        }
    }

    private fun uploadIdFor(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().substringAfterLast('/') }
            .getOrNull()
            ?.takeIf { it.length in 1..256 }
            ?: "provider-upload"

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var total = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) throw UploadCancelledException()
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

    /** Parses only JSON string fields needed from the small provider responses. */
    private fun jsonStringField(json: String, name: String): String? {
        val pattern = Regex(
            "\\\"" + Regex.escape(name) +
                "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\""
        )
        val raw = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
        return decodeJsonString(raw)
    }

    private fun decodeJsonString(raw: String): String? {
        val result = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index++]
            if (character != '\\') {
                if (character.code < 0x20) return null
                result.append(character)
                continue
            }
            if (index >= raw.length) return null
            when (val escaped = raw[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (index + 4 > raw.length) return null
                    val hex = raw.substring(index, index + 4)
                    index += 4
                    result.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                }
                else -> return null
            }
        }
        return result.toString()
    }

    private fun multipartPrefix(
        imageHost: ImageHost,
        boundary: String,
        fileName: String,
        mimeType: String,
    ): ByteArray = buildString {
        if (imageHost == ImageHost.TIKOLU) {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"upload\"\r\n\r\n")
            append("true\r\n")
        }
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"")
            .append(fileName)
            .append("\"\r\n")
        append("Content-Type: ").append(mimeType).append("\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun multipartSuffix(boundary: String): ByteArray =
        ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)

    private class UploadException(val error: UploadErrorCode) : IOException()
    private class UploadCancelledException : IOException()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val STREAM_BUFFER_BYTES = 16 * 1024
        const val MAX_RESPONSE_BYTES = 128 * 1024

        fun validateEndpoint(value: String): URL {
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Invalid upload endpoint") }
            require(uri.scheme.equals("https", ignoreCase = true))
            require(
                (uri.host.equals("tikolu.net", ignoreCase = true) &&
                    uri.rawPath == "/i/") ||
                    (uri.host.equals("photo.lily.lat", ignoreCase = true) &&
                        uri.rawPath == "/upload")
            )
            require(uri.userInfo == null && (uri.port == -1 || uri.port == 443))
            return URL(value)
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
