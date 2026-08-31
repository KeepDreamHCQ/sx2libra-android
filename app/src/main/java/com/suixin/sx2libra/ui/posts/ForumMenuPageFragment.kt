package com.suixin.sx2libra.ui.posts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suixin.sx2libra.R
import com.suixin.sx2libra.model.ForumMenu
import com.suixin.sx2libra.data.repository.WebSessionRepositoryResolver
import com.suixin.sx2libra.ui.web.WebPageAction
import com.suixin.sx2libra.ui.web.WebPageViewModel
import com.suixin.sx2libra.web.LibraWebViewHost
import com.suixin.sx2libra.web.NativeActionController
import com.suixin.sx2libra.web.NativeActionControllerRegistry
import com.suixin.sx2libra.web.NativeActionPage
import com.suixin.sx2libra.web.NavigationResult
import com.suixin.sx2libra.web.PageNavigator
import com.suixin.sx2libra.web.RoutePolicy
import kotlinx.coroutines.launch

/**
 * A menu page host.  Every menu owns one secure WebView whose only initial URL
 * is the validated ForumMenu URL; subsequent main-frame routes become fresh
 * Activity actions handled by PageNavigator.
 */
class ForumMenuPageFragment : Fragment() {
    private val routePolicy = RoutePolicy()

    private val menu: ForumMenu by lazy {
        ForumMenu(
            id = requireArguments().getString(ARG_ID).orEmpty(),
            name = requireArguments().getString(ARG_NAME).orEmpty(),
            path = requireArguments().getString(ARG_PATH).orEmpty()
        )
    }

    private val pageViewModel: WebPageViewModel by viewModels {
        WebPageViewModel.Factory(
            menu.url,
            routePolicy,
            WebSessionRepositoryResolver.resolve(requireContext()),
        )
    }

    private var webHost: LibraWebViewHost? = null
    private var restoredWebState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredWebState = savedInstanceState?.getBundle(KEY_WEB_STATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_forum_menu_page, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hostContainer = view.findViewById<ViewGroup>(R.id.menu_page_web_host)
        val actionController: NativeActionController = NativeActionControllerRegistry.create(
            activity = requireActivity(),
            page = if (routePolicy.isPostComposerUrl(menu.url)) {
                NativeActionPage.POST_COMPOSER
            } else {
                NativeActionPage.ORDINARY
            },
            viewModel = pageViewModel,
            routePolicy = routePolicy,
        )
        webHost = LibraWebViewHost(
            context = requireContext(),
            initialUrl = menu.url,
            routePolicy = routePolicy,
            listener = LibraWebViewHost.listenerFor(pageViewModel),
            actionController = actionController,
        ).also { host ->
            hostContainer.addView(
                host,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            host.start(restoredWebState)
            restoredWebState = null
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                pageViewModel.uiState.collect { state ->
                    state.pendingAction?.let(::handleAction)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val webState = Bundle()
        webHost?.saveState(webState)
        if (!webState.isEmpty) outState.putBundle(KEY_WEB_STATE, webState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        webHost?.destroy()
        webHost = null
        super.onDestroyView()
    }

    private fun handleAction(action: WebPageAction) {
        val result = when (action) {
            is WebPageAction.OpenPage -> PageNavigator().navigate(requireContext(), action.route)
            is WebPageAction.OpenExternal -> PageNavigator().navigate(requireContext(), action.url)
            is WebPageAction.SessionExpired -> PageNavigator().navigate(requireContext(), action.loginUrl)
            is WebPageAction.Rejected -> null
        }
        if (result == null || result is NavigationResult.Started) {
            pageViewModel.onActionHandled(action.id)
        }
    }

    companion object {
        private const val ARG_ID = "menu.id"
        private const val ARG_NAME = "menu.name"
        private const val ARG_PATH = "menu.path"
        private const val KEY_WEB_STATE = "menu.webViewState"

        fun newInstance(menu: ForumMenu): ForumMenuPageFragment =
            ForumMenuPageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, menu.id)
                    putString(ARG_NAME, menu.name)
                    putString(ARG_PATH, menu.path)
                }
            }
    }
}
