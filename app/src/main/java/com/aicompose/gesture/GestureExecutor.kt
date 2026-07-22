package com.aicompose.gesture

import android.util.Log
import com.aicompose.service.A11yService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 指令 → 手势执行
 */
object GestureExecutor {
    private const val TAG = "Gesture"
    private const val COOLDOWN_MS = 2000L

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _lastAction = MutableStateFlow<String?>(null)
    val lastAction: StateFlow<String?> = _lastAction.asStateFlow()
    private val execCount = AtomicLong(0)
    private val lastExecTime = AtomicLong(0L)

    var autoExecute = true

    fun execute(command: String) {
        val svc = A11yService.instance ?: return
        if (!autoExecute) return
        val now = System.currentTimeMillis()
        if (now - lastExecTime.get() < COOLDOWN_MS) return

        scope.launch {
            _lastAction.value = command
            try {
                when {
                    command.startsWith("zoom_in:") -> {
                        val amount = command.removePrefix("zoom_in:").toFloatOrNull() ?: 0.3f
                        svc.pinch(1f + amount)
                    }
                    command.startsWith("zoom_out:") -> {
                        val amount = command.removePrefix("zoom_out:").toFloatOrNull() ?: 0.3f
                        svc.pinch(1f / (1f + amount))
                    }
                    command.startsWith("adjust:") -> {
                        val parts = command.removePrefix("adjust:").split(":")
                        if (parts.size == 2) svc.adjustParam(parts[0], parts[1].toIntOrNull() ?: 1)
                    }
                }
                // 等待手势完成
                val future = CompletableFuture<Boolean>()
                svc.setGestureCallback { future.complete(it) }
                future.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "手势异常: $command", e)
            }
            execCount.incrementAndGet()
            lastExecTime.set(System.currentTimeMillis())
            delay(3000)
            if (_lastAction.value == command) _lastAction.value = null
        }
    }

    fun getStats() = "已调整 ${execCount.get()} 次"

    fun destroy() { scope.cancel() }
}
