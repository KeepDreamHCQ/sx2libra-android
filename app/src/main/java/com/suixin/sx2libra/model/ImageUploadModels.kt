package com.suixin.sx2libra.model

import java.util.Locale
import java.util.UUID

/**
 * Values shared by the picker, upload repository and the WebMessage adapter.
 *
 * No value in this file contains a Context, a Uri object, a WebView, or an
 * upload callback.  In particular, [SelectedImage.contentUri] is only the
 * opaque string needed to ask a platform data source for the bytes.
 */
object ImageMimeTypes {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val GIF = "image/gif"
    const val WEBP = "image/webp"

    val allowed: Set<String> = setOf(JPEG, PNG, GIF, WEBP)

    fun normalize(value: String?): String? {
        val normalized = value?.trim()?.lowercase(Locale.US) ?: return null
        return normalized.takeIf { it in allowed }
    }

    fun requiresCrop(mimeType: String): Boolean =
        normalize(mimeType) == JPEG || normalize(mimeType) == PNG

    fun extensionFor(mimeType: String): String = when (normalize(mimeType)) {
        JPEG -> "jpg"
        PNG -> "png"
        GIF -> "gif"
        WEBP -> "webp"
        else -> "bin"
    }
}

/** A selected/cropped image represented without an Android platform handle. */
data class SelectedImage(
    val contentUri: String,
    val mimeType: String,
    val bytes: Long,
    val displayName: String,
    val selectionIndex: Int
) {
    val normalizedMimeType: String?
        get() = ImageMimeTypes.normalize(mimeType)

    fun isStructurallyValid(): Boolean {
        val mime = normalizedMimeType ?: return false
        return contentUri.length in 1..4_096 &&
            contentUri.startsWith("content://", ignoreCase = true) &&
            bytes in 1L..ImageUploadLimits.maxBytesForMime(mime) &&
            selectionIndex >= 0 &&
            displayName.trim().isNotEmpty()
    }
}

object ImageUploadLimits {
    const val MAX_FILES: Int = 9
    const val MAX_CONCURRENT_UPLOADS: Int = 3
    const val MAX_SAFE_FILE_BYTES: Long = 20L * 1024L * 1024L
    const val MAX_STATIC_IMAGE_BYTES: Long = 6L * 1024L * 1024L
    const val MAX_GIF_BYTES: Long = 10L * 1024L * 1024L
    // The picker must allow large static originals through so they can be
    // compressed before the MIME-specific upload limit is applied.
    const val MAX_PICKER_FILE_BYTES: Long = MAX_SAFE_FILE_BYTES
    const val PROGRESS_THROTTLE_MILLIS: Long = 200L

    fun maxBytesForMime(mimeType: String?): Long = when (ImageMimeTypes.normalize(mimeType)) {
        ImageMimeTypes.GIF -> MAX_GIF_BYTES
        ImageMimeTypes.JPEG,
        ImageMimeTypes.PNG,
        ImageMimeTypes.WEBP,
        -> MAX_STATIC_IMAGE_BYTES
        else -> 0L
    }
}

/** Stable error values exposed to H5.  Do not add response text to these. */
enum class UploadErrorCode {
    USER_CANCELLED,
    INVALID_IMAGE,
    FILE_TOO_LARGE,
    UPLOAD_REJECTED,
    NETWORK_ERROR,
    PAGE_GONE
}

enum class UploadTaskStatus {
    SELECTED,
    QUEUED,
    UPLOADING,
    UPLOADED,
    FAILED,
    CANCELLED
}

data class UploadProgress(
    val clientId: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val fraction: Float
) {
    init {
        require(clientId.isNotBlank())
        require(totalBytes >= 0L)
        require(completedBytes >= 0L)
    }
}

data class UploadTaskSnapshot(
    val clientId: String,
    val selectionIndex: Int,
    val displayName: String,
    val status: UploadTaskStatus,
    val progress: UploadProgress? = null,
    val url: String? = null,
    val error: UploadErrorCode? = null
)

/**
 * Events are deliberately value-only.  A View can translate these into
 * WebMessage replies while the repository remains independent of WebView.
 */
sealed class ImageUploadEvent {
    abstract val requestId: String
    abstract val clientId: String?

    data class Selected(
        override val requestId: String,
        override val clientId: String,
        val selectionIndex: Int,
        val displayName: String
    ) : ImageUploadEvent()

    data class Queued(
        override val requestId: String,
        override val clientId: String
    ) : ImageUploadEvent()

    data class Started(
        override val requestId: String,
        override val clientId: String
    ) : ImageUploadEvent()

    data class Progressed(
        override val requestId: String,
        override val clientId: String,
        val progress: UploadProgress
    ) : ImageUploadEvent()

    data class Completed(
        override val requestId: String,
        override val clientId: String,
        val markdown: String
    ) : ImageUploadEvent()

    data class Failed(
        override val requestId: String,
        override val clientId: String,
        val error: UploadErrorCode,
        val retryable: Boolean
    ) : ImageUploadEvent()

    data class Cancelled(
        override val requestId: String,
        override val clientId: String
    ) : ImageUploadEvent()

    data class BatchFinished(
        override val requestId: String,
        val successfulClientIds: List<String>,
        val failedClientIds: List<String>
    ) : ImageUploadEvent() {
        override val clientId: String? = null
    }

    data class BatchCancelled(
        override val requestId: String
    ) : ImageUploadEvent() {
        override val clientId: String? = null
    }
}

fun newUploadClientId(): String = UUID.randomUUID().toString()
