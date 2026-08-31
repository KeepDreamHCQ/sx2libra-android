package com.suixin.sx2libra.web

import org.junit.Assert.assertEquals
import org.junit.Test

class PostImagePickerTest {
    @Test
    fun legacyChooserCompletesOldCallbackBeforeReplacingItAndOnlyOnce() {
        val results = mutableListOf<String?>()
        val chooser = LegacySingleFileChooser()

        chooser.begin { results += it }
        chooser.begin { results += it }
        chooser.complete("content://picked/image")
        chooser.complete("content://duplicate")

        assertEquals(listOf(null, "content://picked/image"), results)
    }

    @Test
    fun cancelCompletesPendingLegacyCallbackExactlyOnce() {
        val results = mutableListOf<String?>()
        val chooser = LegacySingleFileChooser()

        chooser.begin { results += it }
        chooser.cancel()
        chooser.cancel()

        assertEquals(listOf(null), results)
    }
}
