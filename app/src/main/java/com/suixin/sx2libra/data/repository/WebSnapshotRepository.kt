package com.suixin.sx2libra.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.suixin.sx2libra.data.local.WebSnapshotDataSource
import com.suixin.sx2libra.data.local.WebSnapshotLocalDataSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session boundary used to invalidate page snapshots without exposing cookies. */
interface WebSnapshotInvalidator {
    fun invalidate()

    suspend fun clear()
}

interface WebSnapshotRepositoryContract : WebSnapshotInvalidator {
    suspend fun read(url: String, targetWidth: Int, targetHeight: Int): Bitmap?

    suspend fun save(url: String, bitmap: Bitmap): Boolean

    override suspend fun clear()

    fun scheduleCleanup()
}

/**
 * Encodes and decodes the WebView's visible viewport while keeping all file IO
 * behind the repository/data-source boundary.
 */
class WebSnapshotRepository(
    private val dataSource: WebSnapshotDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebSnapshotRepositoryContract {
    private val invalidationEpoch = AtomicLong(0L)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override suspend fun read(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val epoch = invalidationEpoch.get()
        val encoded = withContext(ioDispatcher) {
            dataSource.read(url, clock(), SNAPSHOT_MAX_AGE_MILLIS)
        } ?: return null
        if (epoch != invalidationEpoch.get()) return null

        val bitmap = withContext(ioDispatcher) {
            decode(encoded, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        }
        if (bitmap == null || !isUsable(bitmap)) {
            bitmap?.recycle()
            withContext(ioDispatcher) { dataSource.delete(url) }
            return null
        }
        if (epoch != invalidationEpoch.get()) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    override suspend fun save(url: String, bitmap: Bitmap): Boolean {
        if (!isUsable(bitmap)) return false
        val epoch = invalidationEpoch.get()
        val encoded = withContext(ioDispatcher) {
            runCatching {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, output)) {
                        return@runCatching null
                    }
                    output.toByteArray()
                }
            }.getOrNull()
        } ?: return false
        if (encoded.isEmpty() || encoded.size.toLong() > WebSnapshotLocalDataSource.MAX_ENTRY_BYTES) {
            return false
        }
        if (epoch != invalidationEpoch.get()) return false
        return withContext(ioDispatcher) {
            if (epoch != invalidationEpoch.get()) return@withContext false
            dataSource.write(url, encoded, clock())
        }
    }

    override suspend fun clear() {
        invalidationEpoch.incrementAndGet()
        withContext(ioDispatcher) { dataSource.clear() }
    }

    override fun invalidate() {
        invalidationEpoch.incrementAndGet()
        maintenanceScope.launch { dataSource.clear() }
    }

    override fun scheduleCleanup() {
        maintenanceScope.launch {
            dataSource.clean(
                nowMillis = clock(),
                maxAgeMillis = SNAPSHOT_MAX_AGE_MILLIS,
                maxBytes = MAX_CACHE_BYTES,
            )
        }
    }

    private fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (width / sample > targetWidth * 2 || height / sample > targetHeight * 2) {
            sample *= 2
        }
        return sample
    }

    /** Rejects empty, transparent, single-color and blank render results. */
    private fun isUsable(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val first = bitmap.getPixel(0, 0)
        var hasVisiblePixel = Color.alpha(first) != 0
        var hasVariation = false
        val xStep = (bitmap.width / 8).coerceAtLeast(1)
        val yStep = (bitmap.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                hasVisiblePixel = hasVisiblePixel || Color.alpha(pixel) != 0
                hasVariation = hasVariation || pixel != first
                x += xStep
            }
            y += yStep
        }
        return hasVisiblePixel && hasVariation
    }

    companion object {
        const val CACHE_DIRECTORY: String = "web_snapshots"
        const val WEBP_QUALITY: Int = 85
        const val MAX_CACHE_BYTES: Long = 20L * 1024L * 1024L
        const val SNAPSHOT_MAX_AGE_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L

        fun forCacheDirectory(cacheDir: File): WebSnapshotRepository =
            WebSnapshotRepository(
                WebSnapshotLocalDataSource(File(cacheDir, CACHE_DIRECTORY)),
            )
    }
}
