package com.suixin.sx2libra

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.suixin.sx2libra.data.repository.UserNameStore
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.ProtectedRootTab
import com.suixin.sx2libra.ui.main.MainRootTab
import com.suixin.sx2libra.ui.main.MainViewModel
import com.suixin.sx2libra.ui.posts.PostsFragment
import com.suixin.sx2libra.ui.web.SinglePageWebFragment
import com.suixin.sx2libra.ui.system.applySystemBarInsets
import com.suixin.sx2libra.ui.system.enableImmersiveSystemBars
import kotlinx.coroutines.launch

/** Root shell: bottom navigation is click-only; only PostsFragment owns a pager. */
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels {
        (application as LibraApplication).appContainer.viewModelFactory
    }

    private lateinit var bottomNavigation: BottomNavigationView
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars()
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_root).applySystemBarInsets(top = true)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.applySystemBarInsets(bottom = true)
        bottomNavigation.setOnItemSelectedListener { item ->
            if (rendering) return@setOnItemSelectedListener true
            MainRootTab.fromItemId(item.itemId)?.let(viewModel::onRootTabSelected)
            true
        }
        bottomNavigation.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_posts) viewModel.onRootTabSelected(MainRootTab.POSTS)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.uiState.value.selectedTab != MainRootTab.POSTS) {
                    viewModel.onRootTabSelected(MainRootTab.POSTS)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect(::render)
                }
                launch {
                    UserNameStore.username.collect {
                        if (viewModel.uiState.value.selectedTab == MainRootTab.PROFILE) {
                            render(viewModel.uiState.value)
                        }
                    }
                }
            }
        }
    }

    private fun render(state: com.suixin.sx2libra.ui.main.MainUiState) {
        val checkedId = state.selectedTab.itemId
        if (bottomNavigation.selectedItemId != checkedId) {
            rendering = true
            bottomNavigation.selectedItemId = checkedId
            rendering = false
        }
        if (state.selectedTab == MainRootTab.PROFILE && UserNameStore.username.value == null) {
            return
        }
        showRoot(state.selectedTab)
    }

    private fun showRoot(tab: MainRootTab) {
        val tag = tab.name
        val manager = supportFragmentManager
        if (manager.isStateSaved) return
        val current = manager.findFragmentByTag(tag)
        manager.commitNow {
            manager.fragments.filter { it.id == R.id.root_container }.forEach { hide(it) }
            if (current == null) add(R.id.root_container, fragmentFor(tab), tag)
            else show(current)
        }
    }

    private fun fragmentFor(tab: MainRootTab): Fragment = when (tab) {
        MainRootTab.POSTS -> PostsFragment.newInstance()
        MainRootTab.MESSAGES -> SinglePageWebFragment.newInstance(
            AuthContract.urlFor(ProtectedRootTab.NOTIFICATIONS),
        )
        MainRootTab.PROFILE -> SinglePageWebFragment.newInstance(
            AuthContract.profileUrl(requireNotNull(UserNameStore.username.value)),
        )
    }
}
