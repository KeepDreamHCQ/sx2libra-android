package com.suixin.sx2libra

import android.app.Application
import android.webkit.WebSettings
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.suixin.sx2libra.data.local.MmkvWebThemeStore
import com.tencent.mmkv.MMKV
import com.suixin.sx2libra.core.AppContainer
import com.suixin.sx2libra.data.repository.WebSessionRepositoryContract
import com.suixin.sx2libra.data.repository.WebSessionRepositoryOwner
import com.suixin.sx2libra.ui.theme.ThemeCoordinator
import com.suixin.sx2libra.web.MediaNativeActionControllerFactory
import com.suixin.sx2libra.web.NativeActionControllerRegistry
import com.suixin.sx2libra.web.WebImageCacheConfig
import com.suixin.sx2libra.web.WebImageResourceInterceptor
import com.suixin.sx2libra.web.WebImageServiceWorkerClient

class LibraApplication : Application(), WebSessionRepositoryOwner {
    lateinit var appContainer: AppContainer
        private set

    lateinit var themeCoordinator: ThemeCoordinator
        private set

    override val webSessionRepository: WebSessionRepositoryContract
        get() = appContainer.webSessionRepository

    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        themeCoordinator = ThemeCoordinator(MmkvWebThemeStore()).also { it.initialize() }
        appContainer = AppContainer(applicationContext)
        installWebImageServiceWorkerClient()
        appContainer.webSnapshotRepository.scheduleCleanup()
        NativeActionControllerRegistry.factory = MediaNativeActionControllerFactory(
            appContainer.imageHostRepository,
        )
    }

    private fun installWebImageServiceWorkerClient() {
        if (!WebImageCacheConfig.ENABLED) return
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) {
            return
        }
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            WebImageServiceWorkerClient(
                WebImageResourceInterceptor(
                    cache = appContainer.webImageCache,
                    fallbackUserAgent = WebSettings.getDefaultUserAgent(this),
                ),
            ),
        )
    }
}
