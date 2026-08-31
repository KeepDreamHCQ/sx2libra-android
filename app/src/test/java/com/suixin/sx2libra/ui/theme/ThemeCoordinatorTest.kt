package com.suixin.sx2libra.ui.theme

import com.suixin.sx2libra.data.local.WebThemeStore
import com.suixin.sx2libra.model.AppThemeMode
import com.suixin.sx2libra.model.WebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCoordinatorTest {
    @Test
    fun persistedThemeIsAppliedBeforeAnObservationReports() {
        val applied = mutableListOf<AppThemeMode>()
        val coordinator = ThemeCoordinator(
            store = FakeStore(WebTheme.DARK),
            applyMode = applied::add,
        )

        coordinator.createObservation()

        assertEquals(listOf(AppThemeMode.DARK), applied)
    }

    @Test
    fun missingThemeFollowsTheSystem() {
        val applied = mutableListOf<AppThemeMode>()
        val coordinator = ThemeCoordinator(
            store = FakeStore(null),
            applyMode = applied::add,
        )

        coordinator.createObservation()

        assertEquals(listOf(AppThemeMode.FOLLOW_SYSTEM), applied)
    }

    @Test
    fun unknownAndDuplicateReportsDoNotPersistOrReapply() {
        val store = FakeStore(null)
        val applied = mutableListOf<AppThemeMode>()
        val observation = ThemeCoordinator(store, applied::add).createObservation()
        observation.activate()

        observation.report(WebTheme.UNKNOWN)
        observation.report(WebTheme.DARK)
        observation.report(WebTheme.DARK)

        assertEquals(listOf(AppThemeMode.FOLLOW_SYSTEM, AppThemeMode.DARK), applied)
        assertEquals(listOf(WebTheme.DARK), store.writes)
    }

    @Test
    fun backgroundObservationCannotOverrideTheForegroundObservation() {
        val applied = mutableListOf<AppThemeMode>()
        val coordinator = ThemeCoordinator(FakeStore(null), applied::add)
        val foreground = coordinator.createObservation()
        val background = coordinator.createObservation()

        foreground.activate()
        foreground.report(WebTheme.DARK)
        background.report(WebTheme.LIGHT)

        assertEquals(AppThemeMode.DARK, applied.last())

        background.activate()
        assertEquals(AppThemeMode.LIGHT, applied.last())
        foreground.report(WebTheme.DARK)
        assertEquals(AppThemeMode.LIGHT, applied.last())
    }

    @Test
    fun anObservationCanBecomeForegroundWithItsCachedTheme() {
        val store = FakeStore(null)
        val applied = mutableListOf<AppThemeMode>()
        val coordinator = ThemeCoordinator(store, applied::add)
        val observation = coordinator.createObservation()

        observation.report(WebTheme.LIGHT)
        assertTrue(applied.last() == AppThemeMode.FOLLOW_SYSTEM)

        observation.activate()

        assertEquals(AppThemeMode.LIGHT, applied.last())
        assertEquals(listOf(WebTheme.LIGHT), store.writes)
    }

    private class FakeStore(initial: WebTheme?) : WebThemeStore {
        private var value = initial
        val writes = mutableListOf<WebTheme>()

        override fun read(): WebTheme? = value

        override fun write(theme: WebTheme) {
            value = theme
            writes += theme
        }
    }
}
