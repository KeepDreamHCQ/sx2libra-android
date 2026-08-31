package com.suixin.sx2libra.data.repository

import com.suixin.sx2libra.data.local.ForumMenuDataSource
import com.suixin.sx2libra.model.ForumMenu
import com.suixin.sx2libra.model.ForumMenuConfig
import com.suixin.sx2libra.model.ForumMenuSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ForumMenuError {
    INVALID_NAME,
    INVALID_PATH,
    DUPLICATE_NAME,
    DUPLICATE_PATH,
    MENU_NOT_FOUND,
    LAST_MENU,
    INVALID_ORDER,
    STORAGE
}

class ForumMenuRepositoryException(
    val reason: ForumMenuError,
    message: String
) : IllegalArgumentException(message)

/** Repository boundary consumed by screen ViewModels. */
interface ForumMenuRepository {
    fun observeMenus(): Flow<ForumMenuConfig>
    fun currentConfig(): ForumMenuConfig
    suspend fun refreshMenus(): ForumMenuConfig
    suspend fun addMenu(name: String, path: String): Result<ForumMenuConfig>
    suspend fun deleteMenu(id: String): Result<ForumMenuConfig>
    suspend fun reorderMenus(ids: List<String>): Result<ForumMenuConfig>
}

/**
 * Single source of truth for the ordered forum menu list. Every mutation is serialized and
 * increments revision exactly once only when the ordered content really changes.
 */
class DefaultForumMenuRepository(
    private val dataSource: ForumMenuDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ForumMenuRepository {
    private val mutex = Mutex()
    private val state = MutableStateFlow(readCanonical())

    override fun observeMenus(): Flow<ForumMenuConfig> = state.asStateFlow()

    override fun currentConfig(): ForumMenuConfig = state.value

    override suspend fun refreshMenus(): ForumMenuConfig = withContext(ioDispatcher) {
        mutex.withLock {
            val latest = readCanonical()
            if (latest != state.value) state.value = latest
            latest
        }
    }

    override suspend fun addMenu(name: String, path: String): Result<ForumMenuConfig> =
        mutate { current ->
            val normalizedName = ForumMenuSpec.normalizeName(name).getOrElse {
                throw ForumMenuRepositoryException(ForumMenuError.INVALID_NAME, "菜单名称无效")
            }
            val normalizedPath = ForumMenuSpec.normalizePath(path).getOrElse {
                throw ForumMenuRepositoryException(ForumMenuError.INVALID_PATH, "菜单路径无效")
            }
            if (current.menus.any { it.name == normalizedName }) {
                throw ForumMenuRepositoryException(ForumMenuError.DUPLICATE_NAME, "菜单名称已存在")
            }
            if (current.menus.any { it.path == normalizedPath }) {
                throw ForumMenuRepositoryException(ForumMenuError.DUPLICATE_PATH, "菜单路径已存在")
            }
            current.copy(
                menus = current.menus + ForumMenu(
                    id = ForumMenuSpec.newId(),
                    name = normalizedName,
                    path = normalizedPath
                )
            )
        }

    override suspend fun deleteMenu(id: String): Result<ForumMenuConfig> = mutate { current ->
        if (current.menus.size <= 1) {
            throw ForumMenuRepositoryException(ForumMenuError.LAST_MENU, "至少保留一个菜单")
        }
        if (current.menus.none { it.id == id }) {
            throw ForumMenuRepositoryException(ForumMenuError.MENU_NOT_FOUND, "菜单不存在")
        }
        current.copy(menus = current.menus.filterNot { it.id == id })
    }

    override suspend fun reorderMenus(ids: List<String>): Result<ForumMenuConfig> = mutate { current ->
        val currentIds = current.menus.map(ForumMenu::id)
        if (ids.size != currentIds.size || ids.toSet().size != ids.size || ids.toSet() != currentIds.toSet()) {
            throw ForumMenuRepositoryException(ForumMenuError.INVALID_ORDER, "菜单顺序无效")
        }
        val byId = current.menus.associateBy(ForumMenu::id)
        current.copy(menus = ids.map { byId.getValue(it) })
    }

    private suspend fun mutate(transform: (ForumMenuConfig) -> ForumMenuConfig): Result<ForumMenuConfig> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val current = readCanonical()
                    val candidate = ForumMenuSpec.normalizeConfig(transform(current))
                        .getOrElse { throw ForumMenuRepositoryException(ForumMenuError.STORAGE, "菜单配置无效") }
                    if (candidate.menus == current.menus) {
                        if (state.value != current) state.value = current
                        return@withLock Result.success(current)
                    }
                    val saved = candidate.copy(revision = current.revision + 1L)
                    dataSource.writeConfig(saved)
                    state.value = saved
                    Result.success(saved)
                } catch (error: ForumMenuRepositoryException) {
                    Result.failure(error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(ForumMenuRepositoryException(ForumMenuError.STORAGE, "菜单保存失败"))
                }
            }
        }

    private fun readCanonical(): ForumMenuConfig {
        val read = runCatching { dataSource.readConfig() }.getOrNull()
            ?: ForumMenuConfig(revision = 0L, menus = ForumMenuSpec.defaultMenus())
        return ForumMenuSpec.normalizeConfig(read).getOrElse {
            ForumMenuConfig(revision = 0L, menus = ForumMenuSpec.defaultMenus())
        }
    }
}
