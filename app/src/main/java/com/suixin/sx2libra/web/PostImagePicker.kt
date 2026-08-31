package com.suixin.sx2libra.web

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.SelectedImage

enum class ImagePickerMode {
    SINGLE,
    MULTIPLE
}

sealed class ImagePickerResult {
    data class Selected(val images: List<SelectedImage>) : ImagePickerResult()
    data object Cancelled : ImagePickerResult()
    data class Failed(val reason: PickerFailure) : ImagePickerResult()
}

enum class PickerFailure {
    PERMISSION_DENIED,
    INVALID_IMAGE,
    TOO_MANY_IMAGES,
    FILE_TOO_LARGE
}

fun interface ImagePickerCallback {
    fun onResult(result: ImagePickerResult)
}

/**
 * Platform boundary for PictureSelector. A concrete Activity adapter can
 * implement this with the exact PictureSelector v3.11.2 API after dependency
 * wiring; the rest of the application never imports selector classes.
 */
interface PictureSelectorGateway {
    fun launch(
        mode: ImagePickerMode,
        maxSelect: Int,
        cropEngine: LibraCropEngine,
        imageEngine: PictureSelectorImageEngine,
        callback: ImagePickerCallback
    )

    fun cancel()
}

class PictureSelectorPostImagePicker(
    private val gateway: PictureSelectorGateway,
    private val resolver: ContentResolver,
    private val cropEngine: LibraCropEngine = LibraCropEngine(),
    private val imageEngine: PictureSelectorImageEngine = PictureSelectorImageEngine()
) : PostImagePicker {
    private val lock = Any()
    private var callback: ImagePickerCallback? = null
    private var generation = 0L

    override fun launch(mode: ImagePickerMode, callback: ImagePickerCallback) {
        // A selector can synchronously call back while it is being cancelled.
        // Stop the old session first, then replace its callback under a lock so
        // a stale callback can never complete the new request.
        runCatching { gateway.cancel() }
        val oldCallback: ImagePickerCallback?
        val token: Long
        synchronized(lock) {
            oldCallback = this.callback
            this.callback = callback
            token = ++generation
        }
        oldCallback?.onResult(ImagePickerResult.Cancelled)
        try {
            gateway.launch(
                mode = mode,
                maxSelect = if (mode == ImagePickerMode.MULTIPLE) ImageUploadLimits.MAX_FILES else 1,
                cropEngine = cropEngine,
                imageEngine = imageEngine,
                callback = ImagePickerCallback { result -> handleResult(token, mode, result) }
            )
        } catch (_: RuntimeException) {
            handleResult(token, mode, ImagePickerResult.Failed(PickerFailure.PERMISSION_DENIED))
        }
    }

    override fun cancel() {
        val target: ImagePickerCallback?
        synchronized(lock) {
            target = callback
            callback = null
            generation++
        }
        runCatching { gateway.cancel() }
        target?.onResult(ImagePickerResult.Cancelled)
    }

    private fun handleResult(token: Long, mode: ImagePickerMode, result: ImagePickerResult) {
        val target: ImagePickerCallback
        synchronized(lock) {
            if (token != generation) return
            target = callback ?: return
            callback = null
        }
        val normalized = when (result) {
            is ImagePickerResult.Selected -> SelectedImageValidator(resolver).validate(result.images, mode)
            else -> result
        }
        target.onResult(normalized)
    }
}

interface PostImagePicker {
    fun launch(mode: ImagePickerMode, callback: ImagePickerCallback)
    fun cancel()
}

/** Validates ContentResolver metadata before an image enters the upload queue. */
class SelectedImageValidator(private val resolver: ContentResolver) {
    fun validate(images: List<SelectedImage>, mode: ImagePickerMode): ImagePickerResult {
        val limit = if (mode == ImagePickerMode.MULTIPLE) ImageUploadLimits.MAX_FILES else 1
        if (images.size > limit) return ImagePickerResult.Failed(PickerFailure.TOO_MANY_IMAGES)
        val result = ArrayList<SelectedImage>(images.size)
        images.forEachIndexed { index, original ->
            val uri = parseContentUri(original.contentUri) ?: return ImagePickerResult.Failed(PickerFailure.INVALID_IMAGE)
            val mime = ImageMimeTypes.normalize(resolver.getType(uri))
                ?: return ImagePickerResult.Failed(PickerFailure.INVALID_IMAGE)
            val bytes = contentLength(uri, ImageUploadLimits.maxBytesForMime(mime))
                ?: return ImagePickerResult.Failed(PickerFailure.INVALID_IMAGE)
            if (bytes <= 0L) return ImagePickerResult.Failed(PickerFailure.INVALID_IMAGE)
            if (bytes > ImageUploadLimits.maxBytesForMime(mime)) {
                return ImagePickerResult.Failed(PickerFailure.FILE_TOO_LARGE)
            }
            // The caller's metadata is not trusted; use resolver values.
            result += original.copy(
                contentUri = uri.toString(),
                mimeType = mime,
                bytes = bytes,
                displayName = safeDisplayName(displayName(uri, original.displayName), mime),
                selectionIndex = index
            )
        }
        return ImagePickerResult.Selected(result)
    }

    private fun parseContentUri(value: String): Uri? = runCatching {
        Uri.parse(value).takeIf { it.scheme.equals("content", ignoreCase = true) && it.authority != null }
    }.getOrNull()

    private fun contentLength(uri: Uri, maxBytes: Long): Long? {
        val descriptor = runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        descriptor?.use { if (it.length >= 0L) return it.length }
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) break
                }
                total
            }
        }.getOrNull()
    }

    private fun displayName(uri: Uri, fallback: String): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: fallback
    }

    private fun safeDisplayName(value: String, mimeType: String): String {
        val clean = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(100)
            .ifEmpty { "image" }
        val extension = ".${ImageMimeTypes.extensionFor(mimeType)}"
        return clean.substringBeforeLast('.', clean) + extension
    }
}

/** Compatibility helper for the legacy single-file WebChrome callback. */
class LegacySingleFileChooser {
    private var callback: ((String?) -> Unit)? = null

    fun begin(newCallback: (String?) -> Unit) {
        val previous = synchronized(this) {
            val old = callback
            callback = newCallback
            old
        }
        previous?.invoke(null)
    }

    fun complete(contentUri: String?) {
        val target = synchronized(this) {
            val old = callback
            callback = null
            old
        }
        target?.invoke(contentUri?.takeIf { it.startsWith("content://", ignoreCase = true) })
    }

    fun cancel() = complete(null)
}
