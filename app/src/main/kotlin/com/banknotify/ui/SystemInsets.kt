package com.banknotify.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Keep controls clear of system bars, cutouts and the keyboard, including enforced edge-to-edge. */
internal fun AppCompatActivity.applySystemInsets() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val content = findViewById<View>(android.R.id.content)
    val left = content.paddingLeft
    val top = content.paddingTop
    val right = content.paddingRight
    val bottom = content.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(content)
}
