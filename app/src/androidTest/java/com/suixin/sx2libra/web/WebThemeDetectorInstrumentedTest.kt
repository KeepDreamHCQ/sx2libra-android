package com.suixin.sx2libra.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suixin.sx2libra.model.WebTheme
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebThemeDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun detectsNightDataAttributeAndDarkClass() {
        assertEquals(
            listOf(WebTheme.DARK),
            inspectPage("<html data-theme=\"night\"><body>night</body></html>", WebTheme.DARK),
        )
        assertEquals(
            listOf(WebTheme.DARK),
            inspectPage("<html class=\"dark\"><body>dark</body></html>", WebTheme.DARK),
        )
    }

    @Test
    fun detectsLightPageAndDoesNotTreatAstealImageAsDarkBackground() {
        val html = """
            <html><body style="margin:0;background:rgb(255,255,255);color:rgb(17,17,17)">
              <img style="width:100vw;height:100vh" src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='20' height='20'%3E%3Crect width='20' height='20' fill='black'/%3E%3C/svg%3E">
            </body></html>
        """.trimIndent()

        assertEquals(listOf(WebTheme.LIGHT), inspectPage(html, WebTheme.LIGHT))
    }

    @Test
    fun reportsRuntimeThemeSwitchOnlyWhenTheResolvedThemeChanges() {
        val themes = Collections.synchronizedList(mutableListOf<WebTheme>())
        val callbacks = CountDownLatch(2)
        lateinit var webView: WebView
        lateinit var detector: WebThemeDetector
        val activity = launchTestActivity()
        instrumentation.runOnMainSync {
            webView = WebView(activity)
            activity.setContentView(webView)
            webView.settings.javaScriptEnabled = true
            detector = WebThemeDetector(activity) {
                themes += it
                callbacks.countDown()
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    detector.inspect(view)
                }
            }
            webView.loadDataWithBaseURL(
                "https://2libra.com/",
                "<html data-theme=\"light\"><body>theme</body></html>",
                "text/html",
                "UTF-8",
                null,
            )
        }

        try {
            assertFalse(callbacks.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    "document.documentElement.setAttribute('data-theme', 'night');",
                ) {
                    detector.inspect(webView)
                }
            }
            assertTrue(callbacks.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(WebTheme.LIGHT, WebTheme.DARK), themes)
        } finally {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.destroy()
                activity.finish()
            }
        }
    }

    private fun inspectPage(html: String, expectedTheme: WebTheme): List<WebTheme> {
        val themes = Collections.synchronizedList(mutableListOf<WebTheme>())
        val callback = CountDownLatch(1)
        lateinit var webView: WebView
        val activity = launchTestActivity()
        instrumentation.runOnMainSync {
            webView = WebView(activity)
            activity.setContentView(webView)
            webView.settings.javaScriptEnabled = true
            val detector = WebThemeDetector(activity) {
                themes += it
                callback.countDown()
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    detector.inspect(view)
                }
            }
            webView.loadDataWithBaseURL(
                "https://2libra.com/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        }

        return try {
            assertTrue(callback.await(10, TimeUnit.SECONDS))
            assertEquals(expectedTheme, themes.single())
            themes.toList()
        } finally {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.destroy()
                activity.finish()
            }
        }
    }

    private fun launchTestActivity(): WebThemeDetectorTestActivity =
        instrumentation.startActivitySync(
            android.content.Intent(
                instrumentation.targetContext,
                WebThemeDetectorTestActivity::class.java,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as WebThemeDetectorTestActivity
}
