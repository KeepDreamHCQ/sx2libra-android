package com.suixin.sx2libra.core

import android.content.Context
import com.suixin.sx2libra.data.local.ForumMenuLocalDataSource
import com.suixin.sx2libra.data.local.ImageHostLocalDataSource
import com.suixin.sx2libra.data.platform.CookieManagerWebCookieDataSource
import com.suixin.sx2libra.data.repository.DefaultForumMenuRepository
import com.suixin.sx2libra.data.repository.ForumMenuRepository
import com.suixin.sx2libra.data.repository.ImageHostRepository
import com.suixin.sx2libra.data.repository.WebSessionRepository

/**
 * Manual dependency graph for the single app module. It owns application-scoped data
 * components only; Activities, Fragments and WebViews are intentionally absent.
 */
class AppContainer(
    applicationContext: Context
) {
    private val appContext = applicationContext.applicationContext

    private val webCookieDataSource = CookieManagerWebCookieDataSource()
    val webSessionRepository = WebSessionRepository(webCookieDataSource)

    val forumMenuDataSource = ForumMenuLocalDataSource()
    val forumMenuRepository: ForumMenuRepository = DefaultForumMenuRepository(forumMenuDataSource)

    val imageHostRepository = ImageHostRepository(ImageHostLocalDataSource())

    val viewModelFactory: LibraViewModelFactory by lazy {
        LibraViewModelFactory(this)
    }
}
