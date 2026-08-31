package com.suixin.sx2libra.ui.system

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Enables edge-to-edge rendering while keeping system bar icons readable. */
fun Activity.enableImmersiveSystemBars(
    lightStatusBars: Boolean = true,
    lightNavigationBars: Boolean = lightStatusBars,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightStatusBars
        isAppearanceLightNavigationBars = lightNavigationBars
    }
}

/** Applies system-bar and IME insets on top of the view's XML/programmatic padding. */
fun View.applySystemBarInsets(
    top: Boolean = false,
    bottom: Boolean = false,
    left: Boolean = false,
    right: Boolean = false,
) {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.updatePadding(
            left = initialLeft + if (left) systemBars.left else 0,
            top = initialTop + if (top) systemBars.top else 0,
            right = initialRight + if (right) systemBars.right else 0,
            bottom = initialBottom + if (bottom) maxOf(systemBars.bottom, ime.bottom) else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
