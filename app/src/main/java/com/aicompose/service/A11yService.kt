package com.aicompose.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务 — 检测小米相机 + 模拟手势
 */
class A11yService : AccessibilityService() {

    companion object {
        private const val TAG = "A11y"
        const val PKG = "com.android.camera"

        @Volatile
        var instance: A11yService? = null
            private set

        // 小米相机控件 ID（需要真机验证）
        val PARAM_IDS = mapOf(
            "iso" to "com.android.camera:id/iso_value",
            "shutter" to "com.android.camera:id/shutter_value",
            "ev" to "com.android.camera:id/ev_value",
            "wb" to "com.android.camera:id/wb_value",
            "focus" to "com.android.camera:id/focus_value",
            "zoom" to "com.android.camera:id/zoom_text"
        )
    }

    var isCameraForeground = false
        private set

    var lastGestureSuccess = false
    @Volatile
    private var gestureCallback: ((Boolean) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val was = isCameraForeground
            isCameraForeground = event.packageName?.toString() == PKG
            if (isCameraForeground && !was) {
                Log.d(TAG, "小米相机进入前台")
                dumpUI()
            }
        }
    }

    private fun dumpUI() {
        val root = rootInActiveWindow ?: return
        dumpNode(root, 0)
        root.recycle()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty() || id.contains("camera")) {
            Log.d(TAG, "${"  ".repeat(depth)}[$id] text='$text'")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
            child.recycle()
        }
    }

    /** 获取参数控件位置 */
    fun getParamRect(paramKey: String): Rect? {
        val id = PARAM_IDS[paramKey] ?: return null
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        val node = nodes?.firstOrNull()
        val rect = if (node != null) { val r = Rect(); node.getBoundsInScreen(r); node.recycle(); r } else null
        root.recycle()
        return rect
    }

    /** 读取参数文本 */
    fun getParamText(paramKey: String): String? {
        val id = PARAM_IDS[paramKey] ?: return null
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        val text = nodes?.firstOrNull()?.text?.toString()
        nodes?.forEach { it.recycle() }
        root.recycle()
        return text
    }

    /** 注册手势完成回调 */
    fun setGestureCallback(cb: (Boolean) -> Unit) {
        gestureCallback = cb
    }

    /** 捏合手势 */
    fun pinch(scale: Float) {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f; val cy = dm.heightPixels / 2f
        val base = 150f; val end = base * scale
        val p1 = Path().apply { moveTo(cx, cy - base/2); lineTo(cx, cy - end/2) }
        val p2 = Path().apply { moveTo(cx, cy + base/2); lineTo(cx, cy + end/2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 250))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, 250))
            .build()
        dispatchGesture(g, gestureCb(), null)
    }

    /** 滑动手势 */
    fun swipe(sx: Float, sy: Float, ex: Float, ey: Float) {
        val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 150))
            .build()
        dispatchGesture(g, gestureCb(), null)
    }

    /** 点击手势 */
    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        dispatchGesture(g, gestureCb(), null)
    }

    /** 点击指定参数控件 */
    fun tapParam(paramKey: String): Boolean {
        val rect = getParamRect(paramKey) ?: return false
        tap(rect.centerX().toFloat(), rect.centerY().toFloat())
        return true
    }

    /** 滑动调整参数 */
    fun adjustParam(paramKey: String, direction: Int) {
        val rect = getParamRect(paramKey) ?: return
        val cx = rect.centerX().toFloat(); val cy = rect.centerY().toFloat()
        val d = 120f
        when (direction) {
            0 -> swipe(cx + d, cy, cx - d, cy) // left
            1 -> swipe(cx - d, cy, cx + d, cy) // right
            2 -> swipe(cx, cy + d, cx, cy - d) // up
            3 -> swipe(cx, cy - d, cx, cy + d) // down
        }
    }

    private fun gestureCb() = object : GestureResultCallback() {
        override fun onCompleted(d: GestureDescription?) { gestureCallback?.invoke(true) }
        override fun onCancelled(d: GestureDescription?) { gestureCallback?.invoke(false) }
    }

    override fun onInterrupt() { Log.w(TAG, "无障碍服务中断") }
    override fun onDestroy() { instance = null; super.onDestroy() }
}
