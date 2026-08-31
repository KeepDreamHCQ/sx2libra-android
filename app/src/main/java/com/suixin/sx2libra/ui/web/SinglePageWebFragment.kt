package com.suixin.sx2libra.ui.web

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.repository.WebSessionRepositoryResolver
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.ProtectedRootTab
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.web.LibraWebViewHost
import com.suixin.sx2libra.web.LibraWebThemeListener
import com.suixin.sx2libra.web.NativeActionController
import com.suixin.sx2libra.web.NativeActionControllerRegistry
import com.suixin.sx2libra.web.NativeActionPage
import com.suixin.sx2libra.web.PageNavigator
import com.suixin.sx2libra.web.RoutePolicy
import com.suixin.sx2libra.ui.theme.ThemeCoordinator
import kotlinx.coroutines.launch

/** WebView host for a protected root page such as notifications or profile. */
class SinglePageWebFragment : Fragment() {
    private val routePolicy = RoutePolicy()

    private val initialUrl: String
        get() = requireArguments().getString(ARG_INITIAL_URL).orEmpty()

    private val pageViewModel: WebPageViewModel by viewModels {
        WebPageViewModel.Factory(
            initialUrl,
            routePolicy,
            WebSessionRepositoryResolver.resolve(requireContext()),
        )
    }

    private var webHost: LibraWebViewHost? = null
    private var themeObservation: ThemeCoordinator.Observation? = null
    private var restoredWebState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredWebState = savedInstanceState?.getBundle(KEY_WEB_STATE)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_single_web_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<ViewGroup>(R.id.single_web_page_host)
        themeObservation = (requireActivity().application as com.suixin.sx2libra.LibraApplication)
            .themeCoordinator
            .createObservation()
        val actionController: NativeActionController = NativeActionControllerRegistry.create(
            activity = requireActivity(),
            page = NativeActionPage.ORDINARY,
            viewModel = pageViewModel,
            routePolicy = routePolicy,
        )
        webHost = LibraWebViewHost(
            context = requireContext(),
            initialUrl = initialUrl,
            routePolicy = routePolicy,
            listener = LibraWebViewHost.listenerFor(pageViewModel),
            themeListener = LibraWebThemeListener { _, theme ->
                themeObservation?.report(theme)
            },
            actionController = actionController,
        ).also { host ->
            container.addView(
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

    override fun onResume() {
        super.onResume()
        themeObservation?.activate()
    }

    override fun onPause() {
        themeObservation?.deactivate()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = Bundle()
        webHost?.saveState(state)
        if (!state.isEmpty) outState.putBundle(KEY_WEB_STATE, state)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        themeObservation?.close()
        themeObservation = null
        webHost?.destroy()
        webHost = null
        super.onDestroyView()
    }

    private fun handleAction(action: WebPageAction) {
        when (action) {
            is WebPageAction.OpenPage -> PageNavigator().navigate(requireContext(), action.route)
            is WebPageAction.OpenExternal -> PageNavigator().navigate(requireContext(), action.url)
            is WebPageAction.SessionExpired -> PageNavigator().navigate(
                requireContext(),
                action.loginUrl,
                protectedTabForInitialUrl(),
            )
            is WebPageAction.Rejected -> Unit
        }
        // Consume the one-shot action after the navigation attempt. Otherwise
        // returning from a child Activity replays the same pending action.
        pageViewModel.onActionHandled(action.id)
    }

    private fun protectedTabForInitialUrl(): ProtectedRootTab? = when (
        RoutePolicy().classify(initialUrl).kind
    ) {
        WebRouteKind.NOTIFICATIONS -> ProtectedRootTab.NOTIFICATIONS
        WebRouteKind.PROFILE -> ProtectedRootTab.PROFILE
        else -> null
    }

    companion object {
        private const val ARG_INITIAL_URL = "single-web.initialUrl"
        private const val KEY_WEB_STATE = "single-web.webViewState"

        fun newInstance(url: String): SinglePageWebFragment = SinglePageWebFragment().apply {
            require(RoutePolicy().isAllowedPageUrl(url)) {
                "SinglePageWebFragment URL must be an allowed 2Libra page"
            }
            arguments = Bundle().apply { putString(ARG_INITIAL_URL, url) }
        }
    }
}
