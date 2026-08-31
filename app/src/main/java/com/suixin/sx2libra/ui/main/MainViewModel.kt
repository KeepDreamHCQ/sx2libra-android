package com.suixin.sx2libra.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val initialSelectedTab = savedStateHandle.get<String>(KEY_SELECTED_TAB)
        ?.let { runCatching { MainRootTab.valueOf(it) }.getOrNull() }
        ?: MainRootTab.POSTS
    private val _uiState = MutableStateFlow(
        MainUiState(selectedTab = initialSelectedTab),
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun onRootTabSelected(target: MainRootTab) {
        savedStateHandle[KEY_SELECTED_TAB] = target.name
        _uiState.value = _uiState.value.copy(selectedTab = target)
    }

    companion object {
        private const val KEY_SELECTED_TAB = "main.selectedTab"
    }
}
