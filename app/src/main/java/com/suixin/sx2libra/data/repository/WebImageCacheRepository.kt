package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.local.WebImageCacheDataSource
import com.suixin.sx2libra.data.local.WebImageCacheLocalDataSource
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CachedWebImage(
    val path: File,
    val mimeType: String,
)

/** Application-scoped cache boundary shared by every WebView. */
interface WebImageCacheInvalidator {
    fun invalidate()

    suspend fun clear()
}

interface WebImageCacheRepositoryContract : WebImageCacheInvalidator {
    val sizeBytes: StateFlow<Long>

    fun currentGeneration(): Long

    fun read(url: String): CachedWebImage?

    fun write(
        url: String,
        mimeType: String,
        source: InputStream,
        generation: Long,
    ): CachedWebImage?

    override fun invalidate()

    override suspend fun clear()
}

class WebImageCacheRepository(
    private val dataSource: WebImageCacheDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebImageCacheRepositoryContract {
    private val generation = AtomicLong(0L)
    private val maintenanceScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val _sizeBytes = MutableStateFlow(readSize())

    override val sizeBytes: StateFlow<Long> = _sizeBytes.asStateFlow()

    override fun currentGeneration(): Long = generation.get()

    override fun read(url: String): CachedWebImage? =
        runCatching { dataSource.read(url)?.toCachedWebImage() }.getOrNull()

    override fun write(
        url: String,
        mimeType: String,
        source: InputStream,
        generation: Long,
    ): CachedWebImage? {
        if (generation != this.generation.get()) return null
        val entry = runCatching { dataSource.write(url, mimeType, source) }.getOrNull() ?: return null
        if (generation == this.generation.get()) {
            _sizeBytes.value = readSize()
        }
        return entry.toCachedWebImage()
    }

    override fun invalidate() {
        generation.incrementAndGet()
        maintenanceScope.launch {
            runCatching { dataSource.clear() }
                .onSuccess { _sizeBytes.value = 0L }
        }
    }

    override suspend fun clear() {
        generation.incrementAndGet()
        withContext(ioDispatcher) { dataSource.clear() }
        _sizeBytes.value = 0L
    }

    private fun readSize(): Long = runCatching { dataSource.sizeBytes() }.getOrDefault(0L)

    private fun com.suixin.sx2libra.data.local.WebImageCacheDataSourceEntry
        .toCachedWebImage(): CachedWebImage = CachedWebImage(path, mimeType)

    companion object {
        fun forCacheDirectory(cacheDir: File): WebImageCacheRepository =
            WebImageCacheRepository(
                WebImageCacheLocalDataSource(
                    File(cacheDir, WebImageCacheLocalDataSource.CACHE_DIRECTORY),
                ),
            )
    }
}
