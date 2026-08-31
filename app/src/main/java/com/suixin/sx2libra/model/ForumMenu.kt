package com.suixin.sx2libra.model

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A user-visible forum tab. Only the relative [path] is persisted. */
data class ForumMenu(
    val id: String,
    val name: String,
    val path: String
) {
    val url: String
        get() = ForumMenuSpec.urlForPath(path)
}

/** Versioned menu payload persisted as one MMKV value. */
data class ForumMenuConfig(
    val schemaVersion: Int = ForumMenuSpec.SCHEMA_VERSION,
    val revision: Long = 0L,
    val menus: List<ForumMenu> = ForumMenuSpec.defaultMenus()
)

/**
 * Pure validation and URL construction for forum menu configuration.
 *
 * Keeping this object completely Android-free makes the security boundary easy to exercise
 * in JVM tests. A menu always stores a path, never a complete user-provided URL.
 */
object ForumMenuSpec {
    const val SCHEMA_VERSION: Int = 1
    const val SITE_SCHEME: String = "https"
    const val SITE_HOST: String = "2libra.com"
    const val SITE_PREFIX: String = "https://2libra.com/"
    const val MAX_MENU_COUNT: Int = 50
    const val MAX_NAME_LENGTH: Int = 20
    const val MAX_PATH_LENGTH: Int = 2_048

    const val HOME_ID: String = "default-home"
    const val TODAY_ID: String = "default-today"
    const val RECENT_ID: String = "default-recent"
    const val LATEST_ID: String = "default-latest"

    fun defaultMenus(): List<ForumMenu> = listOf(
        ForumMenu(HOME_ID, "2Libra首页", "/"),
        ForumMenu(TODAY_ID, "今日热议", "/post/hot/today"),
        ForumMenu(RECENT_ID, "近期热议", "/post/hot/recent"),
        ForumMenu(LATEST_ID, "新发表", "/post/latest")
    )

    fun newId(): String = UUID.randomUUID().toString()

    /** Returns a trimmed, single-line name or a stable validation error. */
    fun normalizeName(input: String): Result<String> {
        val value = input.trim()
        if (value.isEmpty()) return Result.failure(IllegalArgumentException("名称不能为空"))
        if (value.length > MAX_NAME_LENGTH) {
            return Result.failure(IllegalArgumentException("名称不能超过${MAX_NAME_LENGTH}个字符"))
        }
        if (value.any { it.isISOControl() || it == '\n' || it == '\r' }) {
            return Result.failure(IllegalArgumentException("名称不能包含控制字符"))
        }
        return Result.success(value)
    }

