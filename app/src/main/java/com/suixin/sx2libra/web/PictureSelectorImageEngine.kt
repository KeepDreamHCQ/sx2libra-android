package com.suixin.sx2libra.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.luck.picture.lib.engine.ImageEngine
import com.yalantis.ucrop.UCropImageEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

fun interface ImageLoadCallback {
    fun onFinished(success: Boolean)
}

/** Optional deterministic seam for JVM/platform tests. */
fun interface LocalImageLoader {
    fun load(context: Context, model: Any, target: ImageView, callback: ImageLoadCallback)
}

/**
 * Real PictureSelector [ImageEngine] and uCrop [UCropImageEngine] adapter.
 * Glide owns decoding, disk/memory caching and animated GIF/WebP drawables;
 * no page bytes are downloaded or decoded by the Activity.
 */
class PictureSelectorImageEngine(
    private val testLoader: LocalImageLoader? = null,
) : ImageEngine, UCropImageEngine {
    override fun loadImage(context: Context, path: String, imageView: ImageView) {
        loadDrawable(context, path, imageView)
    }

    override fun loadImage(
        context: Context,
        imageView: ImageView,
        path: String,
        width: Int,
        height: Int,
    ) {
        loadDrawable(context, path, imageView, width, height)
    }

    override fun loadAlbumCover(context: Context, path: String, imageView: ImageView) {
        loadDrawable(context, path, imageView)
    }

    override fun loadGridImage(context: Context, path: String, imageView: ImageView) {
        loadDrawable(context, path, imageView)
    }

    override fun pauseRequests(context: Context) {
        Glide.with(context).pauseRequests()
    }

    override fun resumeRequests(context: Context) {
        Glide.with(context).resumeRequests()
    }

    override fun loadImage(
        context: Context,
        path: Uri,
        width: Int,
        height: Int,
        callback: UCropImageEngine.OnCallbackListener<Bitmap>,
    ) {
        val delivered = AtomicBoolean(false)
        Glide.with(context)
            .asBitmap()
            .load(path)
            .override(width.coerceAtLeast(1), height.coerceAtLeast(1))
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (delivered.compareAndSet(false, true)) callback.onCall(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    if (delivered.compareAndSet(false, true)) callback.onCall(null)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (delivered.compareAndSet(false, true)) callback.onCall(null)
                }
            })
    }

    /** Compatibility helper retained for callers that use this adapter directly. */
    fun loadImage(
        context: Context,
        path: String?,
        target: ImageView,
        callback: ImageLoadCallback = ImageLoadCallback { },
    ) {
        if (path.isNullOrBlank()) {
            callback.onFinished(false)
            return
        }
        loadDrawable(context, path, target, callback = callback)
    }

    /** Compatibility helper retained for app-private crop files. */
    fun loadImage(
        context: Context,
        file: File?,
        target: ImageView,
        callback: ImageLoadCallback = ImageLoadCallback { },
    ) {
        if (file == null) {
            callback.onFinished(false)
            return
        }
        loadDrawable(context, file, target, callback = callback)
    }

    fun loadAlbumCoverCompat(context: Context, path: String?, target: ImageView) =
        loadImage(context, path, target)

    fun loadGridImageCompat(context: Context, path: String?, target: ImageView, width: Int, height: Int) {
        if (path.isNullOrBlank()) return
        loadDrawable(context, path, target, width, height)
    }

    fun clear(target: ImageView) {
        Glide.with(target).clear(target)
    }

    private fun loadDrawable(
        context: Context,
        model: Any,
        target: ImageView,
        width: Int = Target.SIZE_ORIGINAL,
        height: Int = Target.SIZE_ORIGINAL,
        callback: ImageLoadCallback? = null,
    ) {
        testLoader?.let { loader ->
            loader.load(context, model, target, callback ?: ImageLoadCallback { })
            return
        }
        val request: RequestBuilder<Drawable> = Glide.with(context)
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        val configured = if (width > 0 && height > 0) request.override(width, height) else request
        configured.listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: com.bumptech.glide.load.engine.GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                callback?.onFinished(false)
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>,
                dataSource: com.bumptech.glide.load.DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                callback?.onFinished(true)
                return false
            }
        }).into(target)
    }
}
