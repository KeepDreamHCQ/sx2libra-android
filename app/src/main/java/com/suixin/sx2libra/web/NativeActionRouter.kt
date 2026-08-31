package com.suixin.sx2libra.web

/**
 * The host Activity implements this interface and delegates navigation to its
 * existing PageNavigator/RoutePolicy. This module deliberately does not know
 * how an Activity is created.
 */
interface NavigationActionDelegate {
    fun openPage(requestId: String, url: String): Boolean
    fun openPost(requestId: String, url: String): Boolean
    fun sharePost(requestId: String, url: String, title: String?): Boolean
    fun openExternal(requestId: String, url: String): Boolean
}

interface MediaActionDelegate {
    fun previewImages(requestId: String, urls: List<String>, initialIndex: Int): Boolean
    fun playVideo(
        requestId: String,
        url: String,
        mimeType: String,
        title: String?,
        posterUrl: String?,
        previewVttUrl: String?
    ): Boolean
}

interface ImageUploadActionDelegate {
    /** Starts picker/upload UI and returns false if this page cannot accept it. */
    fun pickAndUploadImages(requestId: String): Boolean
}

/** Receives the current user's avatar URL from the authenticated page shell. */
interface UserAvatarActionDelegate {
    fun updateUserAvatar(requestId: String, url: String): Boolean
}

/** Receives the current user's username from the authenticated page shell. */
interface UserNameActionDelegate {
    fun updateUserName(requestId: String, username: String): Boolean
}

/** Receives the current unread-message count from the authenticated page shell. */
interface UnreadMessageActionDelegate {
    fun updateUnreadMessageCount(requestId: String, count: Int): Boolean
}

/** Optional value-only retry boundary for a completed upload batch. */
interface RetryableImageUploadActionDelegate {
    fun retryImageUpload(requestId: String, clientId: String): Boolean
}

/** Optional aggregate delegate for callers that prefer one implementation. */
interface NativeActionDelegate : NavigationActionDelegate, MediaActionDelegate, ImageUploadActionDelegate

/**
 * Converts validated bridge messages into immutable delegate calls. Reply
 * proxies and WebViews belong to the caller; this router retains neither.
 */
class NativeActionRouter(
    private val navigation: NavigationActionDelegate,
    private val media: MediaActionDelegate,
    private val uploads: ImageUploadActionDelegate,
    private val userAvatar: UserAvatarActionDelegate? = null,
    private val userName: UserNameActionDelegate? = null,
    private val requireUserGestureForNavigation: Boolean = false,
    private val unreadMessages: UnreadMessageActionDelegate? = null,
) {
    constructor(delegate: NativeActionDelegate) : this(delegate, delegate, delegate)

    fun route(rawMessage: String?, source: BridgeSource): BridgeReply {
        val parsed = BridgeProtocol.parse(rawMessage, source)
        if (parsed is BridgeParseResult.Rejected) {
            return BridgeReply.failure(safeRequestId(rawMessage), parsed.error)
        }
        val request = (parsed as BridgeParseResult.Accepted).request
        if (requireUserGestureForNavigation &&
            !source.hasUserGesture &&
            request.payload.isNavigationAction()
        ) {
            return BridgeReply.failure(request.requestId, BridgeErrorCode.USER_GESTURE_REQUIRED)
        }
        return try {
            val handled = when (val payload = request.payload) {
                is BridgePayload.OpenPage ->
                    navigation.openPage(request.requestId, payload.url)
                is BridgePayload.OpenPost ->
                    navigation.openPost(request.requestId, payload.url)
                is BridgePayload.PreviewImages ->
                    media.previewImages(request.requestId, payload.urls, payload.initialIndex)
                is BridgePayload.PlayVideo ->
                    media.playVideo(
                        request.requestId,
                        payload.url,
                        payload.mimeType,
                        payload.title,
                        payload.posterUrl,
                        payload.previewVttUrl
                    )
                is BridgePayload.PickAndUploadImages ->
                    uploads.pickAndUploadImages(request.requestId)
                is BridgePayload.RetryImageUpload ->
                    (uploads as? RetryableImageUploadActionDelegate)
                        ?.retryImageUpload(request.requestId, payload.clientId) == true
                is BridgePayload.SharePost ->
                    navigation.sharePost(request.requestId, payload.url, payload.title)
                is BridgePayload.OpenExternal ->
                    navigation.openExternal(request.requestId, payload.url)
                is BridgePayload.UserAvatar ->
                    userAvatar?.updateUserAvatar(request.requestId, payload.url) == true
                is BridgePayload.UserName ->
                    userName?.updateUserName(request.requestId, payload.username) == true
                is BridgePayload.UnreadMessageCount ->
                    unreadMessages?.updateUnreadMessageCount(request.requestId, payload.count) == true
            }
            if (handled) BridgeReply.success(request.requestId) else {
                BridgeReply.failure(request.requestId, BridgeErrorCode.INVALID_PAYLOAD)
            }
        } catch (_: RuntimeException) {
            // Delegate failures are kept opaque and cannot leak page/platform
            // details into the untrusted document.
            BridgeReply.failure(request.requestId, BridgeErrorCode.INVALID_MESSAGE)
        }
    }

    private fun safeRequestId(rawMessage: String?): String {
        if (rawMessage == null || rawMessage.length > BridgeProtocol.MAX_MESSAGE_BYTES) return "invalid-request"
        val candidate = REQUEST_ID_FIELD.find(rawMessage)?.groupValues?.getOrNull(1).orEmpty()
        return if (candidate.matches(CANONICAL_UUID)) candidate else "invalid-request"
    }

    private fun BridgePayload.isNavigationAction(): Boolean = when (this) {
        is BridgePayload.OpenPage,
        is BridgePayload.OpenPost,
        is BridgePayload.OpenExternal -> true
        else -> false
    }

    private companion object {
        val CANONICAL_UUID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
        )
        val REQUEST_ID_FIELD = Regex("\\\"requestId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
