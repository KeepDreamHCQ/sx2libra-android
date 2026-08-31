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
import androidx.core.content.ContextCompat
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.suixin.sx2libra.data.repository.UnreadMessageState
import com.suixin.sx2libra.data.repository.UnreadMessageStore
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
    private lateinit var messagesBadge: BadgeDrawable
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveSystemBars()
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.main_root).applySystemBarInsets(top = true)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.applySystemBarInsets(bottom = true)
        messagesBadge = bottomNavigation.getOrCreateBadge(R.id.nav_messages).apply {
            backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.libra_error)
            clearNumber()
            isVisible = false
        }
        bottomNavigation.setOnItemSelectedListener { item ->
            if (rendering) return@setOnItemSelectedListener true
            MainRootTab.fromItemId(item.itemId)?.let { target ->
                viewModel.onRootTabSelected(target)
                if (target == MainRootTab.MESSAGES) UnreadMessageStore.markRead()
            }
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
                    moveTaskToBack(true)
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
                launch {
                    UnreadMessageStore.state.collect(::renderMessageBadge)
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
        renderMessageBadge(UnreadMessageStore.state.value)
        showRoot(state.selectedTab)
    }

    private fun renderMessageBadge(state: UnreadMessageState) {
        messagesBadge.isVisible = viewModel.uiState.value.selectedTab != MainRootTab.MESSAGES &&
            state.hasUnacknowledgedMessages
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
