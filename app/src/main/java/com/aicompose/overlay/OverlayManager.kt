package com.aicompose.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.aicompose.ai.CompositionResult

object OverlayManager {
    private const val TAG = "Overlay"
    private var view: AROverlayView? = null
    private var wm: WindowManager? = null

    fun hasPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(ctx)
        } else true
    }

    fun show(ctx: Context) {
        if (view != null) { Log.d(TAG, "已经在显示"); return }

        if (!hasPermission(ctx)) {
            Log.e(TAG, "没有悬浮窗权限！请在设置中授予")
            // 跳转到悬浮窗权限设置
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (e: Exception) { Log.e(TAG, "跳转设置失败", e) }
            return
        }

        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = AROverlayView(ctx)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        try {
            wm?.addView(v, params)
            view = v
            Log.d(TAG, "AR叠加层已显示, type=$type, permission=${hasPermission(ctx)}")
        } catch (e: Exception) {
            Log.e(TAG, "显示叠加层失败: ${e.message}", e)
        }
    }

    fun hide() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null
    }

    fun update(result: CompositionResult?) { view?.result = result }

    fun updateMotion(dx: Float, dy: Float) {
        view?.motionDx = dx
        view?.motionDy = dy
    }

    fun cycleGuide() { view?.cycleGuide() }
    fun isVisible() = view != null
}
