package com.suixin.sx2libra.data.local

import com.tencent.mmkv.MMKV
import com.suixin.sx2libra.model.ForumMenuConfig
import com.suixin.sx2libra.model.ForumMenu
import com.suixin.sx2libra.model.ForumMenuSpec

/** Small abstraction that keeps MMKV out of repositories and ViewModels. */
interface ForumMenuDataSource {
    fun readConfig(): ForumMenuConfig
    fun writeConfig(config: ForumMenuConfig)
}

/**
 * The only key/value operation needed by the menu store. It also makes the persistence
 * model straightforward to exercise without initializing Android's MMKV runtime.
 */
interface ForumMenuStringStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

private class MmkvStringStore(private val mmkv: MMKV) : ForumMenuStringStore {
    override fun getString(key: String): String? = mmkv.decodeString(key, null)

    override fun putString(key: String, value: String) {
        // MMKV writes synchronously; there is no apply()/commit() race to manage.
        mmkv.encode(key, value)
    }
}

/** Versioned, single-value MMKV storage for ordered forum menus. */
class ForumMenuLocalDataSource private constructor(
    private val store: ForumMenuStringStore
) : ForumMenuDataSource {

    constructor() : this(MmkvStringStore(MMKV.mmkvWithID(STORE_ID)))

    /** Visible for JVM tests and non-MMKV persistence adapters. */
    constructor(store: ForumMenuStringStore, testOnly: Boolean = true) : this(store)

    override fun readConfig(): ForumMenuConfig {
        val raw = store.getString(KEY)
        val parsed = raw?.let(ForumMenuConfigCodec::decode)
        val canonical = parsed?.let { ForumMenuSpec.normalizeConfig(it).getOrNull() }
        if (canonical != null) {
            // Repair harmless formatting/schema drift in one atomic value, while retaining
            // the valid revision and ordered list.
            if (raw != ForumMenuConfigCodec.encode(canonical)) {
                store.putString(KEY, ForumMenuConfigCodec.encode(canonical))
            }
            return canonical
        }

        val defaults = ForumMenuConfig(revision = 0L, menus = ForumMenuSpec.defaultMenus())
        store.putString(KEY, ForumMenuConfigCodec.encode(defaults))
        return defaults
    }

    override fun writeConfig(config: ForumMenuConfig) {
        val canonical = ForumMenuSpec.normalizeConfig(config)
            .getOrElse { throw IllegalArgumentException("无法保存非法菜单配置", it) }
        store.putString(KEY, ForumMenuConfigCodec.encode(canonical))
    }

    companion object {
        const val STORE_ID: String = "forum_menu"
        const val KEY: String = "forum_menu_config_v1"
    }
}

/**
 * The menu payload has a deliberately tiny, explicit schema. This codec is deliberately
 * Android-free: the same validation and persistence model can therefore be tested on the
 * regular JVM without relying on Android's "method not mocked" org.json stubs.
 */
object ForumMenuConfigCodec {
    fun encode(config: ForumMenuConfig): String {
        return buildString {
            append("{\"schemaVersion\":")
            append(config.schemaVersion)
            append(",\"revision\":")
            append(config.revision)
            append(",\"menus\":[")
            config.menus.forEachIndexed { index, menu ->
                if (index > 0) append(',')
                append("{\"id\":")
                append(quoteJson(menu.id))
                append(",\"name\":")
                append(quoteJson(menu.name))
                append(",\"path\":")
                append(quoteJson(menu.path))
                append('}')
            }
            append("]}")
        }
    }

    fun decode(raw: String): ForumMenuConfig? = runCatching { decodeUnsafe(raw) }.getOrNull()

    private fun decodeUnsafe(raw: String): ForumMenuConfig? {
        val reader = JsonReader(raw)
        val root = reader.readValue() as? Map<*, *> ?: return null
        reader.requireEnd()

        val schemaVersion = root["schemaVersion"].asLongOrNull()
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt() ?: return null
        val revision = root["revision"].asLongOrNull() ?: return null
        val menusJson = root["menus"] as? List<*> ?: return null
        val menus = ArrayList<ForumMenu>(menusJson.size)
        menusJson.forEach { itemValue ->
            val item = itemValue as? Map<*, *> ?: return null
            val id = item["id"] as? String ?: return null
            val name = item["name"] as? String ?: return null
            val path = item["path"] as? String ?: return null
            menus += ForumMenu(id = id, name = name, path = path)
        }
        return ForumMenuConfig(schemaVersion, revision, menus)
    }

