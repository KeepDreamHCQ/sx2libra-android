package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.local.MediaStoreWriter
import com.suixin.sx2libra.data.local.SaveCancellation
import com.suixin.sx2libra.model.MediaItem
import com.suixin.sx2libra.model.MediaKind
import com.suixin.sx2libra.model.MediaPreviewRequest
import com.suixin.sx2libra.model.MediaSaveResult
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.model.VideoRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Repository boundary for native media preview and original-format saving. */
class MediaRepository(
    private val store: MediaStoreWriter,
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool()
) {
    fun validatePreviewRequest(urls: List<String>, initialIndex: Int): MediaPreviewRequest? {
        val filtered = urls.asSequence()
            .filter { MediaUrlPolicy.isAllowedImageUrl(it) }
            .distinct()
            .take(MediaUrlPolicy.MAX_PREVIEW_ITEMS)
            .toList()
        if (filtered.isEmpty()) return null
        // The caller's index refers to the unfiltered DOM order. Preserve that
        // relation when an invalid/duplicate URL was removed.
        val requestedUrl = urls.getOrNull(initialIndex) ?: return null
        val normalizedIndex = filtered.indexOf(requestedUrl)
        if (normalizedIndex < 0) return null
        return MediaPreviewRequest(filtered, normalizedIndex)
    }

    fun validateVideoRequest(request: VideoRequest): VideoRequest? {
        if (!MediaUrlPolicy.isAllowedVideoMime(request.mimeType) ||
            !MediaUrlPolicy.isAllowedVideoUrl(request.url, request.mimeType)
        ) {
            return null
        }
        if (request.title != null && request.title.length > MAX_TITLE_LENGTH) return null
        if (request.posterUrl != null && !MediaUrlPolicy.isAllowedPosterUrl(request.posterUrl)) return null
        if (request.previewVttUrl != null && !MediaUrlPolicy.isAllowedVttUrl(request.previewVttUrl)) return null
        return request.copy(
            title = request.title?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    fun classifyImage(url: String, mimeType: String? = null): MediaItem? {
        if (!MediaUrlPolicy.isAllowedImageUrl(url)) return null
        if (mimeType != null && !MediaUrlPolicy.isAllowedImageMime(mimeType)) return null
        return MediaItem(url, MediaKind.IMAGE, mimeType)
    }

    fun saveImage(
        url: String,
        suggestedName: String? = null,
        cancellation: SaveCancellation = SaveCancellation { false }
    ): MediaSaveResult = store.saveImage(url, suggestedName, cancellation)

    fun saveImageAsync(
        url: String,
        suggestedName: String? = null,
        cancellation: SaveCancellation = SaveCancellation { false },
        callback: (MediaSaveResult) -> Unit
    ): Future<*> = ioExecutor.submit {
        callback(saveImage(url, suggestedName, cancellation))
    }

    fun shutdown() {
        ioExecutor.shutdownNow()
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 160
    }
}

