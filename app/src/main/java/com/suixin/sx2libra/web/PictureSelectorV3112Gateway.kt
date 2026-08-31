package com.suixin.sx2libra.web

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.SelectedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Concrete PictureSelector v3.11.2 gateway. Activity/Fragment objects stay in
 * this platform adapter; only validated value objects leave the callback.
 */
class PictureSelectorV3112Gateway(
    private val activity: FragmentActivity,
    private val fileProviderAuthority: String,
    private val compressor: CompressHelperImageCompressor = CompressHelperImageCompressor(activity),
) : PictureSelectorGateway {
    private val lock = Any()
    private var callback: ImagePickerCallback? = null
    private var generation = 0L

    init {
        LibraCropEngine.cleanStaleCache(activity, CACHE_MAX_AGE_MILLIS)
    }

    override fun launch(
        mode: ImagePickerMode,
        maxSelect: Int,
        cropEngine: LibraCropEngine,
        imageEngine: PictureSelectorImageEngine,
        callback: ImagePickerCallback,
    ) {
        cancel()
        val token = synchronized(lock) {
            this.callback = callback
            ++generation
        }
        try {
            val selection = PictureSelector.create(activity)
                .openGallery(SelectMimeType.ofImage())
                .setImageEngine(imageEngine)
                .setCropEngine(cropEngine)
                .setSkipCropMimeType(ImageMimeTypes.GIF, ImageMimeTypes.WEBP)
                .setSelectionMode(
                    if (mode == ImagePickerMode.MULTIPLE) SelectModeConfig.MULTIPLE
                    else SelectModeConfig.SINGLE
                )
                .setMaxSelectNum(
                    if (mode == ImagePickerMode.MULTIPLE) {
                        maxSelect.coerceIn(1, ImageUploadLimits.MAX_FILES)
                    } else {
                        1
                    }
                )
                .setSelectMaxFileSize(ImageUploadLimits.MAX_PICKER_FILE_BYTES)
                .isDisplayCamera(false)
                .isGif(true)
                .isWebp(true)
            selection.forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    activity.lifecycleScope.launch {
                        val selected = withContext(Dispatchers.IO) {
                            result.mapIndexedNotNull(::toSelectedImage)
                        }
                        finish(token, ImagePickerResult.Selected(selected))
                    }
                }

                override fun onCancel() {
                    finish(token, ImagePickerResult.Cancelled)
                }
            })
        } catch (_: RuntimeException) {
            finish(token, ImagePickerResult.Failed(PickerFailure.PERMISSION_DENIED))
        }
    }

    override fun cancel() {
        synchronized(lock) {
            callback = null
            ++generation
        }
        // PictureSelector owns its transparent Activity/Fragment transaction;
        // invalidating this token prevents stale results without attempting an
        // unsafe global FragmentManager pop on a page we do not own.
    }

    fun onDestroy() = cancel()

    private fun finish(token: Long, result: ImagePickerResult) {
        val target = synchronized(lock) {
            if (token != generation) return
            val current = callback ?: return
            callback = null
            current
        }
        target.onResult(result)
    }

    private fun toSelectedImage(index: Int, media: LocalMedia): SelectedImage? {
        val path = media.getAvailablePath()?.takeIf { it.isNotBlank() } ?: return null
        val sourceMime = ImageMimeTypes.normalize(media.getMimeType()) ?: return null
        val source = sourceFile(path, sourceMime) ?: return null
        // PictureSelector has already completed its crop callback at this
        // point. Compression must consume that crop result, never the raw
        // source selected from the gallery.
        val prepared = compressor.compress(source, sourceMime) ?: return null
        val uri = toContentUri(prepared.file.absolutePath, prepared.mimeType) ?: return null
        val bytes = prepared.file.length()
        val displayName = media.getFileName()?.takeIf { it.isNotBlank() }
            ?: prepared.file.name
        return SelectedImage(
            contentUri = uri.toString(),
            mimeType = prepared.mimeType,
            bytes = bytes,
            displayName = displayName,
            selectionIndex = index,
        )
    }

    private fun sourceFile(path: String, mimeType: String): File? {
        val source = runCatching {
            when {
                path.startsWith("content://", ignoreCase = true) -> null
                path.startsWith("file://", ignoreCase = true) ->
                    File(Uri.parse(path).path ?: return null)
                else -> File(path)
            }
        }.getOrNull()
        if (source != null) {
            return source.takeIf {
                it.isFile && it.length() in 1L..ImageUploadLimits.MAX_SAFE_FILE_BYTES
            }
        }

        val uri = runCatching { Uri.parse(path) }.getOrNull()
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) && it.authority != null }
            ?: return null
        val directory = File(activity.cacheDir, LibraCropEngine.CACHE_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(
            directory,
            "source-${UUID.randomUUID()}.${ImageMimeTypes.extensionFor(mimeType)}",
        )
        val copied = runCatching {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use outputUse@{ output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > ImageUploadLimits.MAX_SAFE_FILE_BYTES) return@outputUse false
                        output.write(buffer, 0, count)
                    }
                    total > 0L
                }
            } ?: false
        }.getOrDefault(false)
        if (!copied || !target.isFile || target.length() <= 0L) {
            target.delete()
            return null
        }
        return target
    }

    private fun toContentUri(path: String, mimeType: String): Uri? {
        val source = runCatching {
            when {
                path.startsWith("content://", ignoreCase = true) -> return Uri.parse(path)
                path.startsWith("file://", ignoreCase = true) ->
                    File(Uri.parse(path).path ?: return null)
                else -> File(path)
            }
        }.getOrNull() ?: return null
        if (!source.isFile || source.length() <= 0L ||
            source.length() > ImageUploadLimits.maxBytesForMime(mimeType)
        ) return null
        return runCatching {
            FileProvider.getUriForFile(activity, fileProviderAuthority, source)
        }.getOrElse {
            // PictureSelector may return a provider-unconfigured filesystem
            // path. Copy only into our registered private cache root, bounded
            // to the same upload limit, then expose that copy as content://.
            runCatching {
                val directory = File(activity.cacheDir, LibraCropEngine.CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@runCatching null
                val target = File(
                    directory,
                    "picked-${UUID.randomUUID()}.${ImageMimeTypes.extensionFor(mimeType)}",
                )
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > ImageUploadLimits.maxBytesForMime(mimeType)) {
                                target.delete()
                                return@runCatching null
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                FileProvider.getUriForFile(activity, fileProviderAuthority, target)
            }
                .getOrNull()
        }.takeIf { it?.scheme.equals("content", ignoreCase = true) }
    }

    private companion object {
        const val CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
