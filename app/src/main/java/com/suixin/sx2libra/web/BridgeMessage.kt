package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.MediaUrlPolicy
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID

enum class NativeAction(val wireName: String) {
    OPEN_PAGE("open_page"),
    OPEN_POST("open_post"),
    PREVIEW_IMAGES("preview_images"),
    PLAY_VIDEO("play_video"),
    PICK_AND_UPLOAD_IMAGES("pick_and_upload_images"),
    RETRY_IMAGE_UPLOAD("retry_image_upload"),
    SHARE_POST("share_post"),
    OPEN_EXTERNAL("open_external"),
    USER_AVATAR("user_avatar"),
    USER_NAME("user_name");

    companion object {
        fun fromWireName(value: String?): NativeAction? = entries.firstOrNull { it.wireName == value }
    }
}

enum class BridgeErrorCode {
    INVALID_MESSAGE,
    INVALID_VERSION,
    INVALID_REQUEST_ID,
    INVALID_ORIGIN,
    INVALID_FRAME,
    INVALID_ACTION,
    INVALID_PAYLOAD,
    INVALID_URL,
    PAGE_NOT_ALLOWED,
    USER_GESTURE_REQUIRED
}

/** Typed, immutable payloads produced only after all bridge validation passes. */
sealed class BridgePayload {
    data class OpenPage(val url: String) : BridgePayload()
    data class OpenPost(val url: String) : BridgePayload()
    data class PreviewImages(val urls: List<String>, val initialIndex: Int) : BridgePayload()
    data class PlayVideo(
        val url: String,
        val mimeType: String,
        val title: String?,
        val posterUrl: String?,
        val previewVttUrl: String?
    ) : BridgePayload()

    data object PickAndUploadImages : BridgePayload()
    data class RetryImageUpload(val clientId: String) : BridgePayload()
    data class SharePost(val url: String, val title: String?) : BridgePayload()
    data class OpenExternal(val url: String) : BridgePayload()
    data class UserAvatar(val url: String) : BridgePayload()
    data class UserName(val username: String) : BridgePayload()
}

data class BridgeRequest(
    val version: Int,
    val requestId: String,
    val action: NativeAction,
    val payload: BridgePayload
)

data class BridgeSource(
    val sourceOrigin: String,
    val isMainFrame: Boolean,
    val currentUrl: String,
    val hasUserGesture: Boolean
)

sealed class BridgeParseResult {
    data class Accepted(val request: BridgeRequest) : BridgeParseResult()
    data class Rejected(val error: BridgeErrorCode) : BridgeParseResult()
}

data class BridgeReply(
    val requestId: String,
    val ok: Boolean,
    val error: BridgeErrorCode? = null,
    val payload: Map<String, Any?> = emptyMap()
) {
    fun toJson(): String {
        val root = JSONObject()
            .put("version", BridgeProtocol.VERSION)
            .put("requestId", requestId)
            .put("ok", ok)
        if (ok) root.put("payload", JSONObject(payload))
        else root.put("error", (error ?: BridgeErrorCode.INVALID_MESSAGE).name)
        return root.toString()
    }

    companion object {
        fun success(requestId: String, payload: Map<String, Any?> = emptyMap()) =
            BridgeReply(requestId, ok = true, payload = payload)

        fun failure(requestId: String, error: BridgeErrorCode) =
            BridgeReply(requestId, ok = false, error = error)
    }
}

/**
 * Parser and policy gate for every native action. It does not launch an
 * Activity, read a Cookie, execute JavaScript, or retain a reply proxy.
 */
object BridgeProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 64 * 1024
    const val MAX_PAYLOAD_FIELDS = 12
    const val MAX_REQUEST_ID_LENGTH = 64
    const val MAX_TITLE_LENGTH = 160
    const val MAX_CLIENT_ID_LENGTH = 64

    private const val EXPECTED_ORIGIN = "https://2libra.com"
    private const val SITE_HOST = "2libra.com"

    fun parse(rawMessage: String?, source: BridgeSource): BridgeParseResult {
        if (rawMessage == null || rawMessage.length < 2 ||
            rawMessage.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES
        ) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        }
        if (source.sourceOrigin != EXPECTED_ORIGIN) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_ORIGIN)
        }
        if (!source.isMainFrame) return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_FRAME)
        if (!isAllowedSitePage(source.currentUrl)) {
            return BridgeParseResult.Rejected(BridgeErrorCode.PAGE_NOT_ALLOWED)
        }
        val root = MiniJson.parseObject(rawMessage)
            ?: return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        if (!root.onlyKeys("version", "requestId", "action", "payload")) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_MESSAGE)
        }
        if ((root["version"] as? Long)?.toInt() != VERSION) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_VERSION)
        }
        val requestId = root.stringField("requestId")
            ?: return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_REQUEST_ID)
        if (requestId.length > MAX_REQUEST_ID_LENGTH || !isCanonicalUuid(requestId)) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_REQUEST_ID)
        }
        val action = NativeAction.fromWireName(root.stringField("action"))
            ?: return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_ACTION)
        val payload = root["payload"] as? Map<*, *>
            ?: return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_PAYLOAD)
        @Suppress("UNCHECKED_CAST")
        val typedPayload = payload as Map<String, Any?>
        if (typedPayload.size > MAX_PAYLOAD_FIELDS) {
            return BridgeParseResult.Rejected(BridgeErrorCode.INVALID_PAYLOAD)
        }
        val parsedPayload = parsePayload(
            action = action,
            payload = typedPayload,
            hasUserGesture = source.hasUserGesture,
            currentUrl = source.currentUrl,
        )
            ?: return BridgeParseResult.Rejected(
                if ((action == NativeAction.PICK_AND_UPLOAD_IMAGES ||
                    action == NativeAction.RETRY_IMAGE_UPLOAD) && !source.hasUserGesture
                ) {
                    BridgeErrorCode.USER_GESTURE_REQUIRED
                } else {
                    BridgeErrorCode.INVALID_PAYLOAD
                }
            )
        return BridgeParseResult.Accepted(BridgeRequest(VERSION, requestId, action, parsedPayload))
    }

    private fun parsePayload(
        action: NativeAction,
        payload: Map<String, Any?>,
        hasUserGesture: Boolean,
        currentUrl: String,
    ): BridgePayload? {
        return when (action) {
            NativeAction.OPEN_PAGE -> {
                if (!payload.onlyKeys("url")) return null
                payload.stringField("url")?.takeIf(::isAllowedSitePage)?.let(BridgePayload::OpenPage)
            }
            NativeAction.OPEN_POST -> {
                if (!payload.onlyKeys("url")) return null
                payload.stringField("url")?.takeIf(::isAllowedPostUrl)?.let(BridgePayload::OpenPost)
            }
            NativeAction.PREVIEW_IMAGES -> {
                if (!payload.onlyKeys("urls", "initialIndex")) return null
                parsePreviewImages(payload)
            }
            NativeAction.PLAY_VIDEO -> {
                if (!payload.onlyKeys("url", "mimeType", "title", "posterUrl", "previewVttUrl")) return null
                val url = payload.stringField("url") ?: return null
                val mime = payload.stringField("mimeType")?.trim()?.lowercase(Locale.US) ?: return null
                if (!MediaUrlPolicy.isAllowedVideoUrl(url, mime) || !MediaUrlPolicy.isAllowedVideoMime(mime)) return null
                if (payload.hasInvalidOptionalString("title", MAX_TITLE_LENGTH) ||
                    payload.hasInvalidOptionalString("posterUrl", MediaUrlPolicy.MAX_URL_LENGTH) ||
                    payload.hasInvalidOptionalString("previewVttUrl", MediaUrlPolicy.MAX_URL_LENGTH)
                ) return null
                val title = payload.optionalStringField("title")
                val poster = payload.optionalStringField("posterUrl")
                val vtt = payload.optionalStringField("previewVttUrl")
                if (poster != null && !MediaUrlPolicy.isAllowedPosterUrl(poster)) return null
                if (vtt != null && !MediaUrlPolicy.isAllowedVttUrl(vtt)) return null
                BridgePayload.PlayVideo(url, mime, title?.trim()?.takeIf { it.isNotEmpty() }, poster, vtt)
            }
            NativeAction.PICK_AND_UPLOAD_IMAGES -> {
                if (!hasUserGesture || !isAllowedUploadPage(currentUrl) ||
                    !payload.onlyKeys()
                ) return null
                BridgePayload.PickAndUploadImages
            }
            NativeAction.RETRY_IMAGE_UPLOAD -> {
                if (!hasUserGesture || !isAllowedUploadPage(currentUrl) ||
                    !payload.onlyKeys("clientId")
                ) return null
                payload.stringField("clientId")
                    ?.takeIf { it.length <= MAX_CLIENT_ID_LENGTH && isCanonicalUuid(it) }
                    ?.let(BridgePayload::RetryImageUpload)
            }
            NativeAction.SHARE_POST -> {
                if (!payload.onlyKeys("url", "title")) return null
                val url = payload.stringField("url")?.takeIf(::isAllowedPostUrl) ?: return null
                if (payload.hasInvalidOptionalString("title", MAX_TITLE_LENGTH)) return null
                val title = payload.optionalStringField("title")
                BridgePayload.SharePost(url, title?.trim()?.takeIf { it.isNotEmpty() })
            }
            NativeAction.OPEN_EXTERNAL -> {
                if (!payload.onlyKeys("url")) return null
                payload.stringField("url")?.takeIf(::isAllowedExternalUrl)?.let(BridgePayload::OpenExternal)
            }
            NativeAction.USER_AVATAR -> {
                if (!payload.onlyKeys("url")) return null
                payload.stringField("url")?.takeIf(::isAllowedAvatarUrl)?.let(BridgePayload::UserAvatar)
            }
            NativeAction.USER_NAME -> {
                if (!payload.onlyKeys("username")) return null
                payload.stringField("username")
                    ?.trim()
                    ?.takeIf(AuthContract::isValidUsername)
                    ?.let(BridgePayload::UserName)
            }
        }
    }

    private fun parsePreviewImages(payload: Map<String, Any?>): BridgePayload.PreviewImages? {
        val values = payload["urls"] as? List<*> ?: return null
        if (values.size !in 1..MediaUrlPolicy.MAX_PREVIEW_ITEMS) return null
        val urls = ArrayList<String>(values.size)
        values.forEach { value ->
            if (value !is String || !MediaUrlPolicy.isAllowedImageUrl(value) || value in urls) return null
            urls += value
        }
        val initialIndex = (payload["initialIndex"] as? Long)?.toInt() ?: return null
        if (initialIndex !in urls.indices) return null
        return BridgePayload.PreviewImages(urls, initialIndex)
    }

    private fun isAllowedSitePage(rawUrl: String): Boolean {
        val uri = parseHttps(rawUrl) ?: return false
        if (!uri.host.equals(SITE_HOST, ignoreCase = true)) return false
        return isSafePath(uri.rawPath ?: return false)
    }

    private fun isAllowedPostUrl(rawUrl: String): Boolean {
        val uri = parseHttps(rawUrl) ?: return false
        if (!uri.host.equals(SITE_HOST, ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        if (!isSafePath(path)) return false
        val segments = path.split('/').filter(String::isNotEmpty)
        return segments.size == 3 && segments[0] == "post" &&
            segments[1].length in 1..120 && segments[2].length in 1..200 &&
            segments.none { it == "." || it == ".." || it.contains('\\') }
    }

    private fun isAllowedUploadPage(rawUrl: String): Boolean {
        val uri = parseHttps(rawUrl) ?: return false
        if (!uri.host.equals(SITE_HOST, ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        if (!isSafePath(path)) return false
        val segments = path.split('/').filter(String::isNotEmpty)
        val isPostDetail = segments.size == 3 && segments[0] == "post" &&
            segments[1].length in 1..120 && segments[2].length in 1..200 &&
            segments.none { it == "." || it == ".." || it.contains('\\') }
        return segments == listOf("post", "create") ||
            (segments.size == 4 && segments[0] == "post" && segments[3] == "edit") ||
            isPostDetail
    }

    private fun isAllowedExternalUrl(rawUrl: String): Boolean {
        val uri = parseHttps(rawUrl) ?: return false
        if (uri.host.equals(SITE_HOST, ignoreCase = true) ||
            uri.host.equals(MediaUrlPolicy.MEDIA_HOST, ignoreCase = true)
        ) return false
        return isSafePath(uri.rawPath.orEmpty())
    }

    private fun isAllowedAvatarUrl(rawUrl: String): Boolean {
        val uri = parseHttps(rawUrl) ?: return false
        val hostAllowed = uri.host.equals(SITE_HOST, ignoreCase = true) ||
            uri.host.equals(MediaUrlPolicy.MEDIA_HOST, ignoreCase = true)
        val path = uri.rawPath ?: return false
        return hostAllowed && path.lowercase(Locale.US).contains("/avatars/") && isSafePath(path)
    }

    private fun parseHttps(rawUrl: String): URI? {
        if (rawUrl.length !in 1..MediaUrlPolicy.MAX_URL_LENGTH ||
            rawUrl.any { it.isISOControl() || it.isWhitespace() }
        ) return null
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.fragment != null
        ) return null
        return uri
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isEmpty()) return true
        if (!path.startsWith('/') || path.contains('\\') || path.contains("//")) return false
        if (path == "/") return true
        val decoded = path.lowercase(Locale.US)
            .replace("%2e", ".")
            .replace("%2f", "/")
            .replace("%5c", "\\")
        return !decoded.contains('\\') && decoded.removePrefix("/").split('/').none {
            it.isEmpty() || it == "." || it == ".."
        }
    }

    private fun isCanonicalUuid(value: String): Boolean =
        value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) &&
            runCatching { UUID.fromString(value) }.isSuccess

    private fun Map<String, Any?>.stringField(name: String): String? {
        val value = this[name] as? String ?: return null
        return value.takeIf { it.isNotEmpty() && it.length <= MediaUrlPolicy.MAX_URL_LENGTH }
    }

    private fun Map<String, Any?>.optionalStringField(name: String): String? = this[name] as? String

    private fun Map<String, Any?>.hasInvalidOptionalString(name: String, maxLength: Int): Boolean {
        if (!containsKey(name) || this[name] == null) return false
        val value = this[name] as? String ?: return true
        return value.length > maxLength
    }

    private fun Map<String, Any?>.onlyKeys(vararg allowed: String): Boolean = keys.all { it in allowed }
}

