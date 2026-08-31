package com.suixin.sx2libra.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.luck.picture.lib.engine.CropFileEngine
import com.yalantis.ucrop.UCrop
import com.suixin.sx2libra.R
import com.suixin.sx2libra.model.ImageMimeTypes
import java.io.File
import java.util.ArrayList
import java.util.UUID

data class CropRequest(
    val inputUri: String,
    val destinationUri: String,
    val mimeType: String
)

sealed class CropResult {
    data class Completed(val outputUri: String, val mimeType: String) : CropResult()
    data object Cancelled : CropResult()
    data class Failed(val reason: CropFailure) : CropResult()
}

enum class CropFailure {
    INVALID_INPUT,
    ENGINE_UNAVAILABLE,
    FAILED
}

fun interface CropCallback {
    fun onResult(result: CropResult)
}

/** View/platform adapter implemented by the PictureSelector uCrop bridge. */
interface UcropGateway {
    fun start(request: CropRequest, callback: CropCallback)
    fun cancel()
}

/**
 * MIME-aware crop boundary. JPEG and PNG are sent through uCrop; animated GIF
 * and WebP are returned to the caller unchanged. The gateway owns Activity and
 * Fragment references, so this class can safely be used from a ViewModel
 * adapter without leaking those objects.
 */
class LibraCropEngine(private val gateway: UcropGateway? = null) : CropFileEngine {
    fun requiresCrop(mimeType: String): Boolean = ImageMimeTypes.requiresCrop(mimeType)

    /** PictureSelector v3.11.2's real multi-crop callback entry point. */
    override fun onStartCrop(
        fragment: Fragment,
        srcUri: Uri,
        destinationUri: Uri,
        dataSource: ArrayList<String>,
        requestCode: Int,
    ) {
        val context = fragment.requireContext()
        val mimeType = ImageMimeTypes.normalize(context.contentResolver.getType(srcUri))
            ?: return
        if (!requiresCrop(mimeType)) return
        val primary = ContextCompat.getColor(context, R.color.libra_primary)
        val surface = ContextCompat.getColor(context, R.color.surface_background)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val options = UCrop.Options().apply {
            setCompressionFormat(
                if (mimeType == ImageMimeTypes.PNG) Bitmap.CompressFormat.PNG
                else Bitmap.CompressFormat.JPEG
            )
            setCompressionQuality(100)
            setFreeStyleCropEnabled(true)
            isCropDragSmoothToCenter(true)
            isForbidCropGifWebp(true)
            setToolbarColor(surface)
            setStatusBarColor(surface)
            setToolbarWidgetColor(textPrimary)
            setRootViewBackgroundColor(surface)
            setActiveControlsWidgetColor(primary)
            setToolbarTitle("裁切图片")
        }
        runCatching {
            val crop = UCrop.of(srcUri, destinationUri, dataSource).withOptions(options)
            crop.setImageEngine(PictureSelectorImageEngine())
            crop.start(context, fragment, requestCode)
        }
    }

    fun start(request: CropRequest, callback: CropCallback) {
        val mime = ImageMimeTypes.normalize(request.mimeType)
        if (mime == null || !request.inputUri.startsWith("content://", ignoreCase = true)) {
            callback.onResult(CropResult.Failed(CropFailure.INVALID_INPUT))
            return
        }
        if (!requiresCrop(mime)) {
            callback.onResult(CropResult.Completed(request.inputUri, mime))
            return
        }
        if (!request.destinationUri.startsWith("content://", ignoreCase = true)) {
            callback.onResult(CropResult.Failed(CropFailure.INVALID_INPUT))
            return
        }
        val activeGateway = gateway
        if (activeGateway == null) {
            callback.onResult(CropResult.Failed(CropFailure.ENGINE_UNAVAILABLE))
            return
        }
        activeGateway.start(request.copy(mimeType = mime), callback)
    }

    fun cancel() {
        gateway?.cancel()
    }

    companion object {
        const val CACHE_DIRECTORY = "post-image-crop"

        /** Creates an app-private output file; callers expose it only through FileProvider. */
        fun newCacheFile(context: Context, mimeType: String): File {
            val mime = ImageMimeTypes.normalize(mimeType)
                ?: throw IllegalArgumentException("Unsupported image type")
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Unable to create crop cache")
            }
            return File(
                directory,
                "crop-${UUID.randomUUID()}.${ImageMimeTypes.extensionFor(mime)}"
            )
        }

        /** Removes only old crop files under the dedicated cache directory. */
        fun cleanStaleCache(context: Context, maxAgeMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
            require(maxAgeMillis >= 0L)
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            directory.listFiles()?.forEach { file ->
                if (file.isFile && nowMillis - file.lastModified() > maxAgeMillis) file.delete()
            }
        }
    }
}
