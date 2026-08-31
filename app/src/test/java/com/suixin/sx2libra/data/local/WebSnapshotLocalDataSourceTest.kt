package com.suixin.sx2libra.data.local

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSnapshotLocalDataSourceTest {
    @Test
    fun keyIsAStableFullLengthSha256Digest() {
        val url = "https://2libra.com/post/latest"

        assertEquals(64, WebSnapshotKey.forUrl(url).length)
        assertEquals(WebSnapshotKey.forUrl(url), WebSnapshotKey.forUrl(url))
        assertFalse(WebSnapshotKey.forUrl(url) == WebSnapshotKey.forUrl("$url?p=2"))
    }

    @Test
    fun writesRoundTripAndExpiredEntriesAreRemoved() {
        val directory = Files.createTempDirectory("web-snapshot-test").toFile()
        try {
            val dataSource = WebSnapshotLocalDataSource(directory)
            val url = "https://2libra.com/"
            val bytes = byteArrayOf(1, 2, 3)

            assertTrue(dataSource.write(url, bytes, nowMillis = 1_000L))
            assertArrayEquals(bytes, dataSource.read(url, nowMillis = 1_000L, maxAgeMillis = 0L))
            assertEquals(null, dataSource.read(url, nowMillis = 1_001L, maxAgeMillis = 0L))
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "webp" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cleanupRemovesOldestEntriesUntilTheSizeLimitIsMet() {
        val directory = Files.createTempDirectory("web-snapshot-clean-test").toFile()
        try {
            val dataSource = WebSnapshotLocalDataSource(directory)
            val firstUrl = "https://2libra.com/post/latest"
            val secondUrl = "https://2libra.com/post/hot/today"
            assertTrue(dataSource.write(firstUrl, ByteArray(4) { 1 }, nowMillis = 1_000L))
            assertTrue(dataSource.write(secondUrl, ByteArray(4) { 2 }, nowMillis = 2_000L))

            dataSource.clean(nowMillis = 2_000L, maxAgeMillis = 10_000L, maxBytes = 4L)

            assertFalse(dataSource.read(firstUrl, 2_000L, 10_000L)?.contentEquals(ByteArray(4) { 1 }) == true)
            assertArrayEquals(ByteArray(4) { 2 }, dataSource.read(secondUrl, 2_000L, 10_000L))
        } finally {
            directory.deleteRecursively()
        }
    }
}
