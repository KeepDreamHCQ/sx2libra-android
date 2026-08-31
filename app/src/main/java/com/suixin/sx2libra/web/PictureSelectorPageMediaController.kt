package com.suixin.sx2libra.web

import android.content.ContentResolver
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.fragment.app.FragmentActivity
import com.suixin.sx2libra.data.remote.HttpImageUploadRemoteDataSource
import com.suixin.sx2libra.data.repository.ImageBodyProvider
import com.suixin.sx2libra.data.repository.ImageUploadRepository
import com.suixin.sx2libra.model.ImageMimeTypes
import com.suixin.sx2libra.model.ImageUploadEvent
import com.suixin.sx2libra.model.ImageUploadLimits
import com.suixin.sx2libra.model.SelectedImage
import com.suixin.sx2libra.model.UploadTicket
import java.io.InputStream

/**
 * Page-scoped PictureSelector/uCrop/upload implementation for the bridge
 * interfaces owned by [DefaultNativeActionController].  No Activity, WebView
 * or reply proxy crosses into [ImageUploadRepository].
 */
interface RetryablePageUploadController {
    fun retry(requestId: String, clientId: String): Boolean
}

class PictureSelectorPageUploadController(
    private val activity: FragmentActivity,
    private val picker: PostImagePicker = PictureSelectorPostImagePicker(
        gateway = PictureSelectorV3112Gateway(
            activity,
            "${activity.packageName}.fileprovider",
        ),
        resolver = activity.contentResolver,
    ),
    private val resolver: ContentResolver = activity.contentResolver,
    private val repository: ImageUploadRepository = ImageUploadRepository(
        remote = HttpImageUploadRemoteDataSource(),
        bodyProvider = ImageBodyProvider { image ->
            openImageBody(resolver, image)
        },
    ),
) : PageUploadController, RetryablePageUploadController {
    private val lock = Any()
    private var active = true
    private var pendingPickerRequestId: String? = null
    private var session: UploadSession? = null

    private class UploadSession(val requestId: String) {
        var open = true
        var batch: com.suixin.sx2libra.data.repository.UploadBatchHandle? = null
    }

    override fun start(
        requestId: String,
        uploadTicket: String,
        onEvent: (ImageUploadEvent) -> Unit,
    ): Boolean {
        val accepted = synchronized(lock) {
            if (!active || pendingPickerRequestId != null || session?.open == true || requestId.isBlank()) {
                return@synchronized false
            }
            pendingPickerRequestId = requestId
            true
        }
        if (!accepted) return false

        val ticket = UploadTicket(
            opaqueValue = uploadTicket,
            expiresAtEpochMillis = System.currentTimeMillis() + UPLOAD_TICKET_TTL_MILLIS,
            maxBytesPerFile = ImageUploadLimits.MAX_PICKER_FILE_BYTES,
            maxFiles = ImageUploadLimits.MAX_FILES,
            allowedMimeTypes = ImageMimeTypes.allowed,
        )
        picker.launch(ImagePickerMode.MULTIPLE) { result ->
            when (result) {
                is ImagePickerResult.Selected -> startBatch(
                    requestId,
                    ticket,
                    result.images,
                    onEvent,
                )
                ImagePickerResult.Cancelled,
                is ImagePickerResult.Failed,
                -> finishPickerWithoutBatch(requestId, onEvent)
            }
        }
        return true
    }

    /** Native retry surface; the bridge only receives value-only events. */
    fun retry(clientId: String): Boolean {
        val requestId = synchronized(lock) { session?.requestId } ?: return false
        return retry(requestId, clientId)
    }

    override fun retry(requestId: String, clientId: String): Boolean {
        val target = synchronized(lock) {
            val current = session
            if (!active || pendingPickerRequestId != null || current == null ||
                current.requestId != requestId || current.open
            ) {
                return@synchronized null
            }
            current.open = true
            current.batch
        } ?: return false
        val accepted = runCatching { target.retry(clientId) }.getOrDefault(false)
        if (!accepted) synchronized(lock) {
            session?.takeIf { it.requestId == requestId }?.open = false
        }
        return accepted
    }

    override fun cancel() {
        val shouldCancel = synchronized(lock) {
            if (!active) return@synchronized false
            active = false
            pendingPickerRequestId = null
            true
        }
        if (!shouldCancel) return
        runCatching { picker.cancel() }
        synchronized(lock) { session?.batch }?.cancel()
        repository.shutdown()
    }

    /** Releases page-owned repository threads; safe to call repeatedly. */
    fun close() {
        cancel()
        repository.shutdown()
    }

    private fun startBatch(
        requestId: String,
        ticket: UploadTicket,
        images: List<SelectedImage>,
        onEvent: (ImageUploadEvent) -> Unit,
    ) {
        val canStart = synchronized(lock) {
            active && pendingPickerRequestId == requestId
        }
        if (!canStart) return
        val uploadSession = synchronized(lock) {
            if (!active || pendingPickerRequestId != requestId) return@synchronized null
            pendingPickerRequestId = null
            UploadSession(requestId).also { session = it }
        } ?: return
        val created = runCatching {
            repository.startBatch(requestId, ticket, images) { event ->
                onEvent(event)
                if (event is ImageUploadEvent.BatchFinished ||
                    event is ImageUploadEvent.BatchCancelled
                ) {
                    synchronized(lock) {
                        if (session === uploadSession) {
                            uploadSession.open = false
                        }
                    }
                }
            }
        }.getOrNull()
        if (created == null) {
            synchronized(lock) {
                if (session === uploadSession) session = null
            }
            onEvent(ImageUploadEvent.BatchCancelled(requestId))
            return
        }
        synchronized(lock) {
            if (active && session === uploadSession) {
                uploadSession.batch = created
            } else {
                created.cancel()
            }
        }
    }

    private fun finishPickerWithoutBatch(
        requestId: String,
        onEvent: (ImageUploadEvent) -> Unit,
    ) {
        val shouldFinish = synchronized(lock) {
            if (pendingPickerRequestId != requestId) return@synchronized false
            pendingPickerRequestId = null
            true
        }
        if (shouldFinish) onEvent(ImageUploadEvent.BatchCancelled(requestId))
    }

    private companion object {
        const val UPLOAD_TICKET_TTL_MILLIS = 5L * 60L * 1_000L

        fun openImageBody(resolver: ContentResolver, image: SelectedImage): InputStream {
            val uri = runCatching { Uri.parse(image.contentUri) }.getOrNull()
                ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                ?: throw SecurityException("Image URI is not a content URI")
            return resolver.openInputStream(uri)
                ?: throw SecurityException("Image URI cannot be opened")
        }
    }
}

