package com.suixin.sx2libra

import com.suixin.sx2libra.data.local.ForumMenuConfigCodec
import com.suixin.sx2libra.data.local.ForumMenuLocalDataSource
import com.suixin.sx2libra.data.local.ForumMenuStringStore
import com.suixin.sx2libra.data.repository.DefaultForumMenuRepository
import com.suixin.sx2libra.model.ForumMenuConfig
import com.suixin.sx2libra.model.ForumMenuSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumMenuStorageTest {
    @Test
    fun codecRoundTripsOrderedMenusAndRevision() {
        val config = ForumMenuConfig(revision = 7L, menus = ForumMenuSpec.defaultMenus().reversed())
        assertEquals(config, ForumMenuConfigCodec.decode(ForumMenuConfigCodec.encode(config)))
    }

    @Test
    fun corruptStorageFallsBackToCompleteDefaults() {
        val store = MemoryStore().apply { value = "{not-json" }
        val dataSource = ForumMenuLocalDataSource(store)
        val config = dataSource.readConfig()
        assertEquals(ForumMenuSpec.defaultMenus(), config.menus)
        assertEquals(config, ForumMenuConfigCodec.decode(store.value!!))
    }

    @Test
    fun repositoryIncrementsRevisionOnlyForRealChanges() = runBlocking {
        val store = MemoryStore()
        val dataSource = ForumMenuLocalDataSource(store)
        val repository = DefaultForumMenuRepository(dataSource)
        val original = repository.currentConfig()

        val noOp = repository.reorderMenus(original.menus.map { it.id })
        assertTrue(noOp.isSuccess)
        assertEquals(original.revision, noOp.getOrThrow().revision)

        val added = repository.addMenu("自定义", "node/custom").getOrThrow()
        assertEquals(original.revision + 1L, added.revision)
        val deleted = repository.deleteMenu(added.menus.last().id).getOrThrow()
        assertEquals(added.revision + 1L, deleted.revision)
        assertNotEquals(added.menus, deleted.menus)
    }

    private class MemoryStore : ForumMenuStringStore {
        var value: String? = null
        override fun getString(key: String): String? = value
        override fun putString(key: String, value: String) {
            this.value = value
        }
    }
}

