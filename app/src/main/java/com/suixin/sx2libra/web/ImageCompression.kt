package com.suixin.sx2libra.web

import android.content.Context
import android.graphics.Bitmap
import com.nanchen.compresshelper.CompressHelper
import com.suixin.sx2libra.model.ImageMimeTypes
import java.io.File
import java.util.UUID

/** The file and MIME that will be exposed to the upload pipeline. */
data class CompressedImageFile(
    val file: File,
    val mimeType: String,
)

/**
 * Compresses the completed crop result with CompressHelper in app-private
 * cache. GIF and WebP are returned unchanged so animated images are not
 * flattened into a single bitmap frame by a bitmap-only compressor.
 */
class CompressHelperImageCompressor(
    private val context: Context,
) {
    fun compress(source: File, mimeType: String): CompressedImageFile? {
        val normalizedMime = ImageMimeTypes.normalize(mimeType) ?: return null
        if (!source.isFile || source.length() <= 0L) return null
        if (normalizedMime == ImageMimeTypes.GIF || normalizedMime == ImageMimeTypes.WEBP) {
            return CompressedImageFile(source, normalizedMime)
        }

        val format = if (normalizedMime == ImageMimeTypes.PNG) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        val directory = File(context.cacheDir, LibraCropEngine.CACHE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        val compressed = runCatching {
            CompressHelper.Builder(context)
                .setMaxWidth(MAX_WIDTH)
                .setMaxHeight(MAX_HEIGHT)
                .setQuality(QUALITY)
                .setFileName("upload-${UUID.randomUUID()}")
                .setCompressFormat(format)
                .setDestinationDirectoryPath(directory.absolutePath)
                .build()
                .compressToFile(source)
        }.getOrNull() ?: return null
        return compressed
            .takeIf { it.isFile && it.length() > 0L }
            ?.let { CompressedImageFile(it, normalizedMime) }
    }

    private companion object {
        const val MAX_WIDTH = 1920f
        const val MAX_HEIGHT = 1920f
        const val QUALITY = 80
    }
}
