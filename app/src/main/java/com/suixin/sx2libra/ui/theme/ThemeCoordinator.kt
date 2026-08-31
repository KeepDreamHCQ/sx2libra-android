package com.suixin.sx2libra.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import com.suixin.sx2libra.data.local.WebThemeStore
import com.suixin.sx2libra.model.AppThemeMode
import com.suixin.sx2libra.model.WebTheme

/**
 * Coordinates theme reports from multiple WebViews. Only the observation
 * that currently owns the foreground page can change the application theme.
 */
class ThemeCoordinator(
    private val store: WebThemeStore,
    private val applyMode: (AppThemeMode) -> Unit = { mode ->
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                AppThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    },
) {
    private var initialized = false
    private var appliedTheme = WebTheme.UNKNOWN
    private var activeObservation: Observation? = null

    /** Applies the persisted mode before the first Activity inflates its UI. */
    fun initialize() {
        if (initialized) return
        initialized = true
        val persisted = store.read()?.takeIf { it != WebTheme.UNKNOWN }
        appliedTheme = persisted ?: WebTheme.UNKNOWN
        applyMode(
            when (persisted) {
                WebTheme.LIGHT -> AppThemeMode.LIGHT
                WebTheme.DARK -> AppThemeMode.DARK
                null -> AppThemeMode.FOLLOW_SYSTEM
                WebTheme.UNKNOWN -> AppThemeMode.FOLLOW_SYSTEM
            },
        )
    }

    fun createObservation(): Observation {
        initialize()
        return Observation(this)
    }

    private fun activate(observation: Observation) {
        if (observation.closed) return
        activeObservation = observation
        observation.latestTheme
            .takeIf { it != WebTheme.UNKNOWN }
            ?.let(::applyResolvedTheme)
    }

    private fun deactivate(observation: Observation) {
        if (activeObservation === observation) activeObservation = null
    }

    private fun close(observation: Observation) {
        deactivate(observation)
        observation.closed = true
    }

    private fun report(observation: Observation, theme: WebTheme) {
        if (observation.closed) return
        observation.latestTheme = theme
        if (activeObservation === observation) applyResolvedTheme(theme)
    }

    private fun applyResolvedTheme(theme: WebTheme) {
        if (theme == WebTheme.UNKNOWN || theme == appliedTheme) return
        appliedTheme = theme
        store.write(theme)
        applyMode(
            when (theme) {
                WebTheme.LIGHT -> AppThemeMode.LIGHT
                WebTheme.DARK -> AppThemeMode.DARK
                WebTheme.UNKNOWN -> AppThemeMode.FOLLOW_SYSTEM
            },
        )
    }

    class Observation internal constructor(
        private val coordinator: ThemeCoordinator,
    ) {
        internal var latestTheme: WebTheme = WebTheme.UNKNOWN
        internal var closed: Boolean = false

        fun activate() = coordinator.activate(this)

        fun deactivate() = coordinator.deactivate(this)

        fun report(theme: WebTheme) = coordinator.report(this, theme)

        fun close() = coordinator.close(this)
    }
}