    /**
     * Normalizes a user-entered relative path to an absolute path component.
     * Empty input means the site home page. Queries, fragments, authorities, schemes,
     * path traversal and ambiguous slash/backslash forms are rejected.
     */
    fun normalizePath(input: String): Result<String> {
        val value = input.trim()
        if (value.isEmpty()) return Result.success("/")
        if (value.length > MAX_PATH_LENGTH) {
            return Result.failure(IllegalArgumentException("路径过长"))
        }
        if (value.any { it.isISOControl() || it.isWhitespace() }) {
            return Result.failure(IllegalArgumentException("路径不能包含控制字符"))
        }
        if (value.contains('\\') || value.contains("//")) {
            return Result.failure(IllegalArgumentException("路径格式不安全"))
        }
        if (value.contains('?') || value.contains('#')) {
            return Result.failure(IllegalArgumentException("路径不能包含 query 或 fragment"))
        }

        val candidate = if (value.startsWith('/')) value else "/$value"
        if (!candidate.startsWith('/')) {
            return Result.failure(IllegalArgumentException("路径格式无效"))
        }
        val firstSegment = candidate.removePrefix("/").substringBefore('/')
        if (firstSegment.contains(':')) {
            return Result.failure(IllegalArgumentException("路径不能包含 scheme"))
        }

        val segments = candidate.removePrefix("/").split('/')
        val hasInvalidSegment = candidate != "/" && segments.withIndex().any { (index, segment) ->
            // A single trailing slash is harmless and is preserved; an empty segment
            // anywhere else would be an ambiguous // path (already rejected above).
            val trailingSlash = index == segments.lastIndex && segment.isEmpty()
            val decoded = decodePathSegment(segment)
            !trailingSlash && (segment == "." || segment == ".." ||
                decoded == null || decoded == "." || decoded == ".." ||
                decoded.contains('/') || decoded.contains('\\') ||
                decoded.any(Char::isISOControl))
        }
        if (hasInvalidSegment) {
            return Result.failure(IllegalArgumentException("路径不能包含空段或路径穿越"))
        }
        // Let java.net.URI validate characters that cannot safely be emitted in a
        // URL (for example an unmatched '['), while retaining Unicode paths for
        // toASCIIString() to encode later.
        val uri = runCatching { URI("$SITE_SCHEME://$SITE_HOST$candidate") }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("路径包含非法 URL 字符"))
        if (uri.scheme != SITE_SCHEME || uri.host != SITE_HOST || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath?.startsWith('/') != true
        ) {
            return Result.failure(IllegalArgumentException("路径不是安全的站内路径"))
        }
        return Result.success(candidate)
    }

    fun normalizeMenu(menu: ForumMenu): Result<ForumMenu> {
        val id = menu.id.trim()
        if (id.isEmpty() || id.length > 128 || id.any { it.isISOControl() }) {
            return Result.failure(IllegalArgumentException("菜单 ID 无效"))
        }
        val name = normalizeName(menu.name).getOrElse { return Result.failure(it) }
        val path = normalizePath(menu.path).getOrElse { return Result.failure(it) }
        return Result.success(ForumMenu(id, name, path))
    }

    /** Validates an entire config and returns its canonical representation. */
    fun normalizeConfig(config: ForumMenuConfig): Result<ForumMenuConfig> {
        if (config.schemaVersion != SCHEMA_VERSION || config.revision < 0L) {
            return Result.failure(IllegalArgumentException("菜单配置版本无效"))
        }
        if (config.menus.isEmpty() || config.menus.size > MAX_MENU_COUNT) {
            return Result.failure(IllegalArgumentException("菜单数量无效"))
        }

        val normalized = ArrayList<ForumMenu>(config.menus.size)
        val ids = HashSet<String>()
        val names = HashSet<String>()
        val paths = HashSet<String>()
        config.menus.forEach { menu ->
            val canonical = normalizeMenu(menu).getOrElse { return Result.failure(it) }
            if (!ids.add(canonical.id)) {
                return Result.failure(IllegalArgumentException("菜单 ID 重复"))
            }
            if (!names.add(canonical.name)) {
                return Result.failure(IllegalArgumentException("菜单名称重复"))
            }
            if (!paths.add(canonical.path)) {
                return Result.failure(IllegalArgumentException("菜单路径重复"))
            }
            normalized += canonical
        }
        return Result.success(config.copy(schemaVersion = SCHEMA_VERSION, menus = normalized))
    }

    fun urlForPath(path: String): String {
        val normalized = normalizePath(path).getOrElse { throw IllegalArgumentException("非法菜单路径") }
        val uri = try {
            URI("$SITE_SCHEME://$SITE_HOST$normalized")
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("非法菜单路径")
        }
        check(
            uri.scheme == SITE_SCHEME && uri.host == SITE_HOST &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath?.startsWith('/') == true
        ) {
            "非法站内 URL"
        }
        // toASCIIString preserves valid raw escapes (for example %20) and encodes
        // otherwise-unicode path characters without double-encoding the path.
        return uri.toASCIIString()
    }

    private fun decodePathSegment(segment: String): String? {
        var index = 0
        while (index < segment.length) {
            if (segment[index] == '%') {
                if (index + 2 >= segment.length ||
                    !segment[index + 1].isHexDigit() || !segment[index + 2].isHexDigit()
                ) return null
                index += 3
            } else {
                index++
            }
        }
        return runCatching {
            URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
