package com.suixin.sx2libra.model

import java.net.URI
import java.util.Locale

/** Fixed image hosts supported by the native upload flow. */
enum class ImageHost(
    val key: String,
    val displayName: String,
    val uploadEndpoint: String,
) {
    TIKOLU(
        key = "tikolu",
        displayName = "Tikolu",
        uploadEndpoint = "https://tikolu.net/i/",
    ),
    PHOTO_LILY(
        key = "photo_lily",
        displayName = "Photo Lily",
        uploadEndpoint = "https://photo.lily.lat/upload",
    );

    /** Accept only HTTPS URLs returned by this exact provider. */
    fun isAllowedImageUrl(rawUrl: String): Boolean {
        if (rawUrl.length !in 1..MediaUrlPolicy.MAX_URL_LENGTH ||
            rawUrl.any { it.isISOControl() || it.isWhitespace() }
        ) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(providerHost(), ignoreCase = true) ||
            uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) ||
            uri.fragment != null
        ) return false
        val path = uri.rawPath.orEmpty()
        if (path.isEmpty() || path.contains('\\') || path.contains("//")) return false
        val normalizedPath = path.lowercase(Locale.US)
        if (normalizedPath.contains("%2e") || normalizedPath.contains("%2f")) return false
        return when (this) {
            TIKOLU -> path.startsWith("/i/") && path.length > 3
            PHOTO_LILY -> path.startsWith('/') && path.length > 1
        }
    }

    private fun providerHost(): String = when (this) {
        TIKOLU -> "tikolu.net"
        PHOTO_LILY -> "photo.lily.lat"
    }

    companion object {
        fun fromStoredKey(value: String?): ImageHost =
            entries.firstOrNull { it.key == value?.trim() } ?: TIKOLU

        fun fromKeyOrNull(value: String?): ImageHost? =
            entries.firstOrNull { it.key == value?.trim() }
    }
}
