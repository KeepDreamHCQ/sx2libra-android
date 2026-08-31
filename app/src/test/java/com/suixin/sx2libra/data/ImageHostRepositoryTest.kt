package com.suixin.sx2libra.data

import com.suixin.sx2libra.data.local.ImageHostDataSource
import com.suixin.sx2libra.data.repository.ImageHostRepository
import com.suixin.sx2libra.model.ImageHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHostRepositoryTest {
    @Test
    fun missingOrInvalidValueFallsBackToTikolu() {
        val missingStore = MemoryStore(null)
        val missingRepository = ImageHostRepository(missingStore)
        assertEquals(ImageHost.TIKOLU, missingRepository.selectedHost.value)
        assertEquals(ImageHost.TIKOLU.key, missingStore.key)

        val invalidStore = MemoryStore("invalid-provider")
        val invalidRepository = ImageHostRepository(invalidStore)
        assertEquals(ImageHost.TIKOLU, invalidRepository.selectedHost.value)
        assertEquals(ImageHost.TIKOLU.key, invalidStore.key)
    }

    @Test
    fun selectingPhotoLilyUpdatesFlowAndPersistsCanonicalKey() {
        val store = MemoryStore(ImageHost.TIKOLU.key)
        val repository = ImageHostRepository(store)

        val result = repository.select(ImageHost.PHOTO_LILY)

        assertTrue(result.isSuccess)
        assertEquals(ImageHost.PHOTO_LILY, repository.selectedHost.value)
        assertEquals(ImageHost.PHOTO_LILY.key, store.key)
    }

    @Test
    fun storageFailureDoesNotChangeSelectedHost() {
        val store = MemoryStore(ImageHost.TIKOLU.key).apply { failWrites = true }
        val repository = ImageHostRepository(store)

        val result = repository.select(ImageHost.PHOTO_LILY)

        assertFalse(result.isSuccess)
        assertEquals(ImageHost.TIKOLU, repository.selectedHost.value)
    }

    private class MemoryStore(var key: String?) : ImageHostDataSource {
        var failWrites = false

        override fun readKey(): String? = key

        override fun writeKey(key: String) {
            if (failWrites) error("storage failure")
            this.key = key
        }
    }
}
