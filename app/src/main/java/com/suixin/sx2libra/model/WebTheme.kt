package com.suixin.sx2libra.model

/** Theme state reported by a top-level 2Libra document. */
enum class WebTheme {
    LIGHT,
    DARK,
    UNKNOWN,
}

/** AppCompat night-mode choice used by the application theme coordinator. */
enum class AppThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}
