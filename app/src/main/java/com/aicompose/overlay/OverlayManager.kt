package com.aicompose.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.aicompose.ai.CompositionResult

/**
 * 叠加层管理器 — 保证完全透明，不遮挡相机
 */
object OverlayManager {
    private const val TAG = "Overlay"
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
            // 关键：NOT_FOCUSABLE + NOT_TOUCHABLE = 不拦截任何触摸事件
            // 所有触摸事件穿透到下面的相机 App
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT  // 完全透明
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }

        try {
            wm?.addView(v, params)
            view = v
            Log.d(TAG, "叠加层已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示叠加层失败", e)
        }
    }

    fun hide() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏叠加层失败", e)
        }
        view = null
    }

    fun update(result: CompositionResult?) {
        view?.result = result
    }

    fun cycleGuide() {
        view?.cycleGuide()
    }

    fun isVisible() = view != null
}
