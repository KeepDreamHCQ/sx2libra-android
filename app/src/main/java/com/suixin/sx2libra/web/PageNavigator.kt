package com.suixin.sx2libra.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.ProtectedRootTab
import com.suixin.sx2libra.model.WebRoute
import com.suixin.sx2libra.model.WebRouteKind
import com.suixin.sx2libra.ui.auth.LoginActivity
import com.suixin.sx2libra.ui.post.PostActivity
import com.suixin.sx2libra.ui.web.WebPageActivity

enum class NavigationDestinationKind {
    WEB_PAGE,
    POST,
    LOGIN,
    EXTERNAL,
    REJECTED,
}

data class NavigationDestination(
    val kind: NavigationDestinationKind,
    val url: String,
    val route: WebRoute,
)

sealed interface NavigationResult {
    data class Started(val destination: NavigationDestination) : NavigationResult
    data class Rejected(val reason: String) : NavigationResult
    data class Failed(val destination: NavigationDestination, val reason: String) : NavigationResult
}

/**
 * Converts a policy-approved URL into a fresh Activity or external browser
 * Intent.  It does not reuse or mutate the source WebView.
 */
class PageNavigator(
    private val routePolicy: RoutePolicy = RoutePolicy(),
) {
    fun destinationFor(rawUrl: String?): NavigationDestination? {
        val route = routePolicy.classify(rawUrl)
        val kind = when (route.kind) {
            WebRouteKind.LOGIN -> NavigationDestinationKind.LOGIN
            WebRouteKind.POST_DETAIL -> NavigationDestinationKind.POST
            WebRouteKind.EXTERNAL -> NavigationDestinationKind.EXTERNAL
            WebRouteKind.INVALID -> return null
            else -> NavigationDestinationKind.WEB_PAGE
        }
        return NavigationDestination(kind = kind, url = route.url, route = route)
    }

    fun intentFor(
        context: Context,
        destination: NavigationDestination,
        protectedTab: ProtectedRootTab? = null,
    ): Intent? {
        // Re-run policy validation on the value object immediately before an
        // Intent is created.  This protects callers that deserialize or retain
        // a destination longer than one UI frame.
        val verified = destinationFor(destination.url) ?: return null
        if (verified.kind != destination.kind || verified.url != destination.url) return null

        val intent = when (verified.kind) {
            NavigationDestinationKind.LOGIN -> Intent(context, LoginActivity::class.java).apply {
                putExtra(AuthContract.EXTRA_INITIAL_URL, AuthContract.LOGIN_URL)
                protectedTab?.let { putExtra(AuthContract.EXTRA_PROTECTED_TAB, it.name) }
            }

            NavigationDestinationKind.POST -> Intent(context, PostActivity::class.java).apply {
                putExtra(AuthContract.EXTRA_INITIAL_URL, verified.url)
            }

            NavigationDestinationKind.WEB_PAGE -> Intent(context, WebPageActivity::class.java).apply {
                putExtra(AuthContract.EXTRA_INITIAL_URL, verified.url)
            }

            NavigationDestinationKind.EXTERNAL -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse(verified.url),
            )

            NavigationDestinationKind.REJECTED -> return null
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun intentFor(
        context: Context,
        rawUrl: String?,
        protectedTab: ProtectedRootTab? = null,
    ): Intent? = destinationFor(rawUrl)?.let { intentFor(context, it, protectedTab) }

    fun navigate(
        context: Context,
        rawUrl: String?,
        protectedTab: ProtectedRootTab? = null,
    ): NavigationResult {
        val destination = destinationFor(rawUrl)
            ?: return NavigationResult.Rejected("INVALID_URL")
        val intent = intentFor(context, destination, protectedTab)
            ?: return NavigationResult.Rejected("INVALID_URL")
        return runCatching {
            context.startActivity(intent)
            NavigationResult.Started(destination)
        }.getOrElse { error ->
            NavigationResult.Failed(
                destination = destination,
                reason = error::class.java.simpleName ?: "ACTIVITY_NOT_FOUND",
            )
        }
    }

    fun navigate(
        context: Context,
        route: WebRoute,
        protectedTab: ProtectedRootTab? = null,
    ): NavigationResult = navigate(context, route.url, protectedTab)
}
