package com.suixin.sx2libra.web

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.suixin.sx2libra.data.repository.CachedWebImage
import com.suixin.sx2libra.data.repository.WebImageCacheRepositoryContract
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

/** Controls the app-managed image cache without affecting WebView's normal cache behavior. */
object WebImageCacheConfig {
    /** Set to true to read/write the private cache under cache/web_images. */
    const val ENABLED: Boolean = false
}

/**
 * Resolves WebView subresource requests through the shared image cache.
 * shouldInterceptRequest is invoked off the main thread, so all work here is
 * synchronous and uses the thread-safe cache data source.
 */
class WebImageResourceInterceptor(
    private val cache: WebImageCacheRepositoryContract,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
    private val fallbackUserAgent: String? = null,
) {
    /**
     * Intercepts a request without requiring a WebView instance. Both WebViewClient and Service
     * Worker callbacks run off the UI thread, so all request metadata must come from the request
     * itself or from the application-scoped fallback user agent.
     */
    fun intercept(
        request: WebResourceRequest,
        userAgent: String? = request.requestHeaders.headerValue("User-Agent") ?: fallbackUserAgent,
    ): WebResourceResponse? {
        if (!WebImageCacheConfig.ENABLED || !isEligible(request)) return null
        val url = request.url.toString()

        cache.read(url)?.let { cached ->
            openCachedResponse(cached)?.let { return it }
        }

        val generation = cache.currentGeneration()
        val connection = runCatching {
            openConnection(request, userAgent)
        }.getOrNull() ?: return null

        return try {
            val responseCode = connection.responseCode
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                connection.disconnect()
                return null
            }
            persistResponseCookie(url, connection)

            val body = responseBody(connection, responseCode) ?: run {
                connection.disconnect()
                return null
            }
            val contentType = connection.contentType
            val mimeType = normalizeMimeType(contentType)
            val imageMimeType = mimeType
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: mimeTypeFromExtension(url).takeIf { mimeType == null }

            if (responseCode in 200..299 && imageMimeType != null &&
                cache.currentGeneration() == generation
            ) {
                val cached = cache.write(url, imageMimeType, body, generation)
                connection.disconnect()
                if (cached != null) {
                    return openCachedResponse(cached)
                }
                return null
            }

            WebResourceResponse(
                mimeType ?: DEFAULT_MIME_TYPE,
                charsetFromContentType(contentType),
                responseCode.coerceIn(100, 599),
                reasonPhrase(connection, responseCode),
                responseHeaders(connection),
                ConnectionInputStream(body, connection),
            )
        } catch (_: IOException) {
            connection.disconnect()
            null
        } catch (_: RuntimeException) {
            connection.disconnect()
            null
        }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, value) ->
            key.equals(name, ignoreCase = true) && value.isNotBlank()
        }?.value

    private fun isEligible(request: WebResourceRequest): Boolean =
        !request.isForMainFrame &&
            request.method.equals("GET", ignoreCase = true) &&
            request.url.scheme.equals("https", ignoreCase = true) &&
            request.url.host.orEmpty().isNotBlank()

    private fun openConnection(
        request: WebResourceRequest,
        userAgent: String?,
    ): HttpURLConnection {
        val connection = connectionFactory(URL(request.url.toString())).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept-Encoding", "identity")
        }

        request.requestHeaders.forEach { (name, value) ->
            if (name.isBlank() || value.isBlank() || name.lowercase(Locale.US) in SKIPPED_HEADERS) {
                return@forEach
            }
            runCatching { connection.setRequestProperty(name, value) }
        }
        userAgent?.takeIf(String::isNotBlank)?.let {
            connection.setRequestProperty("User-Agent", it)
        }
        CookieManager.getInstance().getCookie(request.url.toString())?.let {
            connection.setRequestProperty("Cookie", it)
        }
        return connection
    }

    private fun responseBody(connection: HttpURLConnection, responseCode: Int): InputStream? =
        if (responseCode in 200..399) {
            runCatching { connection.inputStream }.getOrNull()
        } else {
            connection.errorStream
        }

    private fun openCachedResponse(cached: CachedWebImage): WebResourceResponse? =
        runCatching {
            WebResourceResponse(
                cached.mimeType,
                null,
                cached.path.inputStream(),
            )
        }.getOrNull()

    private fun persistResponseCookie(url: String, connection: HttpURLConnection) {
        connection.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
            .filter(String::isNotBlank)
            .forEach { cookie ->
                runCatching { CookieManager.getInstance().setCookie(url, cookie) }
            }
    }

    private fun normalizeMimeType(contentType: String?): String? = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf(String::isNotBlank)

    private fun charsetFromContentType(contentType: String?): String? {
        val charset = contentType
            ?.split(';')
            ?.drop(1)
            ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching { Charset.forName(charset).name() }.getOrNull()
    }

    private fun mimeTypeFromExtension(url: String): String? {
        val extension = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        return EXTENSION_MIME_TYPES[extension]
    }

    private fun reasonPhrase(connection: HttpURLConnection, responseCode: Int): String =
        runCatching { connection.responseMessage }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: if (responseCode in 200..299) "OK" else "HTTP $responseCode"

    private fun responseHeaders(connection: HttpURLConnection): Map<String, String> =
        connection.headerFields.entries
            .filter { it.key != null && !it.value.isNullOrEmpty() }
            .associate { (name, values) -> name!! to values!!.joinToString(",") }

    private class ConnectionInputStream(
        input: InputStream,
        private val connection: HttpURLConnection,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private val SKIPPED_HEADERS = setOf(
            "accept-encoding",
            "connection",
            "content-length",
            "cookie",
            "host",
            "proxy-authorization",
            "user-agent",
        )
        private val EXTENSION_MIME_TYPES = mapOf(
            "avif" to "image/avif",
            "gif" to "image/gif",
            "jpeg" to "image/jpeg",
            "jpg" to "image/jpeg",
            "png" to "image/png",
            "svg" to "image/svg+xml",
            "webp" to "image/webp",
        )
    }
}
