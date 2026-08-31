package com.suixin.sx2libra.core

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.suixin.sx2libra.ui.main.MainViewModel
import com.suixin.sx2libra.ui.menu.MenuSettingsViewModel
import com.suixin.sx2libra.ui.posts.PostsViewModel

/** Creates screen ViewModels with SavedStateHandle and repository dependencies. */
class LibraViewModelFactory(
    private val container: AppContainer
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras
    ): T {
        val savedStateHandle = extras.createSavedStateHandle()
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) ->
                MainViewModel(savedStateHandle) as T
            modelClass.isAssignableFrom(PostsViewModel::class.java) ->
                PostsViewModel(container.forumMenuRepository, savedStateHandle) as T
            modelClass.isAssignableFrom(MenuSettingsViewModel::class.java) ->
                MenuSettingsViewModel(
                    container.forumMenuRepository,
                    savedStateHandle,
                    container.imageHostRepository,
                ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }

    /** Convenience overload for JVM tests that do not have a SavedStateRegistry owner. */
    fun <T : ViewModel> create(modelClass: Class<T>, savedStateHandle: SavedStateHandle): T {
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) ->
                MainViewModel(savedStateHandle) as T
            modelClass.isAssignableFrom(PostsViewModel::class.java) ->
                PostsViewModel(container.forumMenuRepository, savedStateHandle) as T
            modelClass.isAssignableFrom(MenuSettingsViewModel::class.java) ->
                MenuSettingsViewModel(
                    container.forumMenuRepository,
                    savedStateHandle,
                    container.imageHostRepository,
                ) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
    }
}
