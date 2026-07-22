package com.aicompose.service

import android.util.Log
import com.aicompose.ai.CompositionEngine
import com.aicompose.gesture.GestureExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * AI 分析服务
 * 从屏幕捕获流消费帧，送入构图引擎，将指令转发给手势执行器
 */
class AIAnalysisService {

    companion object {
        private const val TAG = "AIAnalysisService"
        private const val ANALYSIS_INTERVAL_MS = 500L  // 每500ms分析一帧
    }

    private val engine = CompositionEngine()
    private val gestureExecutor = GestureExecutor()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 最新分析结果
    private val _latestResult = MutableStateFlow<CompositionEngine.AnalysisResult?>(null)
    val latestResult: StateFlow<CompositionEngine.AnalysisResult?> = _latestResult.asStateFlow()

    // 运行状态
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 分析统计
    private var frameCount = 0
    private var totalAnalysisTime = 0L

    /**
     * 启动分析
     */
    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true

        Log.d(TAG, "AI 分析服务启动")

        scope.launch {
            // 从屏幕捕获流消费帧
            ScreenCaptureService.frameFlow
                .conflate()  // 丢弃来不及处理的帧
                .collect { bitmap ->
                    if (!_isRunning.value) return@collect

                    // 限流
                    delay(ANALYSIS_INTERVAL_MS)

                    try {
                        // 分析帧
                        val result = engine.analyzeFrame(bitmap)
                        _latestResult.value = result
                        frameCount++
                        totalAnalysisTime += result.analysisTimeMs

                        // 如果有需要执行的指令，交给手势执行器
                        if (result.commands.isNotEmpty() && gestureExecutor.isEnabled()) {
                            gestureExecutor.executeCommands(result)
                        }

                        // 回收 bitmap
                        bitmap.recycle()

                    } catch (e: Exception) {
                        Log.e(TAG, "帧分析失败", e)
                        bitmap.recycle()
                    }
                }
        }
    }

    fun stop() {
        _isRunning.value = false
        Log.d(TAG, "AI 分析服务停止")
    }

    fun toggleAutoExecute() {
        gestureExecutor.setEnabled(!gestureExecutor.isEnabled())
    }

    fun isAutoExecuteEnabled(): Boolean = gestureExecutor.isEnabled()

    fun getStats(): String {
        val avgTime = if (frameCount > 0) totalAnalysisTime / frameCount else 0
        return "已分析 $frameCount 帧 | 平均 ${avgTime}ms | ${gestureExecutor.getStats()}"
    }

    fun getGestureExecutor(): GestureExecutor = gestureExecutor

    fun destroy() {
        stop()
        scope.cancel()
        gestureExecutor.destroy()
    }
}
