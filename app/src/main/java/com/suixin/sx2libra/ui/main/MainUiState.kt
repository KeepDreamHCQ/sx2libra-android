package com.suixin.sx2libra.ui.main

enum class MainRootTab(val itemId: Int, val route: String) {
    POSTS(com.suixin.sx2libra.R.id.nav_posts, "/"),
    MESSAGES(com.suixin.sx2libra.R.id.nav_messages, "/notifications"),
    PROFILE(com.suixin.sx2libra.R.id.nav_profile, "/user/{username}/about");

    companion object {
        fun fromItemId(itemId: Int): MainRootTab? = entries.firstOrNull { it.itemId == itemId }
    }
}

data class MainUiState(
    val selectedTab: MainRootTab = MainRootTab.POSTS,
)
