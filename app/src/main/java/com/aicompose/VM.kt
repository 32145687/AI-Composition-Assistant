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
import com.aicompose.gesture.GestureExecutor
import com.aicompose.overlay.OverlayManager
import com.aicompose.service.A11yService
import com.aicompose.service.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VM(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "VM"
        private const val ANALYSIS_INTERVAL_MS = 800L
    }

    private val ctx: Context = app.applicationContext
    private val engine = CompositionEngine()

    // Activity 的 launcher
    private var launcher: ActivityResultLauncher<Intent>? = null

    // 状态
    val a11y = MutableStateFlow(false)
    val capturing = MutableStateFlow(false)
    val analyzing = MutableStateFlow(false)
    val overlay = MutableStateFlow(false)
    val autoExec = MutableStateFlow(true)
    val result = MutableStateFlow<CompositionResult?>(null)
    val status = MutableStateFlow("就绪")
    val lastAction = MutableStateFlow<String?>(null)

    fun setLauncher(l: ActivityResultLauncher<Intent>) { launcher = l }

    fun checkA11y() { a11y.value = A11yService.instance != null }

    fun openA11ySettings() {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestCapture() {
        val l = launcher
        if (l == null) { status.value = "系统未就绪"; return }
        val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        l.launch(pm.createScreenCaptureIntent())
        status.value = "请允许屏幕捕获"
    }

    fun onCaptureResult(code: Int, data: Intent) {
        CaptureService.resultCode = code
        CaptureService.resultData = data
        val intent = Intent(ctx, CaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
        else ctx.startService(intent)
        capturing.value = true
        status.value = "屏幕捕获已启动"
        startAnalysis()
    }

    private fun startAnalysis() {
        if (analyzing.value) return
        analyzing.value = true
        status.value = "AI 分析已启动"

        viewModelScope.launch {
            while (isActive && analyzing.value) {
                delay(ANALYSIS_INTERVAL_MS)
                val bmp = CaptureService.frameQueue.poll()
                if (bmp != null) {
                    try {
                        val r = withContext(Dispatchers.Default) { engine.analyze(bmp) }
                        result.value = r
                        OverlayManager.update(r)

                        // 自动生成调整指令
                        if (autoExec.value && r.suggestions.isNotEmpty()) {
                            generateCommands(r)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "分析异常", e)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }

        // 收集手势执行状态
        viewModelScope.launch {
            GestureExecutor.lastAction.collect { lastAction.value = it }
        }
    }

    private fun generateCommands(r: CompositionResult) {
        val subject = r.subject ?: return
        val cx = (subject.left + subject.right) / 2
        val cy = (subject.top + subject.bottom) / 2
        val area = (subject.right - subject.left) * (subject.bottom - subject.top)

        // 三分法不达标 → 缩放调整
        val thirds = r.rules.firstOrNull { it.name == "三分法" }
        if (thirds != null && thirds.score < 0.6f) {
            if (area < 0.15f) GestureExecutor.execute("zoom_in:0.4")
            else if (area > 0.6f) GestureExecutor.execute("zoom_out:0.3")
        }
    }

    fun toggleOverlay() {
        if (overlay.value) { OverlayManager.hide(); overlay.value = false }
        else { OverlayManager.show(ctx); overlay.value = true }
    }

    fun cycleGuide() { OverlayManager.cycleGuide() }

    fun toggleAutoExec() {
        autoExec.value = !autoExec.value
        GestureExecutor.autoExecute = autoExec.value
    }

    fun getStats() = GestureExecutor.getStats()

    override fun onCleared() {
        super.onCleared()
        OverlayManager.hide()
        GestureExecutor.destroy()
        ctx.stopService(Intent(ctx, CaptureService::class.java))
    }
}
