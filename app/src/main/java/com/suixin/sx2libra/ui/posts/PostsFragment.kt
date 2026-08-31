package com.suixin.sx2libra.ui.posts

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.suixin.sx2libra.LibraApplication
import com.suixin.sx2libra.R
import com.suixin.sx2libra.ui.menu.MenuSettingsActivity
import kotlinx.coroutines.launch

class PostsFragment : Fragment() {
    private val viewModel: PostsViewModel by viewModels {
        (requireActivity().application as LibraApplication).appContainer.viewModelFactory
    }

    private lateinit var pager: ViewPager2
    private lateinit var adapter: ForumMenuPagerAdapter
    private var tabsMediator: TabLayoutMediator? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onMenuSettingsClosed(
                result.data?.getLongExtra(MenuSettingsActivity.EXTRA_REVISION, -1L)
                    ?.takeIf { it >= 0L }
            )
        }
        viewModel.refreshMenus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_posts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pager = view.findViewById(R.id.post_pager)
        adapter = ForumMenuPagerAdapter(this)
        pager.adapter = adapter
        pager.isUserInputEnabled = true
        pager.offscreenPageLimit = 1
        tabsMediator = TabLayoutMediator(
            view.findViewById(R.id.post_tabs),
            pager
        ) { tab, position -> tab.text = adapter.menuAt(position)?.name.orEmpty() }.also { it.attach() }

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.menuAt(position)?.id?.let(viewModel::selectMenu)
            }
        }.also(pager::registerOnPageChangeCallback)
        view.findViewById<View>(R.id.menu_settings).setOnClickListener {
            settingsLauncher.launch(Intent(requireContext(), MenuSettingsActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshMenus()
    }

    override fun onDestroyView() {
        tabsMediator?.detach()
        tabsMediator = null
        pageChangeCallback?.let(pager::unregisterOnPageChangeCallback)
        pageChangeCallback = null
        pager.adapter = null
        super.onDestroyView()
    }

    private fun render(state: PostsUiState) {
        val currentId = adapter.menuAt(pager.currentItem)?.id
        if (adapter.menus() != state.menus) adapter.submitMenus(state.menus)
        if (state.menus.isEmpty()) return
        val targetId = listOfNotNull(state.selectedMenuId, currentId)
            .firstOrNull { id -> state.menus.any { it.id == id } }
            ?: state.menus.first().id
        val targetPosition = state.menus.indexOfFirst { it.id == targetId }
        if (targetPosition >= 0 && pager.currentItem != targetPosition) {
            pager.setCurrentItem(targetPosition, false)
        }
    }

    companion object {
        fun newInstance(): PostsFragment = PostsFragment()
    }
}
