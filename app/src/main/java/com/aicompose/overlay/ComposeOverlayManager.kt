package com.aicompose.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.aicompose.ai.CompositionEngine

/**
 * 叠加层管理器 - 管理悬浮窗的显示/隐藏
 */
class ComposeOverlayManager(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ComposeOverlayManager? = null

        fun getInstance(context: Context): ComposeOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: ComposeOverlayManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private var overlayView: ComposeOverlayView? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun show() {
        if (overlayView != null) return

        val view = ComposeOverlayView(context)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(view, params)
        overlayView = view
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    fun updateResult(result: CompositionEngine.AnalysisResult?) {
        overlayView?.analysisResult = result
    }

    fun updateAction(action: String?) {
        overlayView?.lastAction = action
    }

    fun isVisible(): Boolean = overlayView != null
}
