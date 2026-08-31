package com.suixin.sx2libra.model

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class MediaKind {
    IMAGE,
    VIDEO
}

/** A media URL that has already been classified by the native allowlist. */
data class MediaItem(
    val url: String,
    val kind: MediaKind,
    val mimeType: String? = null,
    val title: String? = null
)

data class MediaPreviewRequest(
    val urls: List<String>,
    val initialIndex: Int
) {
    init {
        require(urls.size in 1..MediaUrlPolicy.MAX_PREVIEW_ITEMS)
        require(initialIndex in urls.indices)
    }
}

data class VideoRequest(
    val url: String,
    val mimeType: String,
    val title: String? = null,
    val posterUrl: String? = null,
    val previewVttUrl: String? = null
)

data class PlaybackSnapshot(
    val positionMillis: Long,
    val durationMillis: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val speed: Float,
    val aspect: VideoAspect
) {
    init {
        require(positionMillis >= 0L)
        require(durationMillis >= 0L)
        require(speed > 0f)
    }
}

enum class VideoAspect {
    DEFAULT,
    RATIO_16_9,
    RATIO_4_3,
    CROP,
    STRETCH
}

enum class MediaSaveError {
    INVALID_URL,
    UNSUPPORTED_MIME,
    RESPONSE_TOO_LARGE,
    NETWORK_ERROR,
    STORAGE_ERROR,
    CANCELLED
}

sealed class MediaSaveResult {
    data class Saved(
        val uri: String,
        val mimeType: String,
        val displayName: String
    ) : MediaSaveResult()

    data class Failed(val error: MediaSaveError) : MediaSaveResult()
}

/**
 * URL policy shared by the bridge, media repository and streaming saver.
 * Image previews accept any well-formed HTTPS resource; video and VTT URLs
 * remain restricted to the forum media host.
 */
object MediaUrlPolicy {
    const val MEDIA_HOST = "r2.2libra.com"
    const val SITE_HOST = "2libra.com"
    const val MAX_URL_LENGTH = 4_096
    const val MAX_PREVIEW_ITEMS = 50
    const val MAX_MEDIA_BYTES = 20L * 1024L * 1024L

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp")
    private val videoExtensions = setOf("mp4", "webm", "m3u8", "mov")
    private val imageMimeTypes = setOf(
        ImageMimeTypes.JPEG,
        ImageMimeTypes.PNG,
        ImageMimeTypes.GIF,
        ImageMimeTypes.WEBP
    )
    private val videoMimeTypes = setOf(
        "video/mp4",
        "video/webm",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl"
    )

    fun isAllowedImageUrl(rawUrl: String?): Boolean = parseTrustedHttps(rawUrl) != null

    fun isAllowedVideoUrl(rawUrl: String?, mimeType: String? = null): Boolean {
        val uri = parseTrustedHttps(rawUrl) ?: return false
        if (!uri.host.equals(MEDIA_HOST, ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        if (!isSafeMediaPath(path) || path == "/video" || path == "/video/") return false
        val extension = path.substringAfterLast('.', "").lowercase(Locale.US)
        val normalizedMime = mimeType?.trim()?.lowercase(Locale.US)
        if (mimeType != null && normalizedMime !in videoMimeTypes) return false
        return path.startsWith("/video/") &&
            (extension in videoExtensions || normalizedMime in videoMimeTypes)
    }

    fun isAllowedVttUrl(rawUrl: String?): Boolean {
        val uri = parseTrustedHttps(rawUrl) ?: return false
        if (!uri.host.equals(MEDIA_HOST, ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        return path.startsWith("/video/") &&
            path.lowercase(Locale.US).endsWith(".vtt") &&
            isSafeMediaPath(path)
    }

    fun isAllowedPosterUrl(rawUrl: String?): Boolean = isAllowedImageUrl(rawUrl)

    fun isAllowedImageMime(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.US) in imageMimeTypes

    fun isAllowedVideoMime(mimeType: String?): Boolean =
        mimeType?.trim()?.lowercase(Locale.US) in videoMimeTypes

    fun extensionForMime(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
        ImageMimeTypes.JPEG -> "jpg"
        ImageMimeTypes.PNG -> "png"
        ImageMimeTypes.GIF -> "gif"
        ImageMimeTypes.WEBP -> "webp"
        else -> "bin"
    }

    /** HTTPS URL check for a CDN URL returned by the upload proxy. */
    fun isTrustedCdnUrl(rawUrl: String?): Boolean = isAllowedImageUrl(rawUrl)

    internal fun parseTrustedHttps(rawUrl: String?): URI? {
        if (rawUrl == null || rawUrl.length !in 1..MAX_URL_LENGTH) return null
        if (rawUrl.any { it.isISOControl() || it.isWhitespace() }) return null
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.host == null) return null
        if (uri.fragment != null) return null
        if (uri.rawPath == null || uri.rawPath!!.isEmpty()) return null
        return uri
    }

    private fun isSafeMediaPath(path: String): Boolean {
        if (!path.startsWith('/') || path.contains('\\') || path.contains("//")) return false
        val decoded = path.lowercase(Locale.US)
            .replace("%2e", ".")
            .replace("%2f", "/")
            .replace("%5c", "\\")
        if (decoded.contains('\\') || decoded.contains("//")) return false
        return decoded.removePrefix("/").split('/').none {
            it == "." || it == ".." || it.isEmpty()
        }
    }
}
