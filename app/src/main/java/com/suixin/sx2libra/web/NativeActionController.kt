package com.suixin.sx2libra.web

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import com.suixin.sx2libra.R
import com.suixin.sx2libra.data.repository.UserAvatarStore
import com.suixin.sx2libra.data.repository.UserNameStore
import com.suixin.sx2libra.data.repository.UnreadMessageStore
import com.suixin.sx2libra.model.ImageUploadEvent
import com.suixin.sx2libra.model.MediaUrlPolicy
import com.suixin.sx2libra.ui.media.MediaPreviewActivity
import com.suixin.sx2libra.ui.media.VideoPlayerActivity
import com.suixin.sx2libra.ui.web.WebPageViewModel
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Page role used to keep the upload action scoped to post content editors. */
enum class NativeActionPage {
    ORDINARY,
    POST_COMPOSER,
    POST_DETAIL,
}

/**
 * View/platform owner for the complete page bridge.
 *
 * A controller owns the WebView-adjacent objects (reply proxies, picker
 * callbacks and upload cancellation). ViewModels and repositories never see
 * those objects. The media worker can install its implementation through
 * [NativeActionControllerRegistry] without changing page Activities.
 */
interface NativeActionController {
    /** Installs native touch tracking; it never changes WebView navigation. */
    fun bind(webView: WebView)

    /** Full bridge listener for navigation, media, sharing and upload actions. */
    fun messageListener(): LibraWebMessageListener

    /** Chrome listener including the single-image compatibility chooser. */
    fun chromeListener(progressDelegate: LibraWebChromeClientListener? = null): LibraWebChromeClientListener

    /** Records a gesture observed by a native View, never a payload field. */
    fun recordTrustedGesture(eventTimeMillis: Long = SystemClock.uptimeMillis())

    /** Closes page channels and cancels page-owned work. Idempotent. */
    fun onDestroy()
}

/** Factory seam for the media/upload worker and application bootstrap. */
fun interface NativeActionControllerFactory {
    fun create(
        activity: Activity,
        page: NativeActionPage,
        viewModel: WebPageViewModel,
        routePolicy: RoutePolicy,
    ): NativeActionController
}

/**
 * Process-local injection point. The default keeps the app functional before
 * PictureSelector/upload DI is installed; media workers may replace the
 * factory once with a controller that supplies their picker and repositories.
 */
object NativeActionControllerRegistry {
    @Volatile
    var factory: NativeActionControllerFactory = NativeActionControllerFactory {
            activity,
            page,
            viewModel,
            routePolicy,
        ->
        DefaultNativeActionController(
            activity = activity,
            page = page,
            viewModel = viewModel,
            routePolicy = routePolicy,
        )
    }

    fun create(
        activity: Activity,
        page: NativeActionPage,
        viewModel: WebPageViewModel,
        routePolicy: RoutePolicy,
    ): NativeActionController = factory.create(activity, page, viewModel, routePolicy)
}

/**
 * Narrow page upload boundary implemented by the media worker.
 *
 * Events are value-only and are delivered through a page-scoped WebMessage
 * channel. Provider selection and upload authorization stay native.
 */
interface PageUploadController {
    fun start(
        requestId: String,
        onEvent: (ImageUploadEvent) -> Unit,
    ): Boolean

    fun cancel()
}

