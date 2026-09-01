package com.suixin.sx2libra.data.local

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/** File and URL-index boundary for images intercepted by WebView. */
interface WebImageCacheDataSource {
    fun read(url: String): WebImageCacheDataSourceEntry?

    fun write(url: String, mimeType: String, source: InputStream): WebImageCacheDataSourceEntry?

    fun delete(url: String)

    fun clear()

    fun sizeBytes(): Long
}

data class WebImageCacheDataSourceEntry(
    val path: File,
    val mimeType: String,
)

/**
 * Private cache-directory-backed image storage.
 *
 * The URL is the key in the path index and the absolute image file path is its
 * value.  A parallel index stores the response MIME type required to rebuild a
 * WebResourceResponse on a later WebView request.
 */
class WebImageCacheLocalDataSource(
    private val directory: File,
) : WebImageCacheDataSource {
    private val pathIndexFile: File
        get() = File(directory, PATH_INDEX_FILE)

    private val mimeIndexFile: File
        get() = File(directory, MIME_INDEX_FILE)

    @Synchronized
    override fun read(url: String): WebImageCacheDataSourceEntry? {
        val paths = readProperties(pathIndexFile)
        val pathValue = paths.getProperty(url) ?: return null
        val mimeType = readProperties(mimeIndexFile)
            .getProperty(url)
            ?.trim()
            ?.lowercase(Locale.US)
        val file = pathValue.toManagedFileOrNull()
        if (mimeType.isNullOrBlank() || !mimeType.startsWith("image/") ||
            file == null || !file.isFile || file.length() <= 0L
        ) {
            removeEntry(paths, url)
            return null
        }
        return WebImageCacheDataSourceEntry(file, mimeType)
    }

    @Synchronized
    override fun write(
        url: String,
        mimeType: String,
        source: InputStream,
    ): WebImageCacheDataSourceEntry? {
        val normalizedMimeType = mimeType.trim().lowercase(Locale.US)
        if (url.isBlank() || !normalizedMimeType.startsWith("image/")) return null
        if (!directory.exists() && !directory.mkdirs()) return null

        val target = fileFor(url)
        val temporary = try {
            File.createTempFile("${WebImageCacheKey.forUrl(url).take(12)}-", TEMP_SUFFIX, directory)
        } catch (_: IOException) {
            return null
        }

        return try {
            source.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() <= 0L || !atomicReplace(temporary, target)) {
                null
            } else {
                val paths = readProperties(pathIndexFile)
                val mimes = readProperties(mimeIndexFile)
                paths.setProperty(url, target.absolutePath)
                mimes.setProperty(url, normalizedMimeType)
                // The image is already complete and can serve the current
                // request even if an index write is interrupted. The next
                // request will simply fetch it again if the mapping is absent.
                runCatching {
                    storeProperties(pathIndexFile, paths)
                    storeProperties(mimeIndexFile, mimes)
                }
                WebImageCacheDataSourceEntry(target, normalizedMimeType)
            }
        } catch (_: IOException) {
            null
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    override fun delete(url: String) {
        val paths = readProperties(pathIndexFile)
        val path = paths.getProperty(url)?.toManagedFileOrNull()
        path?.delete()
        removeEntry(paths, url)
    }

    @Synchronized
    override fun clear() {
        // This directory is owned exclusively by the image cache, so clearing
        // every file also removes orphaned files left by an interrupted index
        // update.
        directory.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
    }

    @Synchronized
    override fun sizeBytes(): Long {
        val paths = readProperties(pathIndexFile)
        val mimes = readProperties(mimeIndexFile)
        val invalidUrls = ArrayList<String>()
        var total = 0L
        paths.stringPropertyNames().forEach { url ->
            val file = paths.getProperty(url)?.toManagedFileOrNull()
            val mimeType = mimes.getProperty(url)
            if (file == null || !file.isFile || file.length() <= 0L ||
                mimeType.isNullOrBlank() || !mimeType.startsWith("image/", ignoreCase = true)
            ) {
                invalidUrls += url
            } else {
                total += file.length()
            }
        }
        if (invalidUrls.isNotEmpty()) {
            invalidUrls.forEach {
                paths.remove(it)
                mimes.remove(it)
            }
            runCatching {
                storeProperties(pathIndexFile, paths)
                storeProperties(mimeIndexFile, mimes)
            }
        }
        return total
    }

    private fun fileFor(url: String): File =
        File(directory, WebImageCacheKey.forUrl(url))

    private fun String.toManagedFileOrNull(): File? {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(this).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.path + File.separator
        return candidate.takeIf { it.path.startsWith(rootPath) && it.parentFile == root }
    }

    private fun removeEntry(paths: Properties, url: String) {
        paths.remove(url)
        val mimes = readProperties(mimeIndexFile)
        mimes.remove(url)
        runCatching {
            storeProperties(pathIndexFile, paths)
            storeProperties(mimeIndexFile, mimes)
        }
    }

    private fun readProperties(file: File): Properties = Properties().also { properties ->
        if (!file.isFile) return@also
        runCatching {
            FileInputStream(file).use(properties::load)
        }
    }

    private fun storeProperties(file: File, properties: Properties) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create image cache directory")
        }
        val temporary = File.createTempFile("${file.name}-", TEMP_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { output -> properties.store(output, null) }
            if (!atomicReplace(temporary, file)) {
                throw IOException("Unable to publish image cache index")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun atomicReplace(temporary: File, target: File): Boolean {
        return try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
        } catch (_: UnsupportedOperationException) {
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        const val CACHE_DIRECTORY: String = "web_images"
        private const val PATH_INDEX_FILE = "url_to_path.properties"
        private const val MIME_INDEX_FILE = "url_to_mime.properties"
        private const val TEMP_SUFFIX = ".tmp"
    }
}

/** Stable path-safe key used only for the physical cache file name. */
object WebImageCacheKey {
    fun forUrl(url: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
