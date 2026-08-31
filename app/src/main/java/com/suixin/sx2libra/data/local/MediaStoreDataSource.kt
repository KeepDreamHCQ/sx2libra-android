package com.suixin.sx2libra.data.local

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.MediaSaveError
import com.suixin.sx2libra.model.MediaSaveResult
import com.suixin.sx2libra.model.MediaUrlPolicy
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

fun interface SaveCancellation {
    fun isCancelled(): Boolean
}

interface MediaStoreWriter {
    fun saveImage(
        url: String,
        suggestedName: String?,
        cancellation: SaveCancellation = SaveCancellation { false }
    ): MediaSaveResult
}

/**
 * Streams an original image into MediaStore without decoding or re-encoding
 * it. The only Context retained is the application Context.
 */
class MediaStoreDataSource(context: Context) : MediaStoreWriter {
    private val appContext = context.applicationContext

    override fun saveImage(
        url: String,
        suggestedName: String?,
        cancellation: SaveCancellation
    ): MediaSaveResult {
        if (!MediaUrlPolicy.isAllowedImageUrl(url)) {
            return MediaSaveResult.Failed(MediaSaveError.INVALID_URL)
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = openImageConnection(url)
            if (cancellation.isCancelled()) {
                MediaSaveResult.Failed(MediaSaveError.CANCELLED)
            } else {
                val responseMime = normalizeResponseMime(connection.getHeaderField("Content-Type"))
                val contentLength = connection.contentLengthLong
                if (contentLength > MediaUrlPolicy.MAX_MEDIA_BYTES) {
                    MediaSaveResult.Failed(MediaSaveError.RESPONSE_TOO_LARGE)
                } else {
                    saveResponse(
                        connection.inputStream,
                        responseMime,
                        suggestedName,
                        cancellation
                    )
                }
            }
        } catch (_: CancelledSaveException) {
            MediaSaveResult.Failed(MediaSaveError.CANCELLED)
        } catch (_: TooLargeException) {
            MediaSaveResult.Failed(MediaSaveError.RESPONSE_TOO_LARGE)
        } catch (_: UnsupportedImageException) {
            MediaSaveResult.Failed(MediaSaveError.UNSUPPORTED_MIME)
        } catch (_: IOException) {
            if (cancellation.isCancelled()) {
                MediaSaveResult.Failed(MediaSaveError.CANCELLED)
            } else {
                MediaSaveResult.Failed(MediaSaveError.NETWORK_ERROR)
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun saveResponse(
        source: InputStream,
        responseMime: String?,
        suggestedName: String?,
        cancellation: SaveCancellation
    ): MediaSaveResult {
        BufferedInputStream(source, COPY_BUFFER_BYTES).use { input ->
            val prefix = ByteArray(SNIFF_BYTES)
            var prefixSize = 0
            while (prefixSize < prefix.size) {
                if (cancellation.isCancelled()) throw CancelledSaveException()
                val read = input.read(prefix, prefixSize, prefix.size - prefixSize)
                if (read < 0) break
                if (read == 0) continue
                prefixSize += read
            }
            val sniffedMime = sniffMime(prefix, prefixSize)
                ?: throw UnsupportedImageException()
            if (responseMime != null && responseMime != sniffedMime) {
                throw UnsupportedImageException()
            }
            val mime = responseMime ?: sniffedMime
            val name = displayName(suggestedName, mime)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(input, prefix, prefixSize, mime, name, cancellation)
            } else {
                saveToPublicPictures(input, prefix, prefixSize, mime, name, cancellation)
            }
        }
    }

    private fun saveToMediaStore(
        input: InputStream,
        prefix: ByteArray,
        prefixSize: Int,
        mime: String,
        name: String,
        cancellation: SaveCancellation
    ): MediaSaveResult {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/2Libra")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return MediaSaveResult.Failed(MediaSaveError.STORAGE_ERROR)
        var published = false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                writeResponse(input, output, prefix, prefixSize, cancellation)
            } ?: throw IOException("Unable to open MediaStore output")
            val publishedValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publishedValues, null, null)
            published = true
            MediaSaveResult.Saved(uri.toString(), mime, name)
        } catch (error: CancelledSaveException) {
            throw error
        } catch (_: TooLargeException) {
            throw TooLargeException()
        } catch (_: IOException) {
            MediaSaveResult.Failed(MediaSaveError.STORAGE_ERROR)
        } finally {
            if (!published) resolver.delete(uri, null, null)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(
        input: InputStream,
        prefix: ByteArray,
        prefixSize: Int,
        mime: String,
        name: String,
        cancellation: SaveCancellation
    ): MediaSaveResult {
        if (appContext.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return MediaSaveResult.Failed(MediaSaveError.STORAGE_ERROR)
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "2Libra"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            return MediaSaveResult.Failed(MediaSaveError.STORAGE_ERROR)
        }
        val target = File(directory, name)
        val temporary = File.createTempFile(".2libra-", ".tmp", directory)
        return try {
            FileOutputStream(temporary).use { output ->
                writeResponse(input, output, prefix, prefixSize, cancellation)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Unable to publish image")
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(target.absolutePath),
                arrayOf(mime),
                null
            )
            MediaSaveResult.Saved(Uri.fromFile(target).toString(), mime, name)
        } catch (error: CancelledSaveException) {
            throw error
        } catch (_: TooLargeException) {
            throw TooLargeException()
        } catch (_: IOException) {
            MediaSaveResult.Failed(MediaSaveError.STORAGE_ERROR)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun writeResponse(
        input: InputStream,
        output: OutputStream,
        prefix: ByteArray,
        prefixSize: Int,
        cancellation: SaveCancellation
    ) {
        var copied = prefixSize.toLong()
        output.write(prefix, 0, prefixSize)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted) {
                throw CancelledSaveException()
            }
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            if (copied > MediaUrlPolicy.MAX_MEDIA_BYTES) throw TooLargeException()
            output.write(buffer, 0, read)
        }
    }

    private fun openImageConnection(initialUrl: String): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            val candidate = URL(current)
            val connection = (candidate.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                setRequestProperty("Accept", "image/jpeg,image/png,image/gif,image/webp")
            }
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in 300..399 && hop < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("Redirect without location")
                val resolved = URI(current).resolve(location).toString()
                if (!MediaUrlPolicy.isAllowedImageUrl(resolved)) {
                    connection.disconnect()
                    throw UnsupportedImageException()
                }
                connection.disconnect()
                current = resolved
            } else {
                connection.disconnect()
                throw IOException("Image request failed")
            }
        }
        throw IOException("Too many redirects")
    }

    private fun normalizeResponseMime(contentType: String?): String? {
        val value = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            ?: return null
        return ImageMimeTypes.normalize(value) ?: throw UnsupportedImageException()
    }

    private fun sniffMime(prefix: ByteArray, size: Int): String? {
        fun startsWith(vararg values: Int): Boolean =
            size >= values.size && values.indices.all { prefix[it].toInt() and 0xff == values[it] }

        return when {
            startsWith(0xff, 0xd8, 0xff) -> ImageMimeTypes.JPEG
            startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> ImageMimeTypes.PNG
            startsWith(0x47, 0x49, 0x46, 0x38) -> ImageMimeTypes.GIF
            size >= 12 &&
                prefix[0].toInt() == 'R'.code && prefix[1].toInt() == 'I'.code &&
                prefix[2].toInt() == 'F'.code && prefix[3].toInt() == 'F'.code &&
                prefix[8].toInt() == 'W'.code && prefix[9].toInt() == 'E'.code &&
                prefix[10].toInt() == 'B'.code && prefix[11].toInt() == 'P'.code -> ImageMimeTypes.WEBP
            else -> null
        }
    }

    private fun displayName(suggestedName: String?, mime: String): String {
        val base = suggestedName.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .take(100)
            .ifEmpty { "2libra-image" }
        val extension = ".${ImageMimeTypes.extensionFor(mime)}"
        val withoutExtension = base.substringBeforeLast('.', base)
        return (withoutExtension + extension).take(120)
    }

    private class CancelledSaveException : IOException()
    private class TooLargeException : IOException()
    private class UnsupportedImageException : IOException()

    private companion object {
        const val MAX_REDIRECTS = 3
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val SNIFF_BYTES = 16
    }
}