/** Single-file compatibility boundary for WebChromeClient. */
interface SingleImageFileChooser {
    fun show(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean

    fun cancel()
}

/** Explicitly rejects the optional compatibility path until a picker is wired. */
object RejectingSingleImageFileChooser : SingleImageFileChooser {
    override fun show(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = false

    override fun cancel() = Unit
}

/**
 * Adapter for the media worker's existing [PostImagePicker] boundary. The
 * WebChrome fallback is intentionally single-image and never returns a local
 * path or a non-content URI to the page.
 */
class PostImageFileChooser(
    private val picker: PostImagePicker,
) : SingleImageFileChooser {
    override fun show(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        if (!acceptsImage(params)) return false
        return runCatching {
            picker.launch(ImagePickerMode.SINGLE) { result ->
                val uri = (result as? ImagePickerResult.Selected)
                    ?.images
                    ?.singleOrNull()
                    ?.contentUri
                    ?.let(Uri::parse)
                    ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                callback.onReceiveValue(uri?.let { arrayOf(it) })
            }
            true
        }.getOrDefault(false)
    }

    override fun cancel() = picker.cancel()

    private fun acceptsImage(params: WebChromeClient.FileChooserParams): Boolean {
        val accepted = params.acceptTypes
            .asSequence()
            .flatMap { it.split(',').asSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        // Empty accept is retained for legacy image inputs whose HTML omitted
        // the attribute; a non-image MIME/wildcard never reaches the picker.
        return accepted.isEmpty() || accepted.any { type ->
            type.equals("image/*", ignoreCase = true) ||
                type.startsWith("image/", ignoreCase = true) ||
                type.equals(".jpg", ignoreCase = true) ||
                type.equals(".jpeg", ignoreCase = true) ||
                type.equals(".png", ignoreCase = true) ||
                type.equals(".gif", ignoreCase = true) ||
                type.equals(".webp", ignoreCase = true)
        }
    }
}

/** Explicitly rejects uploads when no media worker has been installed. */
object RejectingPageUploadController : PageUploadController {
    override fun start(
        requestId: String,
        onEvent: (ImageUploadEvent) -> Unit,
    ): Boolean = false

    override fun cancel() = Unit
}

/**
 * The default controller wires every non-upload action to existing native
 * screens and leaves picker/upload providers injectable. It is also the
 * reference implementation for the media worker's controller contract.
 */
class DefaultNativeActionController(
    private val activity: Activity,
    private val page: NativeActionPage,
    private val viewModel: WebPageViewModel,
    private val routePolicy: RoutePolicy = RoutePolicy(),
    private val uploadController: PageUploadController = RejectingPageUploadController,
    fileChooser: SingleImageFileChooser = RejectingSingleImageFileChooser,
) : NativeActionController {
    private val active = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trustedGesture = TrustedGestureWindow()
    private val chooser = PageFileChooserGate(fileChooser)
    private val channels = Collections.synchronizedMap(
        LinkedHashMap<String, PageReplyChannel>(),
    )
    private var activeUploadRequestId: String? = null
    private var boundWebView: WebView? = null

    private fun acceptsImageUpload(): Boolean =
        page == NativeActionPage.POST_COMPOSER || page == NativeActionPage.POST_DETAIL

    private val delegate = object : NativeActionDelegate, RetryableImageUploadActionDelegate,
        UserAvatarActionDelegate, UserNameActionDelegate, UnreadMessageActionDelegate {
        override fun openPage(requestId: String, url: String): Boolean {
            if (!active.get() || !trustedGesture.peek()) return false
            viewModel.onNavigationRequested(url, isRedirect = false, hasUserGesture = true)
            return true
        }

        override fun openPost(requestId: String, url: String): Boolean {
            if (!active.get() || !trustedGesture.peek()) return false
            viewModel.onNavigationRequested(url, isRedirect = false, hasUserGesture = true)
            return true
        }

        override fun sharePost(requestId: String, url: String, title: String?): Boolean {
            if (!active.get() || !routePolicy.isPostDetailUrl(url)) return false
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, url)
                title?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    putExtra(android.content.Intent.EXTRA_TITLE, it)
                }
            }
            return runCatching {
                activity.startActivity(android.content.Intent.createChooser(share, null))
                true
            }.getOrDefault(false)
        }

        override fun openExternal(requestId: String, url: String): Boolean {
            if (!active.get() || !trustedGesture.peek()) return false
            viewModel.onNavigationRequested(url, isRedirect = false, hasUserGesture = true)
            return true
        }

        override fun updateUserAvatar(requestId: String, url: String): Boolean {
            if (!active.get()) return false
            UserAvatarStore.update(url)
            return true
        }

        override fun updateUserName(requestId: String, username: String): Boolean {
            return active.get() && UserNameStore.update(username)
        }

        override fun updateUnreadMessageCount(requestId: String, count: Int): Boolean {
            if (!active.get()) return false
            UnreadMessageStore.update(count)
            return true
        }

        override fun previewImages(requestId: String, urls: List<String>, initialIndex: Int): Boolean {
            if (!active.get() || urls.isEmpty() || initialIndex !in urls.indices ||
                urls.any { !MediaUrlPolicy.isAllowedImageUrl(it) }
            ) return false
            return runCatching {
                activity.startActivity(
                    android.content.Intent(activity, MediaPreviewActivity::class.java).apply {
                        putStringArrayListExtra(MediaPreviewActivity.EXTRA_URLS, ArrayList(urls))
                        putExtra(MediaPreviewActivity.EXTRA_INITIAL_INDEX, initialIndex)
                    },
                )
                true
            }.getOrDefault(false)
        }

