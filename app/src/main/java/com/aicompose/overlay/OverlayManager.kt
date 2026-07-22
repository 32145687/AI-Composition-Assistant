package com.aicompose.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.aicompose.ai.CompositionResult

object OverlayManager {
    private var view: OverlayView? = null
    private var wm: WindowManager? = null

    fun show(ctx: Context) {
        if (view != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = OverlayView(ctx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm?.addView(v, params)
        view = v
    }

    fun hide() {
        view?.let { wm?.removeView(it) }
        view = null
    }

    fun update(result: CompositionResult?) { view?.result = result }

    fun cycleGuide() { view?.cycleGuide() }

    fun isVisible() = view != null
}
