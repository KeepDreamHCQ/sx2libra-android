package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.local.WebImageCacheLocalDataSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebImageCacheRepositoryTest {
    @Test
    fun writePublishesSizeAndClearRemovesCachedImage() = runBlocking {
        val directory = Files.createTempDirectory("web-image-repository-test").toFile()
        try {
            val repository = WebImageCacheRepository(
                WebImageCacheLocalDataSource(directory),
                Dispatchers.Unconfined,
            )
            val url = "https://cdn.example.test/image.webp"
            val generation = repository.currentGeneration()

            val written = repository.write(
                url,
                "image/webp",
                ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                generation,
            )

            assertNotNull(written)
            assertEquals(3L, repository.sizeBytes.value)
            assertTrue(repository.read(url)!!.path.isFile)

            repository.clear()

            assertEquals(0L, repository.sizeBytes.value)
            assertNull(repository.read(url))
            assertNotEquals(generation, repository.currentGeneration())
        } finally {
            directory.deleteRecursively()
        }
    }
}
