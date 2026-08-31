package com.suixin.sx2libra.ui.posts

import com.suixin.sx2libra.model.ForumMenu

enum class PostsError {
    MENU_LOAD_FAILED
}

data class PostsUiState(
    val menus: List<ForumMenu> = emptyList(),
    val selectedMenuId: String? = null,
    val isLoading: Boolean = true,
    val error: PostsError? = null
)
