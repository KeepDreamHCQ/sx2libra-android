package com.suixin.sx2libra.web

import android.net.http.SslError
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebChromeClient.FileChooserParams

/** Optional platform callbacks supplied by the owning Activity/View layer. */
interface LibraWebChromeClientListener {
    fun onProgressChanged(progress: Int) {}

    fun onReceivedTitle(title: String?) {}

    /** Return true if the callback was completed by a concrete file-picker adapter. */
    fun onShowFileChooser(
        callback: ValueCallback<Array<android.net.Uri>>,
        params: FileChooserParams,
    ): Boolean = false
}

/** Shared ChromeClient skeleton; media/upload workers supply narrow delegates. */
class LibraWebChromeClient(
    private val listener: LibraWebChromeClientListener? = null,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        listener?.onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        listener?.onReceivedTitle(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        val handled = listener?.onShowFileChooser(filePathCallback, fileChooserParams) == true
        if (!handled) filePathCallback.onReceiveValue(null)
        return true
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean {
        // No arbitrary child windows.  target=_blank links are routed by the
        // document-start script/URL callback into an external or new Activity.
        return false
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        // The client does not expose camera, microphone or protected resources
        // through WebView.  A future feature must add an explicit allowlist.
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback?.invoke(origin, false, false)
    }
}
