package com.aicompose

import android.app.Application
import android.content.Context
import android.content.Intent
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
        private const val ANALYSIS_INTERVAL_MS = 600L
    }

    private val ctx: Context = app.applicationContext
    private val tfliteScorer = TFLiteScorer(ctx)
    private val engine = CompositionEngine(tfliteScorer)
    private var launcher: ActivityResultLauncher<Intent>? = null

    // 状态
    val a11y = MutableStateFlow(false)
    val capturing = MutableStateFlow(false)
    val analyzing = MutableStateFlow(false)
    val overlayVisible = MutableStateFlow(false)
    val autoExec = MutableStateFlow(true)
    val result = MutableStateFlow<CompositionResult?>(null)
    val status = MutableStateFlow("就绪")
    val lastAction = MutableStateFlow<String?>(null)

    fun setLauncher(l: ActivityResultLauncher<Intent>) { launcher = l }

    fun checkA11y() { a11y.value = A11yService.instance != null }

    fun getAIStatus(): String {
        return if (tfliteScorer.isAvailable) "🧠 TFLite 深度学习 + 算法混合" else "📊 纯算法分析（放入模型文件启用DL）"
    }

    fun openA11ySettings() {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 请求屏幕捕获权限
     */
    fun requestCapture() {
        val l = launcher
        if (l == null) { status.value = "系统未就绪，请稍后"; return }
        try {
            val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            l.launch(pm.createScreenCaptureIntent())
            status.value = "请在弹窗中允许屏幕捕获"
        } catch (e: Exception) {
            Log.e(TAG, "请求屏幕捕获失败", e)
            status.value = "请求权限失败: ${e.message}"
        }
    }

    /**
     * 屏幕捕获权限回调
     */
    fun onCaptureResult(code: Int, data: Intent) {
        Log.d(TAG, "屏幕捕获权限获取成功")
        CaptureService.resultCode = code
        CaptureService.resultData = data

        try {
            val intent = Intent(ctx, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            capturing.value = true
            status.value = "屏幕捕获已启动"
            // 自动启动 AI 分析
            startAnalysis()
        } catch (e: Exception) {
            Log.e(TAG, "启动捕获服务失败", e)
            status.value = "启动失败: ${e.message}"
        }
    }

    private fun startAnalysis() {
        if (analyzing.value) return
        analyzing.value = true
        status.value = "AI 分析运行中..."

        // 分析循环
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive && analyzing.value) {
                delay(ANALYSIS_INTERVAL_MS)
                val bmp = CaptureService.frameQueue.poll()
                if (bmp != null) {
                    try {
                        val r = engine.analyze(bmp)
                        result.value = r
                        // 更新叠加层
                        OverlayManager.update(r)
                        // 自动执行调整
                        if (autoExec.value) autoAdjust(r)
                    } catch (e: Exception) {
                        Log.e(TAG, "分析异常", e)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }

        // 手势状态
        viewModelScope.launch {
            GestureExecutor.lastAction.collect { lastAction.value = it }
        }
    }

    private fun autoAdjust(r: CompositionResult) {
        val s = r.subject ?: return
        val area = (s.right - s.left) * (s.bottom - s.top)
        // 主体太小 → 放大
        if (area < 0.1f && r.rules[0].score < 0.5f) {
            GestureExecutor.execute("zoom_in:0.4")
        }
        // 主体太大 → 缩小
        if (area > 0.65f && r.rules[0].score < 0.5f) {
            GestureExecutor.execute("zoom_out:0.3")
        }
    }

    fun toggleOverlay() {
        if (overlayVisible.value) {
            OverlayManager.hide()
            overlayVisible.value = false
        } else {
            OverlayManager.show(ctx)
            overlayVisible.value = true
        }
    }

    fun cycleGuide() { OverlayManager.cycleGuide() }

    fun toggleAutoExec() {
        autoExec.value = !autoExec.value
        GestureExecutor.autoExecute = autoExec.value
    }

    fun stopAnalysis() {
        analyzing.value = false
        status.value = "AI 分析已停止"
    }

    fun getStats() = GestureExecutor.getStats()

    override fun onCleared() {
        super.onCleared()
        analyzing.value = false
        OverlayManager.hide()
        GestureExecutor.destroy()
        try { ctx.stopService(Intent(ctx, CaptureService::class.java)) } catch (_: Exception) {}
    }
}
