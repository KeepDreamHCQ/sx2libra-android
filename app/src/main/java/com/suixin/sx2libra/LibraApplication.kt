package com.suixin.sx2libra

import android.app.Application
import com.tencent.mmkv.MMKV
import com.suixin.sx2libra.core.AppContainer
import com.suixin.sx2libra.data.repository.WebSessionRepositoryContract
import com.suixin.sx2libra.data.repository.WebSessionRepositoryOwner
import com.suixin.sx2libra.web.MediaNativeActionControllerFactory
import com.suixin.sx2libra.web.NativeActionControllerRegistry

class LibraApplication : Application(), WebSessionRepositoryOwner {
    lateinit var appContainer: AppContainer
        private set

    override val webSessionRepository: WebSessionRepositoryContract
        get() = appContainer.webSessionRepository

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        appContainer = AppContainer(applicationContext)
        NativeActionControllerRegistry.factory = MediaNativeActionControllerFactory(
            appContainer.imageHostRepository,
        )
    }
}
