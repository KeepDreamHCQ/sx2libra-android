package com.suixin.sx2libra.web

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Provides an attached window for WebView instrumentation tests. */
class WebThemeDetectorTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
