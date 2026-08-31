package com.suixin.sx2libra.ui.menu

import com.suixin.sx2libra.model.ForumMenu
import com.suixin.sx2libra.model.ImageHost
import com.suixin.sx2libra.model.SiteRoute

enum class MenuSettingsError {
    INVALID_NAME,
    INVALID_PATH,
    DUPLICATE_NAME,
    DUPLICATE_PATH,
    MENU_NOT_FOUND,
    LAST_MENU,
    INVALID_ORDER,
    STORAGE
}

data class PendingDelete(
    val id: String,
    val name: String,
    val requestId: String
)

data class MenuSettingsUiState(
    val menus: List<ForumMenu> = emptyList(),
    val startMenus: List<ForumMenu> = emptyList(),
    val startRevision: Long = 0L,
    val currentRevision: Long = 0L,
    val pendingDelete: PendingDelete? = null,
    val availableRoutes: List<SiteRoute> = emptyList(),
    val selectedImageHost: ImageHost = ImageHost.TIKOLU,
    val error: MenuSettingsError? = null,
    val isLoading: Boolean = true
) {
    val hasChanges: Boolean
        get() = menus != startMenus
}