    private fun Any?.asLongOrNull(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        is Short -> toLong()
        is Byte -> toLong()
        is Double -> if (isFinite() && this == toLong().toDouble()) toLong() else null
        is Float -> if (isFinite() && this == toLong().toFloat()) toLong() else null
        else -> null
    }

    private fun quoteJson(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /** Minimal JSON reader for the three-value schema above; rejects malformed payloads. */
    private class JsonReader(private val source: String) {
        private var index: Int = 0

        fun readValue(): Any? {
            skipWhitespace()
            if (index >= source.length) throw IllegalArgumentException("JSON value missing")
            return when (source[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON token")
            }
        }

        fun requireEnd() {
            skipWhitespace()
            if (index != source.length) throw IllegalArgumentException("Trailing JSON data")
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val values = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (consume('}')) return values
            while (true) {
                skipWhitespace()
                val key = readString()
                if (values.containsKey(key)) throw IllegalArgumentException("Duplicate JSON key")
                skipWhitespace()
                expect(':')
                values[key] = readValue()
                skipWhitespace()
                when {
                    consume('}') -> return values
                    consume(',') -> Unit
                    else -> throw IllegalArgumentException("Object separator missing")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val values = ArrayList<Any?>()
            skipWhitespace()
            if (consume(']')) return values
            while (true) {
                values += readValue()
                skipWhitespace()
                when {
                    consume(']') -> return values
                    consume(',') -> Unit
                    else -> throw IllegalArgumentException("Array separator missing")
                }
                skipWhitespace()
                if (peek(']')) throw IllegalArgumentException("Trailing array comma")
            }
        }

        private fun readString(): String {
            expect('"')
            return buildString {
                while (index < source.length) {
                    val character = source[index++]
                    when (character) {
                        '"' -> return@buildString
                        '\\' -> append(readEscape())
                        else -> {
                            if (character.code < 0x20) {
                                throw IllegalArgumentException("Unescaped JSON control character")
                            }
                            append(character)
                        }
                    }
                }
                throw IllegalArgumentException("Unterminated JSON string")
            }
        }

        private fun readEscape(): Char {
            if (index >= source.length) throw IllegalArgumentException("Incomplete JSON escape")
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) throw IllegalArgumentException("Incomplete unicode escape")
                    val hex = source.substring(index, index + 4)
                    index += 4
                    hex.toIntOrNull(16)?.toChar()
                        ?: throw IllegalArgumentException("Invalid unicode escape")
                }
                else -> throw IllegalArgumentException("Unknown JSON escape")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, index)) throw IllegalArgumentException("Invalid JSON literal")
            index += literal.length
            return value
        }

        private fun readNumber(): Number {
            val start = index
            if (peek('-')) index++
            if (index >= source.length) throw IllegalArgumentException("Invalid JSON number")
            if (peek('0')) {
                index++
                if (index < source.length && source[index].isDigit()) {
                    throw IllegalArgumentException("Leading zero in JSON number")
                }
            } else {
                if (!source[index].isDigit()) throw IllegalArgumentException("Invalid JSON number")
                while (index < source.length && source[index].isDigit()) index++
            }
            var fractional = false
            if (consume('.')) {
                fractional = true
                val fractionStart = index
                while (index < source.length && source[index].isDigit()) index++
                if (fractionStart == index) throw IllegalArgumentException("Invalid JSON fraction")
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                fractional = true
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                val exponentStart = index
                while (index < source.length && source[index].isDigit()) index++
                if (exponentStart == index) throw IllegalArgumentException("Invalid JSON exponent")
            }
            val token = source.substring(start, index)
            return if (fractional) token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid JSON number")
            else token.toLongOrNull() ?: throw IllegalArgumentException("Invalid JSON number")
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(character: Char) {
            if (!consume(character)) throw IllegalArgumentException("Expected JSON token")
        }

        private fun consume(character: Char): Boolean {
            if (index < source.length && source[index] == character) {
                index++
                return true
            }
            return false
        }

        private fun peek(character: Char): Boolean = index < source.length && source[index] == character
    }
}