        override fun playVideo(
            requestId: String,
            url: String,
            mimeType: String,
            title: String?,
            posterUrl: String?,
            previewVttUrl: String?,
        ): Boolean {
            if (!active.get() || !MediaUrlPolicy.isAllowedVideoMime(mimeType) ||
                !MediaUrlPolicy.isAllowedVideoUrl(url, mimeType) ||
                (posterUrl != null && !MediaUrlPolicy.isAllowedPosterUrl(posterUrl)) ||
                (previewVttUrl != null && !MediaUrlPolicy.isAllowedVttUrl(previewVttUrl))
            ) return false
            return runCatching {
                activity.startActivity(
                    android.content.Intent(activity, VideoPlayerActivity::class.java).apply {
                        putExtra(VideoPlayerActivity.EXTRA_URL, url)
                        putExtra(VideoPlayerActivity.EXTRA_MIME_TYPE, mimeType)
                        title?.let { putExtra(VideoPlayerActivity.EXTRA_TITLE, it) }
                        posterUrl?.let { putExtra(VideoPlayerActivity.EXTRA_POSTER_URL, it) }
                        previewVttUrl?.let {
                            putExtra(VideoPlayerActivity.EXTRA_PREVIEW_VTT_URL, it)
                        }
                    },
                )
                true
            }.getOrDefault(false)
        }

        override fun pickAndUploadImages(requestId: String): Boolean {
            if (!active.get() || !acceptsImageUpload()) return false
            // BridgeProtocol only marks the source as trusted after the
            // controller's native touch window passes. Consume it here so the
            // same native tap cannot start a second batch.
            if (!trustedGesture.consume()) return false
            synchronized(this@DefaultNativeActionController) {
                if (activeUploadRequestId != null) return false
                activeUploadRequestId = requestId
            }
            val started = runCatching {
                uploadController.start(requestId) { event ->
                    emitUploadEvent(event)
                }
            }.getOrDefault(false)
            if (!started) {
                synchronized(this@DefaultNativeActionController) {
                    if (activeUploadRequestId == requestId) activeUploadRequestId = null
                }
            }
            return started
        }

        override fun retryImageUpload(requestId: String, clientId: String): Boolean {
            if (!active.get() || !acceptsImageUpload()) return false
            if (!trustedGesture.consume()) return false
            val retryable = uploadController as? RetryablePageUploadController ?: return false
            synchronized(this@DefaultNativeActionController) {
                if (activeUploadRequestId != null && activeUploadRequestId != requestId) return false
                activeUploadRequestId = requestId
            }
            val accepted = runCatching {
                retryable.retry(requestId, clientId)
            }.getOrDefault(false)
            if (!accepted) {
                synchronized(this@DefaultNativeActionController) {
                    if (activeUploadRequestId == requestId) activeUploadRequestId = null
                }
            }
            return accepted
        }
    }

    private val router = NativeActionRouter(
        navigation = delegate,
        media = delegate,
        uploads = delegate,
        userAvatar = delegate,
        userName = delegate,
        requireUserGestureForNavigation = true,
        unreadMessages = delegate,
    )

