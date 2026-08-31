package com.suixin.sx2libra.data.local

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Byte-oriented persistence boundary for the WebView snapshot cache. */
interface WebSnapshotDataSource {
    fun read(url: String, nowMillis: Long, maxAgeMillis: Long): ByteArray?

    fun write(url: String, bytes: ByteArray, nowMillis: Long): Boolean

    fun delete(url: String)

    fun clear()

    fun clean(nowMillis: Long, maxAgeMillis: Long, maxBytes: Long)
}

/**
 * Private, cache-directory-backed snapshot storage.
 *
 * The data source only accepts URL-derived names and never uses a URL as a
 * filesystem path. Writes happen through a same-directory temporary file so
 * readers never observe a partially encoded image.
 */
class WebSnapshotLocalDataSource(
    private val directory: File,
) : WebSnapshotDataSource {
    @Synchronized
    override fun read(url: String, nowMillis: Long, maxAgeMillis: Long): ByteArray? {
        require(maxAgeMillis >= 0L) { "maxAgeMillis must not be negative" }
        val file = fileFor(url)
        if (!file.isFile) return null

        val modified = file.lastModified()
        if (modified <= 0L || nowMillis - modified > maxAgeMillis || file.length() > MAX_ENTRY_BYTES) {
            file.delete()
            return null
        }

        return try {
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    override fun write(url: String, bytes: ByteArray, nowMillis: Long): Boolean {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_ENTRY_BYTES) return false
        if (!directory.exists() && !directory.mkdirs()) return false

        val target = fileFor(url)
        val temporary = try {
            File.createTempFile("${WebSnapshotKey.forUrl(url).take(12)}-", ".tmp", directory)
        } catch (_: IOException) {
            return false
        }

        return try {
            temporary.outputStream().use { it.write(bytes) }
            temporary.setLastModified(nowMillis)
            atomicReplace(temporary, target)
        } catch (_: IOException) {
            false
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    @Synchronized
    override fun delete(url: String) {
        fileFor(url).delete()
    }

    @Synchronized
    override fun clear() {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && isManagedFile(file)) file.delete()
        }
    }

    @Synchronized
    override fun clean(nowMillis: Long, maxAgeMillis: Long, maxBytes: Long) {
        require(maxAgeMillis >= 0L) { "maxAgeMillis must not be negative" }
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
        val files = directory.listFiles()
            ?.filter { it.isFile && isManagedFile(it) }
            ?.toMutableList()
            ?: return

        files.filter { file ->
            file.name.endsWith(TEMP_SUFFIX) ||
                file.lastModified() <= 0L ||
                nowMillis - file.lastModified() > maxAgeMillis
        }.forEach { file ->
            file.delete()
            files.remove(file)
        }

        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= maxBytes) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= maxBytes) return@forEach
            val size = file.length()
            if (file.delete()) totalBytes -= size
        }
    }

    private fun fileFor(url: String): File =
        File(directory, "${WebSnapshotKey.forUrl(url)}$SNAPSHOT_SUFFIX")

    private fun isManagedFile(file: File): Boolean =
        file.name.endsWith(SNAPSHOT_SUFFIX) || file.name.endsWith(TEMP_SUFFIX)

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
        }
    }

    companion object {
        const val SNAPSHOT_SUFFIX: String = ".webp"
        const val TEMP_SUFFIX: String = ".tmp"
        const val MAX_ENTRY_BYTES: Long = 4L * 1024L * 1024L
    }
}

/** Stable, path-safe key for a normalized page URL. */
object WebSnapshotKey {
    fun forUrl(url: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
