package com.suixin.sx2libra.data.local

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebImageCacheLocalDataSourceTest {
    @Test
    fun urlIndexRoundTripsImageBytesAndMimeType() {
        val directory = Files.createTempDirectory("web-image-cache-test").toFile()
        try {
            val dataSource = WebImageCacheLocalDataSource(directory)
            val url = "https://cdn.example.test/image?id=1"
            val bytes = byteArrayOf(1, 2, 3, 4)

            val written = dataSource.write(url, "image/png", ByteArrayInputStream(bytes))

            assertNotNull(written)
            val read = dataSource.read(url)
            assertNotNull(read)
            assertEquals(written!!.path.canonicalPath, read!!.path.canonicalPath)
            assertEquals("image/png", read.mimeType)
            assertArrayEquals(bytes, read.path.readBytes())
            assertEquals(bytes.size.toLong(), dataSource.sizeBytes())
            assertFalse(read.path.absolutePath.contains(url))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun differentUrlsAndQueryValuesHaveIndependentEntries() {
        val directory = Files.createTempDirectory("web-image-cache-query-test").toFile()
        try {
            val dataSource = WebImageCacheLocalDataSource(directory)
            val firstUrl = "https://cdn.example.test/image?id=1"
            val secondUrl = "https://cdn.example.test/image?id=2"

            dataSource.write(firstUrl, "image/jpeg", ByteArrayInputStream(byteArrayOf(1)))
            dataSource.write(secondUrl, "image/jpeg", ByteArrayInputStream(byteArrayOf(2, 3)))

            assertArrayEquals(byteArrayOf(1), dataSource.read(firstUrl)!!.path.readBytes())
            assertArrayEquals(byteArrayOf(2, 3), dataSource.read(secondUrl)!!.path.readBytes())
            assertEquals(3L, dataSource.sizeBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingFilesAreRemovedFromTheIndexAndClearRemovesAllFiles() {
        val directory = Files.createTempDirectory("web-image-cache-clear-test").toFile()
        try {
            val dataSource = WebImageCacheLocalDataSource(directory)
            val url = "https://cdn.example.test/image.png"
            val entry = dataSource.write(
                url,
                "image/png",
                ByteArrayInputStream(byteArrayOf(9, 8)),
            )!!

            assertTrue(entry.path.delete())
            assertNull(dataSource.read(url))
            assertEquals(0L, dataSource.sizeBytes())

            dataSource.write(url, "image/png", ByteArrayInputStream(byteArrayOf(7)))
            dataSource.clear()

            assertNull(dataSource.read(url))
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