    override fun bind(webView: WebView) {
        if (!active.get()) return
        boundWebView = webView
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                recordTrustedGesture(event.eventTime)
            }
            false
        }
    }

    override fun messageListener(): LibraWebMessageListener = LibraWebMessageListener {
            view,
            message,
            sourceOrigin,
            isMainFrame,
            replyProxy,
        ->
        if (!active.get()) {
            return@LibraWebMessageListener
        }
        val source = BridgeSource(
            sourceOrigin = sourceOrigin.toString(),
            isMainFrame = isMainFrame,
            currentUrl = view.url.orEmpty(),
            // This comes only from a native touch window. Payload fields are
            // never consulted for gesture authorization.
            hasUserGesture = trustedGesture.peek(),
        )
        val parsed = BridgeProtocol.parse(message, source)
        val acceptedUpload = (parsed as? BridgeParseResult.Accepted)
            ?.request
            ?.takeIf {
                it.action == NativeAction.PICK_AND_UPLOAD_IMAGES ||
                    it.action == NativeAction.RETRY_IMAGE_UPLOAD
            }
        val channel = PageReplyChannel(replyProxy)
        if (acceptedUpload != null) channels[acceptedUpload.requestId] = channel

        val reply = router.route(message, source)
        runCatching { channel.post(reply.toJson()) }
        if (acceptedUpload == null || !reply.ok) {
            channel.close()
        }
    }

    override fun chromeListener(progressDelegate: LibraWebChromeClientListener?): LibraWebChromeClientListener =
        object : LibraWebChromeClientListener {
            override fun onProgressChanged(progress: Int) {
                progressDelegate?.onProgressChanged(progress)
            }

            override fun onReceivedTitle(title: String?) {
                progressDelegate?.onReceivedTitle(title)
            }

            override fun onShowFileChooser(
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean = chooser.show(callback, params)
        }

    override fun recordTrustedGesture(eventTimeMillis: Long) {
        if (active.get()) trustedGesture.record(eventTimeMillis)
    }

    override fun onDestroy() {
        if (!active.compareAndSet(true, false)) return
        boundWebView?.setOnTouchListener(null)
        boundWebView = null
        chooser.cancel()
        runCatching { uploadController.cancel() }
        synchronized(this) {
            activeUploadRequestId = null
            channels.values.toList().forEach(PageReplyChannel::close)
            channels.clear()
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun emitUploadEvent(event: ImageUploadEvent) {
        if (!active.get()) return
        val requestId = event.requestId
        mainHandler.post {
            if (!active.get()) return@post
            val channel = channels[requestId] ?: return@post
            when (event) {
                is ImageUploadEvent.Completed ->
                    Toast.makeText(activity, R.string.image_upload_succeeded, Toast.LENGTH_SHORT).show()
                is ImageUploadEvent.Failed ->
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.image_upload_failed, event.error.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                else -> Unit
            }
            channel.post(BridgeEventEncoder.encode(event))
            if (event is ImageUploadEvent.BatchFinished || event is ImageUploadEvent.BatchCancelled) {
                synchronized(this@DefaultNativeActionController) {
                    if (activeUploadRequestId == requestId) activeUploadRequestId = null
                }
                channel.close()
                channels.remove(requestId)
            }
        }
    }
}

/** Monotonic native gesture window; payload.userGesture is intentionally absent. */
class TrustedGestureWindow(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private var lastDownAt: Long = Long.MIN_VALUE

    init {
        require(windowMillis > 0L)
    }

    @Synchronized
    fun record(eventTimeMillis: Long = SystemClock.uptimeMillis()) {
        lastDownAt = if (eventTimeMillis > 0L) eventTimeMillis else SystemClock.uptimeMillis()
    }

    @Synchronized
    fun peek(nowMillis: Long = SystemClock.uptimeMillis()): Boolean =
        isWithinWindow(nowMillis)

    @Synchronized
    fun consume(nowMillis: Long = SystemClock.uptimeMillis()): Boolean {
        if (!isWithinWindow(nowMillis)) return false
        lastDownAt = Long.MIN_VALUE
        return true
    }

    @Synchronized
    fun clear() {
        lastDownAt = Long.MIN_VALUE
    }

    private fun isWithinWindow(nowMillis: Long): Boolean {
        val elapsed = nowMillis - lastDownAt
        return lastDownAt != Long.MIN_VALUE && elapsed in 0..windowMillis
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS: Long = 1_000L
    }
}

/** One-shot wrapper around a picker callback; stale picker callbacks are ignored. */
private class PageFileChooserGate(
    private val delegate: SingleImageFileChooser,
) : LibraWebChromeClientListener {
    private val lock = Any()
    private var generation = 0L
    private var callback: ValueCallback<Array<Uri>>? = null

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean = show(callback, params)

    fun show(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        cancel()
        val token: Long
        synchronized(lock) {
            token = ++generation
            this.callback = callback
        }
        val guarded = ValueCallback<Array<Uri>> { values ->
            val singleContentUri = values
                ?.takeIf { it.size == 1 }
                ?.firstOrNull()
                ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                ?.let { arrayOf(it) }
            complete(token, singleContentUri)
        }
        val accepted = runCatching { delegate.show(guarded, params) }.getOrDefault(false)
        if (!accepted) complete(token, null)
        // This callback is always handled here; rejection/cancellation has
        // already completed the one-shot callback with null.
        return true
    }

    fun cancel() {
        val target: ValueCallback<Array<Uri>>?
        synchronized(lock) {
            target = callback
            callback = null
            generation++
        }
        runCatching { delegate.cancel() }
        target?.onReceiveValue(null)
    }

    private fun complete(token: Long, value: Array<Uri>?) {
        val target: ValueCallback<Array<Uri>>?
        synchronized(lock) {
            if (token != generation) return
            target = callback
            callback = null
        }
        target?.onReceiveValue(value)
    }
}

/** Reply proxy retained only while a page upload batch is active. */
private class PageReplyChannel(
    proxy: JavaScriptReplyProxy,
) {
    private val lock = Any()
    private var activeProxy: JavaScriptReplyProxy? = proxy

    fun post(message: String) {
        val target = synchronized(lock) { activeProxy } ?: return
        runCatching { target.postMessage(message) }
    }

    fun close() {
        synchronized(lock) { activeProxy = null }
    }
}
