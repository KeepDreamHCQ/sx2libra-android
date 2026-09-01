package com.suixin.sx2libra.web

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat

/** Routes Service Worker resource requests through the same local image cache as WebViewClient. */
class WebImageServiceWorkerClient(
    private val imageResourceInterceptor: WebImageResourceInterceptor,
) : ServiceWorkerClientCompat() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        imageResourceInterceptor.intercept(request)
}
