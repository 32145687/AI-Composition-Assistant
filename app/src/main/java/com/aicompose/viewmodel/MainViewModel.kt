package com.aicompose.viewmodel

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
import com.aicompose.overlay.ComposeOverlayManager
import com.aicompose.service.AIAnalysisService
import com.aicompose.service.ComposeAccessibilityService
import com.aicompose.service.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val context: Context = application.applicationContext

    // Activity 传入的 launcher（必须在 setContent 之后设置）
    private var screenCaptureLauncher: ActivityResultLauncher<Intent>? = null

    // 服务状态
    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _captureRunning = MutableStateFlow(false)
    val captureRunning: StateFlow<Boolean> = _captureRunning.asStateFlow()

    private val _aiRunning = MutableStateFlow(false)
    val aiRunning: StateFlow<Boolean> = _aiRunning.asStateFlow()

    private val _overlayVisible = MutableStateFlow(false)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val _autoExecute = MutableStateFlow(true)
    val autoExecute: StateFlow<Boolean> = _autoExecute.asStateFlow()

    // AI 分析结果
    private val _analysisResult = MutableStateFlow<com.aicompose.ai.CompositionEngine.AnalysisResult?>(null)
    val analysisResult: StateFlow<com.aicompose.ai.CompositionEngine.AnalysisResult?> = _analysisResult.asStateFlow()

    // 状态消息
    private val _statusMessage = MutableStateFlow("就绪")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // AI 分析服务
    private var aiService: AIAnalysisService? = null

    init {
        checkAccessibilityStatus()
    }

    /**
     * Activity 注入 launcher（必须调用）
     */
    fun setScreenCaptureLauncher(launcher: ActivityResultLauncher<Intent>) {
        screenCaptureLauncher = launcher
    }

    /**
     * 检查无障碍服务是否启用
     */
    fun checkAccessibilityStatus() {
        _accessibilityEnabled.value = ComposeAccessibilityService.instance != null
    }

    /**
     * 打开无障碍设置页面
     */
    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 请求屏幕捕获权限（通过 Activity launcher）
     */
    fun requestScreenCapture() {
        val launcher = screenCaptureLauncher
        if (launcher == null) {
            _statusMessage.value = "系统未就绪，请稍后重试"
            Log.e(TAG, "screenCaptureLauncher 为 null，Activity 可能还没初始化")
            return
        }

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(projectionManager.createScreenCaptureIntent())
        _statusMessage.value = "请在弹窗中允许屏幕捕获"
    }

    /**
     * 屏幕捕获权限回调（由 Activity 调用）
     */
    fun onScreenCaptureResult(resultCode: Int, data: Intent) {
        Log.d(TAG, "屏幕捕获权限已获取, resultCode=$resultCode")

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)

        if (projection == null) {
            _statusMessage.value = "获取 MediaProjection 失败"
            return
        }

        ScreenCaptureService.setProjection(projection)

        // 启动前台服务
        val intent = Intent(context, ScreenCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        _captureRunning.value = true
        _statusMessage.value = "屏幕捕获已启动"

        // 自动启动 AI 分析
        startAIAnalysis()
    }

    /**
     * 启动 AI 分析
     */
    fun startAIAnalysis() {
        if (aiService == null) {
            aiService = AIAnalysisService()
        }

        aiService?.start()
        _aiRunning.value = true

        // 收集分析结果
        viewModelScope.launch {
            aiService?.latestResult?.collect { result ->
                _analysisResult.value = result
            }
        }

        _statusMessage.value = "AI 分析已启动"
    }

    /**
     * 停止 AI 分析
     */
    fun stopAIAnalysis() {
        aiService?.stop()
        _aiRunning.value = false
        _statusMessage.value = "AI 分析已停止"
    }

    /**
     * 显示/隐藏叠加层
     */
    fun toggleOverlay() {
        val overlayManager = ComposeOverlayManager.getInstance(context)
        if (_overlayVisible.value) {
            overlayManager.hide()
            _overlayVisible.value = false
        } else {
            overlayManager.show()
            _overlayVisible.value = true
        }
    }

    /**
     * 切换自动执行
     */
    fun toggleAutoExecute() {
        _autoExecute.value = !_autoExecute.value
        aiService?.toggleAutoExecute()
    }

    /**
     * 获取统计信息
     */
    fun getStats(): String {
        return aiService?.getStats() ?: "未启动"
    }

    override fun onCleared() {
        super.onCleared()
        aiService?.destroy()
        ComposeOverlayManager.getInstance(context).hide()
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }
}
