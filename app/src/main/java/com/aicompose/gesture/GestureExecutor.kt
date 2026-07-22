package com.aicompose.gesture

import android.util.Log
import com.aicompose.ai.CompositionEngine
import com.aicompose.service.ComposeAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 手势执行器
 * 接收 AI 分析指令，通过无障碍服务执行手势，带节流和重试
 */
class GestureExecutor {

    companion object {
        private const val TAG = "GestureExecutor"
        private const val MIN_COMMAND_INTERVAL_MS = 1500L
        private const val GESTURE_TIMEOUT_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(ExecutionState.IDLE)
    val state: StateFlow<ExecutionState> = _state.asStateFlow()

    private val _lastAction = MutableStateFlow<String?>(null)
    val lastAction: StateFlow<String?> = _lastAction.asStateFlow()

    // 用 AtomicLong 避免跨线程读写问题
    private val totalExecutions = AtomicLong(0)
    private val lastExecutionTime = AtomicLong(0L)

    // 当前手势的 CompletableDeferred，修复回调竞态
    private var currentGestureDeferred: CompletableDeferred<Boolean>? = null

    enum class ExecutionState {
        IDLE, EXECUTING, COOLDOWN, DISABLED
    }

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
        if (now - lastExecutionTime.get() < MIN_COMMAND_INTERVAL_MS) {
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

                    // 为每次手势创建独立的 CompletableDeferred，避免竞态
                    val deferred = CompletableDeferred<Boolean>()
                    currentGestureDeferred = deferred

                    // 设置回调，将结果通知到 deferred
                    service.onGestureCompleted = { success ->
                        deferred.complete(success)
                    }

                    // 执行手势
                    service.executeCommand(command)

                    // 带超时等待手势完成
                    val completed = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                        deferred.await()
                    } ?: false

                    currentGestureDeferred = null

                    if (!completed) {
                        Log.w(TAG, "手势执行失败或超时: ${command.describe()}")
                    }

                    // 指令间间隔
                    delay(300)

                } catch (e: CancellationException) {
                    Log.d(TAG, "指令被取消: ${command.describe()}")
                    throw e  // CancellationException 必须重新抛出
                } catch (e: Exception) {
                    Log.e(TAG, "指令执行异常: ${command.describe()}", e)
                }
            }

            lastExecutionTime.set(System.currentTimeMillis())
            totalExecutions.incrementAndGet()
            _state.value = ExecutionState.IDLE

            // 3秒后清除动作提示
            delay(3000)
            if (_state.value == ExecutionState.IDLE) {
                _lastAction.value = null
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = if (enabled) ExecutionState.IDLE else ExecutionState.DISABLED
    }

    fun isEnabled(): Boolean = _state.value != ExecutionState.DISABLED

    fun getStats(): String = "已执行 ${totalExecutions.get()} 次调整"

    fun destroy() {
        currentGestureDeferred?.cancel()
        scope.cancel()
    }

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
