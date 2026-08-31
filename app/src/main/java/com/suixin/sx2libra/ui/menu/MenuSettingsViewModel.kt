package com.suixin.sx2libra.ui.menu

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixin.sx2libra.data.local.SiteRouteCatalog
import com.suixin.sx2libra.data.repository.ForumMenuError
import com.suixin.sx2libra.data.repository.ForumMenuRepository
import com.suixin.sx2libra.data.repository.ForumMenuRepositoryException
import com.suixin.sx2libra.data.repository.ImageHostRepository
import com.suixin.sx2libra.model.ForumMenu
import com.suixin.sx2libra.model.ImageHost
import com.suixin.sx2libra.model.SiteRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MenuSettingsViewModel(
    private val menuRepository: ForumMenuRepository,
    private val savedStateHandle: SavedStateHandle,
    private val imageHostRepository: ImageHostRepository,
) : ViewModel() {
    private val siteRoutes: List<SiteRoute> = SiteRouteCatalog.routes
    private val startConfig = menuRepository.currentConfig()
    private val restoredPendingDelete = savedStateHandle.get<String>(KEY_PENDING_DELETE_ID)
        ?.let { id ->
            startConfig.menus.firstOrNull { it.id == id }?.let { menu ->
                PendingDelete(
                    id = id,
                    name = menu.name,
                    requestId = savedStateHandle.get<String>(KEY_PENDING_DELETE_REQUEST)
                        ?: java.util.UUID.randomUUID().toString()
                )
            }
        }
    private val _uiState = MutableStateFlow(
        MenuSettingsUiState(
            menus = startConfig.menus,
            startMenus = startConfig.menus,
            startRevision = startConfig.revision,
            currentRevision = startConfig.revision,
            pendingDelete = restoredPendingDelete,
            availableRoutes = filterAvailableRoutes(siteRoutes, startConfig.menus),
            selectedImageHost = imageHostRepository.selectedHost.value,
            isLoading = false
        )
    )
    val uiState: StateFlow<MenuSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            menuRepository.observeMenus().collect { config ->
                val pending = _uiState.value.pendingDelete?.let { pendingDelete ->
                    config.menus.firstOrNull { it.id == pendingDelete.id }?.let {
                        pendingDelete.copy(name = it.name)
                    }
                }
                _uiState.value = _uiState.value.copy(
                    menus = config.menus,
                    currentRevision = config.revision,
                    availableRoutes = filterAvailableRoutes(siteRoutes, config.menus),
                    pendingDelete = pending,
                    isLoading = false,
                    error = null
                )
            }
        }
        viewModelScope.launch {
            imageHostRepository.selectedHost.collect { host ->
                _uiState.value = _uiState.value.copy(
                    selectedImageHost = host,
                )
            }
        }
    }

    fun selectImageHost(host: ImageHost) {
        val result = imageHostRepository.select(host)
        if (result.isSuccess) {
            _uiState.value = _uiState.value.copy(error = null)
        } else {
            setError(MenuSettingsError.STORAGE)
        }
    }

    fun addMenu(name: String, path: String) {
        viewModelScope.launch {
            val result = menuRepository.addMenu(name, path)
            publishResult(result)
        }
    }

    fun requestDelete(id: String) {
        val state = _uiState.value
        val menu = state.menus.firstOrNull { it.id == id } ?: run {
            setError(MenuSettingsError.MENU_NOT_FOUND)
            return
        }
        if (state.menus.size <= 1) {
            setError(MenuSettingsError.LAST_MENU)
            return
        }
        if (state.pendingDelete?.id == id) return
        val requestId = java.util.UUID.randomUUID().toString()
        savedStateHandle[KEY_PENDING_DELETE_ID] = id
        savedStateHandle[KEY_PENDING_DELETE_REQUEST] = requestId
        _uiState.value = state.copy(
            pendingDelete = PendingDelete(id, menu.name, requestId),
            error = null
        )
    }

    fun cancelDelete() {
        clearPendingDelete()
    }

    fun confirmDelete() {
        val pending = _uiState.value.pendingDelete ?: return
        viewModelScope.launch {
            val result = menuRepository.deleteMenu(pending.id)
            clearPendingDelete()
            publishResult(result)
        }
    }

    fun onReorderFinished(ids: List<String>) {
        if (ids == _uiState.value.menus.map(ForumMenu::id)) return
        viewModelScope.launch {
            val result = menuRepository.reorderMenus(ids)
            publishResult(result)
        }
    }

    private fun clearPendingDelete() {
        savedStateHandle[KEY_PENDING_DELETE_ID] = null
        savedStateHandle[KEY_PENDING_DELETE_REQUEST] = null
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    private fun publishResult(result: Result<*>) {
        result.exceptionOrNull()?.let { error ->
            val reason = (error as? ForumMenuRepositoryException)?.reason
            setError(reason?.toSettingsError() ?: MenuSettingsError.STORAGE)
        }
    }

    private fun setError(error: MenuSettingsError) {
        _uiState.value = _uiState.value.copy(error = error)
    }

    private fun filterAvailableRoutes(
        routes: List<SiteRoute>,
        menus: List<ForumMenu>,
    ): List<SiteRoute> {
        val existingPaths = menus.mapTo(HashSet(), ForumMenu::path)
        return routes.filterNot { it.path in existingPaths }
    }

    private fun ForumMenuError.toSettingsError(): MenuSettingsError = when (this) {
        ForumMenuError.INVALID_NAME -> MenuSettingsError.INVALID_NAME
        ForumMenuError.INVALID_PATH -> MenuSettingsError.INVALID_PATH
        ForumMenuError.DUPLICATE_NAME -> MenuSettingsError.DUPLICATE_NAME
        ForumMenuError.DUPLICATE_PATH -> MenuSettingsError.DUPLICATE_PATH
        ForumMenuError.MENU_NOT_FOUND -> MenuSettingsError.MENU_NOT_FOUND
        ForumMenuError.LAST_MENU -> MenuSettingsError.LAST_MENU
        ForumMenuError.INVALID_ORDER -> MenuSettingsError.INVALID_ORDER
        ForumMenuError.STORAGE -> MenuSettingsError.STORAGE
    }

    companion object {
        private const val KEY_PENDING_DELETE_ID = "menu.pendingDeleteId"
        private const val KEY_PENDING_DELETE_REQUEST = "menu.pendingDeleteRequest"
    }
}