/**
 * Adapter for WebChromeClient's legacy single-file callback.  It uses the
 * same PictureSelector session as the batch controller and completes a stale
 * or duplicate callback at most once.
 */
class PictureSelectorSingleImageFileChooser(
    private val picker: PostImagePicker,
    private val resolver: ContentResolver,
) : SingleImageFileChooser {
    private val lock = Any()
    private var generation = 0L
    private var callback: ValueCallback<Array<Uri>>? = null

    override fun show(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        cancel()
        if (!acceptsImage(params.acceptTypes)) return false
        val token = synchronized(lock) {
            ++generation
            this.callback = callback
            generation
        }
        picker.launch(ImagePickerMode.SINGLE) { result ->
            val value = when (result) {
                is ImagePickerResult.Selected -> result.images.firstOrNull()
                    ?.let(::contentUri)
                    ?.let { arrayOf(it) }
                else -> null
            }
            complete(token, value)
        }
        return true
    }

    override fun cancel() {
        val target = synchronized(lock) {
            ++generation
            val old = callback
            callback = null
            old
        }
        runCatching { picker.cancel() }
        target?.onReceiveValue(null)
    }

    private fun complete(token: Long, value: Array<Uri>?) {
        val target = synchronized(lock) {
            if (token != generation) return
            val old = callback
            callback = null
            old
        }
        target?.onReceiveValue(value)
    }

    private fun contentUri(image: SelectedImage): Uri? {
        val uri = runCatching { Uri.parse(image.contentUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority == null) return null
        val mime = ImageMimeTypes.normalize(resolver.getType(uri)) ?: return null
        return uri.takeIf { mime in ImageMimeTypes.allowed }
    }

    private fun acceptsImage(types: Array<String>?): Boolean {
        val declared = types.orEmpty().map { it.trim().lowercase() }.filter(String::isNotEmpty)
        if (declared.isEmpty()) return true
        return declared.any { it == "image/*" || it.startsWith("image/") }
    }
}

/**
 * Factory to install from the host's composition root without changing any
 * Web Activity.  The host should assign this factory to
 * [NativeActionControllerRegistry.factory] once during Application startup.
 */
class MediaNativeActionControllerFactory : NativeActionControllerFactory {
    override fun create(
        activity: android.app.Activity,
        page: NativeActionPage,
        viewModel: com.suixin.sx2libra.ui.web.WebPageViewModel,
        routePolicy: RoutePolicy,
    ): NativeActionController {
        val fragmentActivity = activity as? FragmentActivity
            ?: return DefaultNativeActionController(activity, page, viewModel, routePolicy)
        val gateway = PictureSelectorV3112Gateway(
            fragmentActivity,
            "${activity.packageName}.fileprovider",
        )
        val picker = PictureSelectorPostImagePicker(
            gateway = gateway,
            resolver = activity.contentResolver,
        )
        val upload = PictureSelectorPageUploadController(
            activity = fragmentActivity,
            picker = picker,
            resolver = activity.contentResolver,
        )
        val chooser = PictureSelectorSingleImageFileChooser(
            picker = picker,
            resolver = activity.contentResolver,
        )
        return DefaultNativeActionController(
            activity = activity,
            page = page,
            viewModel = viewModel,
            routePolicy = routePolicy,
            uploadController = upload,
            fileChooser = chooser,
        )
    }
}