/** Small strict JSON reader so BridgeProtocol remains JVM-testable without Android's mocked org.json. */
private object MiniJson {
    fun parseObject(input: String): Map<String, Any?>? = runCatching {
        val parser = Parser(input)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.atEnd() || value !is Map<*, *>) null
        else @Suppress("UNCHECKED_CAST") (value as Map<String, Any?>)
    }.getOrNull()

    private class Parser(private val input: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= input.length

        fun skipWhitespace() {
            while (!atEnd() && input[index] in charArrayOf(' ', '\t', '\n', '\r')) index++
        }

        fun value(): Any? {
            skipWhitespace()
            if (atEnd()) error("missing value")
            return when (input[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> error("unexpected token")
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = stringValue()
                if (result.containsKey(key)) error("duplicate key")
                skipWhitespace()
                expect(':')
                result[key] = value()
                skipWhitespace()
                when {
                    peek('}') -> {
                        index++
                        return result
                    }
                    peek(',') -> index++
                    else -> error("object separator")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result += value()
                skipWhitespace()
                when {
                    peek(']') -> {
                        index++
                        return result
                    }
                    peek(',') -> index++
                    else -> error("array separator")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (!atEnd()) {
                when (val char = input[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (atEnd()) error("unterminated escape")
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) error("unicode escape")
                                val code = input.substring(index, index + 4).toIntOrNull(16)
                                    ?: error("unicode escape")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> error("invalid escape")
                        }
                    }
                    else -> {
                        if (char.code < 0x20) error("control character")
                        result.append(char)
                    }
                }
            }
            error("unterminated string")
        }

        private fun numberValue(): Number {
            val start = index
            if (peek('-')) index++
            if (atEnd()) error("number")
            if (peek('0')) index++ else {
                if (input[index] !in '1'..'9') error("number")
                while (!atEnd() && input[index] in '0'..'9') index++
            }
            var decimal = false
            if (peek('.')) {
                decimal = true
                index++
                if (atEnd() || input[index] !in '0'..'9') error("number")
                while (!atEnd() && input[index] in '0'..'9') index++
            }
            if (peek('e') || peek('E')) {
                decimal = true
                index++
                if (peek('+') || peek('-')) index++
                if (atEnd() || input[index] !in '0'..'9') error("number")
                while (!atEnd() && input[index] in '0'..'9') index++
            }
            val raw = input.substring(start, index)
            return if (decimal) raw.toDoubleOrNull() ?: error("number") else raw.toLongOrNull() ?: error("number")
        }

        private fun literal(expected: String, value: Any?): Any? {
            if (!input.startsWith(expected, index)) error("literal")
            index += expected.length
            return value
        }

        private fun expect(char: Char) {
            if (atEnd() || input[index] != char) error("expected $char")
            index++
        }

        private fun peek(char: Char): Boolean = !atEnd() && input[index] == char
    }
}
