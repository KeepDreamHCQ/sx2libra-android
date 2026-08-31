package com.suixin.sx2libra.data.repository

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixin.sx2libra.data.local.WebSnapshotLocalDataSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSnapshotRepositoryInstrumentedTest {
    @Test
    fun bitmapIsEncodedAndDecodedThroughThePrivateSnapshotCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "web-snapshot-instrumented-${System.nanoTime()}")
        val repository = WebSnapshotRepository(WebSnapshotLocalDataSource(directory))
        val original = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val url = "https://2libra.com/post/latest"
        var decoded: Bitmap? = null
        try {
            for (y in 0 until original.height) {
                for (x in 0 until original.width) {
                    original.setPixel(x, y, if ((x + y) % 2 == 0) 0xffff00ff.toInt() else 0xff00ffff.toInt())
                }
            }

            assertTrue(repository.save(url, original))
            decoded = repository.read(url, 24, 24)
            assertNotNull(decoded)
            assertEquals(24, requireNotNull(decoded).width)
            assertEquals(24, requireNotNull(decoded).height)
        } finally {
            decoded?.recycle()
            original.recycle()
            repository.clear()
            directory.deleteRecursively()
        }
    }
}
