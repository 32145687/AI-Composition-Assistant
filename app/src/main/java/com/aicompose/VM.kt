package com.aicompose

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aicompose.ai.CompositionEngine
import com.aicompose.ai.CompositionResult
import com.aicompose.ai.TFLiteScorer
import com.aicompose.ar.SceneTracker
import com.aicompose.gesture.GestureExecutor
import com.aicompose.overlay.OverlayManager
import com.aicompose.service.A11yService
import com.aicompose.service.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VM(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "VM"
        private const val ANALYSIS_INTERVAL_MS = 500L
    }

    private val ctx: Context = app.applicationContext
    private val tfliteScorer = TFLiteScorer(ctx)
    private val engine = CompositionEngine(tfliteScorer)
    private val sceneTracker = SceneTracker()
    private var launcher: ActivityResultLauncher<Intent>? = null

    // 累计场景运动
    private var totalMotionX = 0f; private var totalMotionY = 0f

    val a11y = MutableStateFlow(false)
    val capturing = MutableStateFlow(false)
    val analyzing = MutableStateFlow(false)
    val overlayPermission = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)
    val autoExec = MutableStateFlow(true)
    val result = MutableStateFlow<CompositionResult?>(null)
    val status = MutableStateFlow("就绪")
    val lastAction = MutableStateFlow<String?>(null)

    fun setLauncher(l: ActivityResultLauncher<Intent>) { launcher = l }
    fun checkA11y() { a11y.value = A11yService.instance != null }
    fun checkOverlayPerm() { overlayPermission.value = OverlayManager.hasPermission(ctx) }

    fun getAIStatus() = if (tfliteScorer.isAvailable) "🧠 TFLite + 算法混合" else "📊 纯算法"

    fun openA11ySettings() {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestCapture() {
        val l = launcher ?: run { status.value = "系统未就绪"; return }
        try {
            val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            l.launch(pm.createScreenCaptureIntent())
            status.value = "请允许屏幕捕获"
        } catch (e: Exception) { status.value = "请求失败: ${e.message}" }
    }

    fun onCaptureResult(code: Int, data: Intent) {
        CaptureService.resultCode = code; CaptureService.resultData = data
        try {
            val intent = Intent(ctx, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
            capturing.value = true; status.value = "屏幕捕获已启动"
            startAnalysis()
        } catch (e: Exception) { status.value = "启动失败: ${e.message}" }
    }

    private fun startAnalysis() {
        if (analyzing.value) return
        analyzing.value = true; status.value = "AI + AR 分析运行中..."

        // 分析循环
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive && analyzing.value) {
                delay(ANALYSIS_INTERVAL_MS)
                val bmp = CaptureService.frameQueue.poll() ?: continue
                try {
                    // 1. 构图分析
                    val r = engine.analyze(bmp)

                    // 2. 场景跟踪（用于 AR 跟随）
                    val smallW = 160; val smallH = (bmp.height.toFloat() / bmp.width * smallW).toInt()
                    val small = Bitmap.createScaledBitmap(bmp, smallW, smallH.coerceAtLeast(1), true)
                    val pixels = IntArray(smallW * smallH)
                    small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)
                    val motion = sceneTracker.trackFrame(pixels, smallW, smallH)
                    small.recycle()

                    // 3. 累计运动偏移
                    totalMotionX += motion.dx * 2f  // 放大系数，增强跟随感
                    totalMotionY += motion.dy * 2f
                    // 衰减，防止无限漂移
                    totalMotionX *= 0.95f; totalMotionY *= 0.95f

                    // 4. 更新叠加层
                    result.value = r
                    OverlayManager.update(r)
                    OverlayManager.updateMotion(totalMotionX, totalMotionY)

                    // 5. 自动调整
                    if (autoExec.value) autoAdjust(r)
                } catch (e: Exception) { Log.e(TAG, "分析异常", e) }
                finally { bmp.recycle() }
            }
        }

        viewModelScope.launch { GestureExecutor.lastAction.collect { lastAction.value = it } }
    }

    private fun autoAdjust(r: CompositionResult) {
        val s = r.subject ?: return
        val area = (s.right - s.left) * (s.bottom - s.top)
        if (area < 0.1f && r.rules[0].score < 0.5f) GestureExecutor.execute("zoom_in:0.4")
        if (area > 0.65f && r.rules[0].score < 0.5f) GestureExecutor.execute("zoom_out:0.3")
    }

    fun toggleOverlay() {
        if (overlayVisible.value) {
            OverlayManager.hide()
            overlayVisible.value = false
        } else {
            // 检查权限
            if (!OverlayManager.hasPermission(ctx)) {
                status.value = "需要悬浮窗权限，请在设置中授予"
                // 跳转到权限设置
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + ctx.packageName))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {}
                return
            }
            OverlayManager.show(ctx)
            overlayVisible.value = true
        }
    }

    fun cycleGuide() { OverlayManager.cycleGuide() }
    fun toggleAutoExec() { autoExec.value = !autoExec.value; GestureExecutor.autoExecute = autoExec.value }
    fun stopAnalysis() { analyzing.value = false; status.value = "AI 分析已停止" }
    fun getStats() = GestureExecutor.getStats()

    override fun onCleared() {
        super.onCleared()
        analyzing.value = false; OverlayManager.hide(); GestureExecutor.destroy()
        tfliteScorer.close()
        try { ctx.stopService(Intent(ctx, CaptureService::class.java)) } catch (_: Exception) {}
    }
}
