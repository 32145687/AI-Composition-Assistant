package com.aicompose.gesture

import android.util.Log
import com.aicompose.ai.CompositionEngine
import com.aicompose.service.ComposeAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手势执行器
 * 接收 AI 分析指令，通过无障碍服务执行手势，带节流和重试
 */
class GestureExecutor {

    companion object {
        private const val TAG = "GestureExecutor"
        private const val MIN_COMMAND_INTERVAL_MS = 1500L  // 最小指令间隔
        private const val MAX_RETRY = 2
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 执行状态
    private val _state = MutableStateFlow(ExecutionState.IDLE)
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    // 最近执行的指令
    private val _lastAction = MutableStateFlow<String?>(null)
    val lastAction: StateFlow<String?> = _lastAction.asStateFlow()

    // 执行统计
    private var totalExecutions = 0
    private var lastExecutionTime = 0L

    enum class ExecutionState {
        IDLE,       // 空闲
        EXECUTING,  // 执行中
        COOLDOWN,   // 冷却中
        DISABLED    // 已禁用
    }

    /**
     * 执行 AI 分析结果中的指令
     */
    fun executeCommands(result: CompositionEngine.AnalysisResult) {
        val service = ComposeAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "无障碍服务未连接")
            return
        }

        if (_state.value == ExecutionState.DISABLED) return

        val commands = result.commands
        if (commands.isEmpty()) return

        // 节流检查
        val now = System.currentTimeMillis()
        if (now - lastExecutionTime < MIN_COMMAND_INTERVAL_MS) {
            Log.d(TAG, "冷却中，跳过执行")
            _state.value = ExecutionState.COOLDOWN
            return
        }

        scope.launch {
            _state.value = ExecutionState.EXECUTING

            for (command in commands) {
                try {
                    Log.d(TAG, "执行指令: ${command.describe()}")
                    _lastAction.value = command.describe()

                    service.executeCommand(command)

                    // 等待手势完成
                    withContext(Dispatchers.Default) {
                        val completed = suspendCancellableCoroutine<Boolean> { cont ->
                            service.onGestureCompleted = { success ->
                                cont.resume(success) {}
                            }
                            // 超时保护
                            scope.launch {
                                delay(2000)
                                if (cont.isActive) cont.resume(false) {}
                            }
                        }

                        if (!completed) {
                            Log.w(TAG, "手势执行失败: ${command.describe()}")
                        }
                    }

                    // 指令间间隔
                    delay(300)

                } catch (e: Exception) {
                    Log.e(TAG, "指令执行异常: ${command.describe()}", e)
                }
            }

            lastExecutionTime = System.currentTimeMillis()
            totalExecutions++
            _state.value = ExecutionState.IDLE

            // 3秒后清除动作提示
            delay(3000)
            if (_state.value == ExecutionState.IDLE) {
                _lastAction.value = null
            }
        }
    }

    /**
     * 禁用/启用自动执行
     */
    fun setEnabled(enabled: Boolean) {
        _state.value = if (enabled) ExecutionState.IDLE else ExecutionState.DISABLED
    }

    fun isEnabled(): Boolean = _state.value != ExecutionState.DISABLED

    fun getStats(): String = "已执行 $totalExecutions 次调整"

    fun destroy() {
        scope.cancel()
    }

    /**
     * 指令描述（用于 UI 显示）
     */
    private fun ComposeAccessibilityService.ComposeCommand.describe(): String {
        return when (this) {
            is ComposeAccessibilityService.ComposeCommand.ZoomIn ->
                "🔍 放大 ${String.format("%.0f", amount * 100)}%"
            is ComposeAccessibilityService.ComposeCommand.ZoomOut ->
                "🔍 缩小 ${String.format("%.0f", amount * 100)}%"
            is ComposeAccessibilityService.ComposeCommand.AdjustISO ->
                "📊 调整ISO ${direction.name}"
            is ComposeAccessibilityService.ComposeCommand.AdjustShutter ->
                "⏱️ 调整快门 ${direction.name}"
            is ComposeAccessibilityService.ComposeCommand.AdjustEV ->
                "💡 调整曝光 ${direction.name}"
            is ComposeAccessibilityService.ComposeCommand.AdjustWB ->
                "🌡️ 调整白平衡 ${direction.name}"
            is ComposeAccessibilityService.ComposeCommand.AdjustFocus ->
                "🎯 调整对焦 ${direction.name}"
            is ComposeAccessibilityService.ComposeCommand.TapToFocus ->
                "👆 点击对焦 (${x.toInt()}, ${y.toInt()})"
            is ComposeAccessibilityService.ComposeCommand.Wait ->
                "⏳ 等待 ${millis}ms"
        }
    }
}
