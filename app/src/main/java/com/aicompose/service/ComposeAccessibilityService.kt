package com.aicompose.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务 - 核心控制器
 *
 * 职责:
 * 1. 检测小米相机 App 是否在前台
 * 2. 发现小米相机 UI 控件（参数面板、快门按钮等）
 * 3. 模拟手势执行 AI 构图指令（捏合缩放、滑动切换参数、点击调整值）
 */
class ComposeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ComposeA11y"
        const val XIAOMI_CAMERA_PACKAGE = "com.android.camera"

        // 小米相机专业模式 UI 控件 ID（通过 UI Inspector 获取）
        // 这些 ID 可能因 MIUI 版本不同而变化，需要在真机上验证
        const val ID_ISO_VALUE = "com.android.camera:id/iso_value"
        const val ID_SHUTTER_VALUE = "com.android.camera:id/shutter_value"
        const val ID_EV_VALUE = "com.android.camera:id/ev_value"
        const val ID_WB_VALUE = "com.android.camera:id/wb_value"
        const val ID_FOCUS_VALUE = "com.android.camera:id/focus_value"
        const val ID_ZOOM_TEXT = "com.android.camera:id/zoom_text"
        const val ID_SHUTTER_BUTTON = "com.android.camera:id/shutter_button"
        const val ID_MODE_SELECTOR = "com.android.camera:id/mode_selector"
        const val ID_PRO_MODE = "com.android.camera:id/pro_mode"

        // 单例访问
        @Volatile
        var instance: ComposeAccessibilityService? = null
            private set
    }

    // 小米相机是否在前台
    private val _isCameraForeground = MutableStateFlow(false)
    val isCameraForeground: StateFlow<Boolean> = _isCameraForeground.asStateFlow()

    // 当前检测到的参数
    private val _detectedParams = MutableStateFlow(CameraParams())
    val detectedParams: StateFlow<CameraParams> = _detectedParams.asStateFlow()

    // 屏幕尺寸
    private var screenWidth = 1080
    private var screenHeight = 2400

    // 手势执行回调
    var onGestureCompleted: ((Boolean) -> Unit)? = null

    data class CameraParams(
        val iso: String = "--",
        val shutter: String = "--",
        val ev: String = "--",
        val wb: String = "--",
        val focus: String = "--",
        val zoom: String = "1x",
        val isProMode: Boolean = false
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }

        Log.d(TAG, "无障碍服务已连接, 屏幕: ${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                val wasForeground = _isCameraForeground.value
                _isCameraForeground.value = packageName == XIAOMI_CAMERA_PACKAGE

                if (_isCameraForeground.value && !wasForeground) {
                    Log.d(TAG, "小米相机进入前台")
                    scanCameraUI()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (_isCameraForeground.value) {
                    // 参数面板更新时重新扫描
                    scanCameraParams()
                }
            }
        }
    }

    /**
     * 扫描小米相机 UI 结构
     */
    private fun scanCameraUI() {
        val root = rootInActiveWindow ?: return

        Log.d(TAG, "--- 扫描小米相机 UI ---")
        dumpNodeTree(root, 0)
        scanCameraParams()
    }

    /**
     * 递归打印 UI 树（调试用）
     */
    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName ?: "no-id"
        val text = node.text?.toString() ?: ""
        val clazz = node.className?.toString()?.substringAfterLast('.') ?: ""
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (text.isNotEmpty() || id.contains("camera")) {
            Log.d(TAG, "${indent}[$clazz] id=$id text='$text' bounds=$rect")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * 扫描小米相机专业模式参数
     */
    private fun scanCameraParams() {
        val root = rootInActiveWindow ?: return

        val params = CameraParams(
            iso = findNodeText(root, ID_ISO_VALUE) ?: "--",
            shutter = findNodeText(root, ID_SHUTTER_VALUE) ?: "--",
            ev = findNodeText(root, ID_EV_VALUE) ?: "--",
            wb = findNodeText(root, ID_WB_VALUE) ?: "--",
            focus = findNodeText(root, ID_FOCUS_VALUE) ?: "--",
            zoom = findNodeText(root, ID_ZOOM_TEXT) ?: "1x",
            isProMode = findNodeById(root, ID_PRO_MODE) != null
        )

        _detectedParams.value = params
        root.recycle()
    }

    /**
     * 通过 ID 查找节点文本
     */
    private fun findNodeText(root: AccessibilityNodeInfo, id: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return nodes?.firstOrNull()?.text?.toString()
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        return nodes?.firstOrNull()
    }

    // ==================== 手势模拟 API ====================

    /**
     * 模拟双指捏合 - 放大/缩小相机
     * @param scale >1 放大, <1 缩小
     * @param centerX 中心点 X
     * @param centerY 中心点 Y
     */
    fun performPinch(scale: Float, centerX: Float = screenWidth / 2f, centerY: Float = screenHeight / 2f) {
        val baseDistance = 200f
        val startDistance = baseDistance
        val endDistance = baseDistance * scale

        val startTime = 0L
        val duration = 300L

        // 手指1: 从中心上方到更上方（放大）或更近（缩小）
        val path1 = Path().apply {
            moveTo(centerX, centerY - startDistance / 2)
            lineTo(centerX, centerY - endDistance / 2)
        }

        // 手指2: 从中心下方到更下方（放大）或更近（缩小）
        val path2 = Path().apply {
            moveTo(centerX, centerY + startDistance / 2)
            lineTo(centerX, centerY + endDistance / 2)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, startTime, duration)
        val stroke2 = GestureDescription.StrokeDescription(path2, startTime, duration)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        Log.d(TAG, "执行捏合: scale=$scale, center=($centerX, $centerY)")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "捏合完成")
                onGestureCompleted?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "捏合取消")
                onGestureCompleted?.invoke(false)
            }
        }, null)
    }

    /**
     * 模拟单指滑动 - 切换参数值
     * @param startX 起始X
     * @param startY 起始Y
     * @param endX 终点X
     * @param endY 终点Y
     * @param duration 持续时间ms
     */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        duration: Long = 200
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        Log.d(TAG, "执行滑动: ($startX,$startY) -> ($endX,$endY)")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onGestureCompleted?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onGestureCompleted?.invoke(false)
            }
        }, null)
    }

    /**
     * 模拟点击
     */
    fun performClick(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        Log.d(TAG, "执行点击: ($x, $y)")

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onGestureCompleted?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onGestureCompleted?.invoke(false)
            }
        }, null)
    }

    /**
     * 点击指定 ID 的控件
     */
    fun clickNodeById(id: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(id)
        val node = nodes?.firstOrNull() ?: return false

        val rect = Rect()
        node.getBoundsInScreen(rect)

        performClick(rect.centerX().toFloat(), rect.centerY().toFloat())
        node.recycle()
        root.recycle()
        return true
    }

    /**
     * 在参数值区域执行左右滑动来调整参数
     * 小米相机专业模式：点击参数后左右滑动调整值
     */
    fun adjustParamBySwipe(paramId: String, direction: SwipeDirection) {
        val root = rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByViewId(paramId)
        val node = nodes?.firstOrNull() ?: run {
            root.recycle()
            return
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        node.recycle()
        root.recycle()

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        val swipeDistance = 100f

        when (direction) {
            SwipeDirection.LEFT -> performSwipe(centerX + swipeDistance, centerY, centerX - swipeDistance, centerY)
            SwipeDirection.RIGHT -> performSwipe(centerX - swipeDistance, centerY, centerX + swipeDistance, centerY)
            SwipeDirection.UP -> performSwipe(centerX, centerY + swipeDistance, centerX, centerY - swipeDistance)
            SwipeDirection.DOWN -> performSwipe(centerX, centerY - swipeDistance, centerX, centerY + swipeDistance)
        }
    }

    /**
     * 执行 AI 构图指令
     */
    fun executeCommand(command: ComposeCommand) {
        when (command) {
            is ComposeCommand.ZoomIn -> {
                val scale = 1f + command.amount
                performPinch(scale)
            }
            is ComposeCommand.ZoomOut -> {
                val scale = 1f / (1f + command.amount)
                performPinch(scale)
            }
            is ComposeCommand.AdjustISO -> {
                adjustParamBySwipe(ID_ISO_VALUE, command.direction)
            }
            is ComposeCommand.AdjustShutter -> {
                adjustParamBySwipe(ID_SHUTTER_VALUE, command.direction)
            }
            is ComposeCommand.AdjustEV -> {
                adjustParamBySwipe(ID_EV_VALUE, command.direction)
            }
            is ComposeCommand.AdjustWB -> {
                adjustParamBySwipe(ID_WB_VALUE, command.direction)
            }
            is ComposeCommand.AdjustFocus -> {
                adjustParamBySwipe(ID_FOCUS_VALUE, command.direction)
            }
            is ComposeCommand.TapToFocus -> {
                performClick(command.x, command.y)
            }
            is ComposeCommand.Wait -> {
                // 等待由调用方处理
            }
        }
    }

    enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

    sealed class ComposeCommand {
        data class ZoomIn(val amount: Float) : ComposeCommand()      // amount: 0.5 = 50%放大
        data class ZoomOut(val amount: Float) : ComposeCommand()
        data class AdjustISO(val direction: SwipeDirection) : ComposeCommand()
        data class AdjustShutter(val direction: SwipeDirection) : ComposeCommand()
        data class AdjustEV(val direction: SwipeDirection) : ComposeCommand()
        data class AdjustWB(val direction: SwipeDirection) : ComposeCommand()
        data class AdjustFocus(val direction: SwipeDirection) : ComposeCommand()
        data class TapToFocus(val x: Float, val y: Float) : ComposeCommand()
        data class Wait(val millis: Long) : ComposeCommand()
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "无障碍服务已销毁")
    }
}
