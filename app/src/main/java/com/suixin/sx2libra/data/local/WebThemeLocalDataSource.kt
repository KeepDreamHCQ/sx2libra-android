package com.suixin.sx2libra.data.local

import com.suixin.sx2libra.model.WebTheme
import com.tencent.mmkv.MMKV

/** Small persistence boundary for the last resolved 2Libra page theme. */
interface WebThemeStore {
    fun read(): WebTheme?

    fun write(theme: WebTheme)
}

/** Stores only the non-sensitive, last known light/dark theme decision. */
class MmkvWebThemeStore(
    private val mmkv: MMKV = MMKV.mmkvWithID(STORE_ID),
) : WebThemeStore {
    override fun read(): WebTheme? = when (mmkv.decodeString(KEY, null)?.lowercase()) {
        "light" -> WebTheme.LIGHT
        "dark" -> WebTheme.DARK
        else -> null
    }

    override fun write(theme: WebTheme) {
        if (theme == WebTheme.UNKNOWN) return
        mmkv.encode(KEY, theme.name.lowercase())
    }

    private companion object {
        const val STORE_ID = "web-theme"
        const val KEY = "last-resolved-theme"
    }
}
