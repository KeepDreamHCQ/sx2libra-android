package com.suixin.sx2libra.ui.posts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixin.sx2libra.data.repository.ForumMenuRepository
import com.suixin.sx2libra.model.ForumMenuConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PostsViewModel(
    private val menuRepository: ForumMenuRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        PostsUiState(selectedMenuId = savedStateHandle[KEY_SELECTED_MENU_ID])
    )
    val uiState: StateFlow<PostsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            menuRepository.observeMenus().collect(::onConfig)
        }
    }

    fun selectMenu(id: String) {
        val state = _uiState.value
        if (state.menus.none { it.id == id }) return
        savedStateHandle[KEY_SELECTED_MENU_ID] = id
        _uiState.value = state.copy(selectedMenuId = id)
    }

    fun refreshMenus() {
        viewModelScope.launch {
            runCatching { menuRepository.refreshMenus() }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = PostsError.MENU_LOAD_FAILED
                    )
                }
        }
    }

    fun onMenuSettingsClosed(revision: Long? = null) {
        // Revision is carried by the result for callers that need it; the Repository remains
        // the single source of truth and deduplicates equal values.
        refreshMenus()
    }

    private fun onConfig(config: ForumMenuConfig) {
        val previousId = _uiState.value.selectedMenuId
        val selectedId = when {
            previousId != null && config.menus.any { it.id == previousId } -> previousId
            config.menus.isNotEmpty() -> config.menus.first().id
            else -> null
        }
        if (selectedId == null) {
            _uiState.value = PostsUiState(
                menus = config.menus,
                isLoading = false,
                error = PostsError.MENU_LOAD_FAILED
            )
            return
        }
        savedStateHandle[KEY_SELECTED_MENU_ID] = selectedId
        _uiState.value = PostsUiState(
            menus = config.menus,
            selectedMenuId = selectedId,
            isLoading = false,
            error = null
        )
    }

    companion object {
        private const val KEY_SELECTED_MENU_ID = "posts.selectedMenuId"
    }
}

