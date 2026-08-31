package com.suixin.sx2libra.web

import com.suixin.sx2libra.model.AuthContract
import com.suixin.sx2libra.model.WebRoute
import com.suixin.sx2libra.model.WebRouteKind
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Classifies and normalizes every URL that can become a top-level page.
 *
 * This class deliberately does not use startsWith() on the complete URL.  The
 * URI scheme, host, authority and path are checked independently so values such
 * as https://2libra.com.evil.example cannot enter the authenticated WebView.
 */
class RoutePolicy(
    val siteHost: String = DEFAULT_SITE_HOST,
    val mediaHost: String = DEFAULT_MEDIA_HOST,
) {
    init {
        require(siteHost.isNotBlank() && siteHost == siteHost.lowercase(Locale.US)) {
            "siteHost must be a lowercase DNS host"
        }
        require(mediaHost.isNotBlank() && mediaHost == mediaHost.lowercase(Locale.US)) {
            "mediaHost must be a lowercase DNS host"
        }
    }

    /** Main entry point used by WebViewClient, Bridge and PageNavigator. */
    fun classify(rawUrl: String?): WebRoute {
        if (rawUrl == null) return WebRoute.invalid("NULL_URL")
        val parsed = parse(rawUrl) ?: return WebRoute.invalid("INVALID_URL")
        val uri = parsed.uri
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return WebRoute.invalid("MISSING_SCHEME")

        if (scheme != HTTPS_SCHEME) {
            return WebRoute.invalid("UNSUPPORTED_SCHEME")
        }

        val host = parsed.host
        if (host != siteHost) {
            // External pages are intentionally never valid in the in-app
            // authenticated WebView, but PageNavigator may open HTTPS URLs in
            // a browser/custom tab after this classification.
            return WebRoute(
                kind = WebRouteKind.EXTERNAL,
                url = parsed.externalUrl,
            )
        }

        val path = parsed.path
        val canonical = parsed.siteUrl
        val segments = parsed.decodedSegments
        return when {
            path == "/" -> WebRoute(WebRouteKind.HOME, canonical)
            path == "/post/hot/today" -> WebRoute(WebRouteKind.POST_LIST, canonical)
            path == "/post/hot/recent" -> WebRoute(WebRouteKind.POST_LIST, canonical)
            path == "/post/latest" -> WebRoute(WebRouteKind.POST_LIST, canonical)
            path == "/notifications" -> WebRoute(WebRouteKind.NOTIFICATIONS, canonical)
            path == "/user/setting/profile" -> WebRoute(WebRouteKind.PROFILE, canonical)
            path == "/auth/login" -> WebRoute(WebRouteKind.LOGIN, canonical)
            path == "/post/create" -> WebRoute(WebRouteKind.CREATE_POST, canonical)
            segments.size == 3 && segments[0] == "post" -> WebRoute(
                kind = WebRouteKind.POST_DETAIL,
                url = canonical,
                nodeSlug = segments[1],
                postId = segments[2],
            )
            else -> WebRoute(WebRouteKind.SITE_PAGE, canonical)
        }
    }

    /** Alias that reads naturally at call sites which deal with navigation. */
    fun evaluate(rawUrl: String?): WebRoute = classify(rawUrl)

    /** Alias retained for integrations that call the policy as a route parser. */
    fun route(rawUrl: String?): WebRoute = classify(rawUrl)

    fun normalize(rawUrl: String?): String? =
        classify(rawUrl).takeIf { it.isSitePage }?.url

    fun isAllowedPageUrl(rawUrl: String?): Boolean = classify(rawUrl).isSitePage

    fun isLoginUrl(rawUrl: String?): Boolean = classify(rawUrl).kind == WebRouteKind.LOGIN

    /**
     * URLs that may stay inside the login WebView while an OAuth flow is in
     * progress. Same-origin pages are part of the site's login flow; external
     * navigation is limited to Google's authorization host.
     */
    fun isAllowedLoginFlowUrl(rawUrl: String?): Boolean {
        val route = classify(rawUrl)
        if (route.isSitePage) return true
        if (route.kind != WebRouteKind.EXTERNAL) return false
        val parsed = parse(rawUrl ?: return false) ?: return false
        return parsed.host in LOGIN_PROVIDER_HOSTS &&
            (parsed.uri.port == -1 || parsed.uri.port == 443)
    }

    fun isPostDetailUrl(rawUrl: String?): Boolean =
        classify(rawUrl).kind == WebRouteKind.POST_DETAIL

    /** The top-level routes that host post-composer image editors. */
    fun isPostComposerUrl(rawUrl: String?): Boolean {
        val route = classify(rawUrl)
        if (!route.isSitePage) return false
        if (route.kind == WebRouteKind.CREATE_POST) return true
        val path = runCatching { URI(route.url).rawPath.orEmpty() }.getOrDefault("")
        val segments = path.split('/').filter(String::isNotEmpty)
        return segments.size == 4 && segments[0] == "post" && segments[3] == "edit"
    }

    /**
     * Allows only redirects needed to finish the request for the current page.
     * A redirect to login is intentionally not allowed here: it must be
     * surfaced as a session-expired navigation action.
     */
    fun isAllowedSamePageRedirect(initialUrl: String?, targetUrl: String?): Boolean {
        val initial = parseSite(initialUrl) ?: return false
        val target = parseSite(targetUrl) ?: return false
        val initialRoute = classify(initial.siteUrl)
        val targetRoute = classify(target.siteUrl)
        if (!initialRoute.isSitePage || !targetRoute.isSitePage) return false
        if (targetRoute.kind == WebRouteKind.LOGIN || initialRoute.kind == WebRouteKind.LOGIN) {
            return targetRoute.kind == WebRouteKind.LOGIN &&
                initialRoute.kind == WebRouteKind.LOGIN &&
                initial.path == target.path
        }
        return initial.path == target.path &&
            initialRoute.kind == targetRoute.kind
    }

    /**
     * Media resources are allowed only on the dedicated media host and only
     * under known path prefixes.  This helper is shared by media/bridge code;
     * it does not make a media URL eligible as a WebView page URL.
     */
    fun isTrustedMediaUrl(rawUrl: String?): Boolean {
        val parsed = parse(rawUrl ?: return false) ?: return false
        if (parsed.host != mediaHost || parsed.uri.scheme?.lowercase(Locale.US) != HTTPS_SCHEME) {
            return false
        }
        val path = parsed.path
        return path.startsWith("/i/") ||
            path.startsWith("/video/")
    }

    /** Site-host check for Bridge actions that need to accept only same-origin URLs. */
    fun isTrustedSiteUrl(rawUrl: String?): Boolean = parseSite(rawUrl) != null

    private fun parseSite(rawUrl: String?): ParsedUrl? {
        val parsed = parse(rawUrl ?: return null) ?: return null
        return if (
            parsed.uri.scheme?.lowercase(Locale.US) == HTTPS_SCHEME &&
            parsed.host == siteHost
        ) {
            parsed
        } else {
            null
        }
    }

    private fun parse(rawUrl: String): ParsedUrl? {
        if (rawUrl.length !in 1..MAX_URL_LENGTH) return null
        if (rawUrl.any { it.isISOControl() || it == '\\' || it.isWhitespace() }) return null

        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (uri.userInfo != null || uri.rawAuthority == null) return null
        // The default HTTPS port is equivalent to an omitted port.  Any other
        // port would be an alternate authenticated/media endpoint.
        if ((host == siteHost || host == mediaHost) && uri.port != -1 && uri.port != 443) return null
        val rawPath = uri.rawPath?.ifEmpty { "/" } ?: "/"
        if (!rawPath.startsWith('/') || rawPath.contains("//")) return null
        val decodedSegments = decodePathSegments(rawPath) ?: return null
        val normalizedPath = canonicalPath(decodedSegments)
        val encodedPath = canonicalEncodedPath(rawPath)
        val siteUrl = buildUrl(host = siteHost, path = encodedPath, uri = uri)
        // Keep arbitrary external authorities (including bracketed IPv6
        // literals) exactly as parsed instead of reconstructing them from
        // URI.host, which would lose authority syntax.
        val externalUrl = uri.toASCIIString()
        return ParsedUrl(
            uri = uri,
            host = host,
            path = normalizedPath,
            decodedSegments = decodedSegments,
            siteUrl = siteUrl,
            externalUrl = externalUrl,
        )
    }

    private fun decodePathSegments(rawPath: String): List<String>? {
        val rawSegments = rawPath.removePrefix("/").split('/').filter { it.isNotEmpty() }
        val decoded = rawSegments.map { segment ->
            // URLDecoder is used only for a path segment.  A plus sign is a
            // literal path character, not application/x-www-form-urlencoded
            // whitespace, so decode it explicitly after URLDecoder.
            val withPlaceholder = segment.replace("+", "%2B")
            try {
                URLDecoder.decode(withPlaceholder, StandardCharsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        if (decoded.any { segment ->
                segment == "." ||
                    segment == ".." ||
                    segment.contains('/') ||
                    segment.contains('\\') ||
                    segment.any { it.isISOControl() }
            }) {
            return null
        }
        return decoded
    }

    private fun canonicalPath(segments: List<String>): String = if (segments.isEmpty()) {
        "/"
    } else {
        "/" + segments.joinToString("/")
    }

    /**
     * Keep the already URI-encoded path for the emitted URL.  Rebuilding from
     * decoded segments would turn `%20` into a literal space and could also
     * change reserved characters.  The decoded form is used only for route
     * classification after traversal/reserved-separator checks above.
     */
    private fun canonicalEncodedPath(rawPath: String): String {
        val withoutLeading = rawPath.removePrefix("/")
        if (withoutLeading.isEmpty()) return "/"
        val withoutTrailing = withoutLeading.removeSuffix("/")
        return "/$withoutTrailing"
    }

    private fun buildUrl(
        host: String,
        path: String,
        uri: URI,
    ): String = buildString {
        append(HTTPS_SCHEME)
        append("://")
        append(host)
        append(path)
        uri.rawQuery?.let {
            append('?')
            append(it)
        }
        uri.rawFragment?.let {
            append('#')
            append(it)
        }
    }

    private data class ParsedUrl(
        val uri: URI,
        val host: String,
        val path: String,
        val decodedSegments: List<String>,
        val siteUrl: String,
        val externalUrl: String,
    )

    companion object {
        const val DEFAULT_SITE_HOST: String = "2libra.com"
        const val DEFAULT_MEDIA_HOST: String = "r2.2libra.com"
        const val HTTPS_SCHEME: String = "https"
        const val MAX_URL_LENGTH: Int = 8_192

        private val LOGIN_PROVIDER_HOSTS = setOf("accounts.google.com")
    }
}
