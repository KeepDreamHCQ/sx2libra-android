package com.suixin.sx2libra

import androidx.lifecycle.SavedStateHandle
import com.suixin.sx2libra.ui.main.MainRootTab
import com.suixin.sx2libra.ui.main.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {
    @Test
    fun messagesAndProfileTabsSwitchWithoutSessionChecks() {
        val viewModel = MainViewModel(SavedStateHandle())

        viewModel.onRootTabSelected(MainRootTab.MESSAGES)
        assertEquals(MainRootTab.MESSAGES, viewModel.uiState.value.selectedTab)

        viewModel.onRootTabSelected(MainRootTab.PROFILE)
        assertEquals(MainRootTab.PROFILE, viewModel.uiState.value.selectedTab)
    }

    @Test
    fun restoredTabIsShownDirectly() {
        val viewModel = MainViewModel(
            SavedStateHandle(mapOf("main.selectedTab" to MainRootTab.PROFILE.name)),
        )

        assertEquals(MainRootTab.PROFILE, viewModel.uiState.value.selectedTab)
    }
}
