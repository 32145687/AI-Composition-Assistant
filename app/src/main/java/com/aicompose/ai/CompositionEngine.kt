package com.aicompose.ai

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.aicompose.service.ComposeAccessibilityService
import com.aicompose.service.ComposeAccessibilityService.SwipeDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AI 构图分析引擎
 *
 * 分析屏幕捕获的画面，输出:
 * 1. 构图评分 (0-100)
 * 2. 检测到的构图法则
 * 3. 需要执行的调整指令列表
 */
class CompositionEngine {

    companion object {
        private const val TAG = "ComposeEngine"

        // 目标构图参数
        private const val TARGET_SCORE = 75f  // 达到此分数认为构图合格
        private const val SCORE_THRESHOLD = 10f // 分数变化阈值才触发调整

        // 构图权重
        private val WEIGHTS = mapOf(
            "thirds" to 0.25f,
            "golden" to 0.15f,
            "symmetry" to 0.10f,
            "leading" to 0.12f,
            "balance" to 0.10f,
            "depth" to 0.08f,
            "simplicity" to 0.10f,
            "horizon" to 0.10f
        )
    }

    // 上一次分析结果
    private var lastScore = 0f
    private var lastCommands = listOf<ComposeAccessibilityService.ComposeCommand>()
    private var stableFrameCount = 0  // 连续稳定帧数

    /**
     * 分析一帧画面，返回调整指令
     */
    fun analyzeFrame(bitmap: Bitmap): AnalysisResult {
        val startTime = System.currentTimeMillis()

        // 1. 图像预处理
        val resized = resizeForAnalysis(bitmap, 320)
        val pixels = getPixels(resized)

        // 2. 计算各项构图指标
        val thirdsScore = detectRuleOfThirds(pixels, resized.width, resized.height)
        val goldenScore = detectGoldenRatio(pixels, resized.width, resized.height)
        val symmetryScore = detectSymmetry(pixels, resized.width, resized.height)
        val leadingScore = detectLeadingLines(pixels, resized.width, resized.height)
        val balanceScore = detectVisualBalance(pixels, resized.width, resized.height)
        val depthScore = detectDepth(pixels, resized.width, resized.height)
        val simplicityScore = detectSimplicity(pixels, resized.width, resized.height)
        val horizonScore = detectHorizon(pixels, resized.width, resized.height)

        // 3. 计算加权总分
        val scores = mapOf(
            "thirds" to thirdsScore,
            "golden" to goldenScore,
            "symmetry" to symmetryScore,
            "leading" to leadingScore,
            "balance" to balanceScore,
            "depth" to depthScore,
            "simplicity" to simplicityScore,
            "horizon" to horizonScore
        )

        val totalScore = scores.entries.sumOf { (key, value) ->
            (value * (WEIGHTS[key] ?: 0f)).toDouble()
        }.toFloat() * 100f

        // 4. 检测主体位置
        val subject = detectSubject(pixels, resized.width, resized.height)

        // 5. 生成调整指令
        val commands = generateCommands(scores, subject, resized.width, resized.height)

        // 6. 稳定性过滤 - 连续3帧相似才执行
        val scoreDelta = abs(totalScore - lastScore)
        if (scoreDelta < SCORE_THRESHOLD) {
            stableFrameCount++
        } else {
            stableFrameCount = 0
        }

        val shouldExecute = stableFrameCount >= 3 && totalScore < TARGET_SCORE
        val finalCommands = if (shouldExecute) commands else emptyList()

        if (shouldExecute && finalCommands.isNotEmpty()) {
            lastCommands = finalCommands
        }
        lastScore = totalScore

        resized.recycle()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "分析完成: score=${String.format("%.1f", totalScore)}, " +
                "commands=${finalCommands.size}, time=${elapsed}ms")

        return AnalysisResult(
            score = totalScore,
            scores = scores,
            subject = subject,
            commands = if (shouldExecute) finalCommands else emptyList(),
            shouldExecute = shouldExecute,
            analysisTimeMs = elapsed
        )
    }

    // ==================== 构图法则检测 ====================

    /**
     * 三分法检测 - 检查主体是否在三分线交叉点附近
     */
    private fun detectRuleOfThirds(pixels: IntArray, w: Int, h: Int): Float {
        val subject = detectSubject(pixels, w, h) ?: return 0.5f

        val cx = (subject.left + subject.right) / 2
        val cy = (subject.top + subject.bottom) / 2

        // 三分线交叉点
        val thirdPoints = listOf(
            Pair(1f / 3, 1f / 3), Pair(2f / 3, 1f / 3),
            Pair(1f / 3, 2f / 3), Pair(2f / 3, 2f / 3)
        )

        val minDist = thirdPoints.minOf { (tx, ty) ->
            val dx = cx - tx
            val dy = cy - ty
            sqrt(dx * dx + dy * dy)
        }

        // 距离越近分数越高，最大容差 0.2
        return (1f - minDist / 0.2f).coerceIn(0f, 1f)
    }

    /**
     * 黄金比例检测
     */
    private fun detectGoldenRatio(pixels: IntArray, w: Int, h: Int): Float {
        val subject = detectSubject(pixels, w, h) ?: return 0.5f

        val cx = (subject.left + subject.right) / 2
        val cy = (subject.top + subject.bottom) / 2

        val goldenPoints = listOf(
            Pair(0.382f, 0.382f), Pair(0.618f, 0.382f),
            Pair(0.382f, 0.618f), Pair(0.618f, 0.618f)
        )

        val minDist = goldenPoints.minOf { (gx, gy) ->
            val dx = cx - gx
            val dy = cy - gy
            sqrt(dx * dx + dy * dy)
        }

        return (1f - minDist / 0.15f).coerceIn(0f, 1f)
    }

    /**
     * 对称性检测 - 左右镜像对比
     */
    private fun detectSymmetry(pixels: IntArray, w: Int, h: Int): Float {
        var diffSum = 0L
        var count = 0

        for (y in 0 until h) {
            for (x in 0 until w / 2) {
                val leftIdx = y * w + x
                val rightIdx = y * w + (w - 1 - x)

                val lR = (pixels[leftIdx] shr 16) and 0xFF
                val lG = (pixels[leftIdx] shr 8) and 0xFF
                val lB = pixels[leftIdx] and 0xFF

                val rR = (pixels[rightIdx] shr 16) and 0xFF
                val rG = (pixels[rightIdx] shr 8) and 0xFF
                val rB = pixels[rightIdx] and 0xFF

                diffSum += abs(lR - rR) + abs(lG - rG) + abs(lB - rB)
                count++
            }
        }

        val avgDiff = diffSum.toFloat() / (count * 3 * 255)
        return (1f - avgDiff * 3).coerceIn(0f, 1f)
    }

    /**
     * 引导线检测 - 使用 Sobel 边缘检测 + 霍夫变换简化版
     */
    private fun detectLeadingLines(pixels: IntArray, w: Int, h: Int): Float {
        // Sobel 边缘检测
        var edgeCount = 0
        var strongEdgeCount = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = sobelX(pixels, x, y, w)
                val gy = sobelY(pixels, x, y, w)
                val magnitude = sqrt((gx * gx + gy * gy).toFloat())

                if (magnitude > 30) edgeCount++
                if (magnitude > 80) strongEdgeCount++
            }
        }

        val edgeRatio = edgeCount.toFloat() / (w * h)
        val strongRatio = strongEdgeCount.toFloat() / (w * h)

        // 引导线存在时，边缘比例适中（不太少也不太多）
        return when {
            edgeRatio in 0.05f..0.25f && strongRatio > 0.02f -> 0.8f
            edgeRatio in 0.03f..0.35f -> 0.5f
            else -> 0.3f
        }
    }

    /**
     * 视觉平衡检测 - 四象限权重分布
     */
    private fun detectVisualBalance(pixels: IntArray, w: Int, h: Int): Float {
        val quadrants = LongArray(4)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val r = (pixels[idx] shr 16) and 0xFF
                val g = (pixels[idx] shr 8) and 0xFF
                val b = pixels[idx] and 0xFF
                val brightness = (r + g + b) / 3L

                val qi = if (y < h / 2) {
                    if (x < w / 2) 0 else 1
                } else {
                    if (x < w / 2) 2 else 3
                }
                quadrants[qi] += brightness
            }
        }

        val total = quadrants.sum().toFloat()
        if (total == 0f) return 0.5f

        val ratios = quadrants.map { it / total }
        val maxDeviation = ratios.maxOf { abs(it - 0.25f) }

        return (1f - maxDeviation * 4).coerceIn(0f, 1f)
    }

    /**
     * 纵深感检测 - 上下区域亮度对比
     */
    private fun detectDepth(pixels: IntArray, w: Int, h: Int): Float {
        var topBrightness = 0L
        var bottomBrightness = 0L
        val halfH = h / 2
        val count = w * halfH

        for (y in 0 until halfH) {
            for (x in 0 until w) {
                val idx = y * w + x
                val r = (pixels[idx] shr 16) and 0xFF
                val g = (pixels[idx] shr 8) and 0xFF
                val b = pixels[idx] and 0xFF
                topBrightness += (r + g + b) / 3
            }
        }

        for (y in halfH until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val r = (pixels[idx] shr 16) and 0xFF
                val g = (pixels[idx] shr 8) and 0xFF
                val b = pixels[idx] and 0xFF
                bottomBrightness += (r + g + b) / 3
            }
        }

        val contrast = abs(topBrightness - bottomBrightness).toFloat() / (count * 255)
        return contrast.coerceIn(0f, 1f)
    }

    /**
     * 简洁性检测 - 颜色种类越少越简洁
     */
    private fun detectSimplicity(pixels: IntArray, w: Int, h: Int): Float {
        val colorBuckets = mutableMapOf<Int, Int>()

        for (pixel in pixels) {
            // 量化到 8x8x8 色块
            val r = ((pixel shr 16) and 0xFF) / 32
            val g = ((pixel shr 8) and 0xFF) / 32
            val b = (pixel and 0xFF) / 32
            val key = (r shl 6) or (g shl 3) or b
            colorBuckets[key] = (colorBuckets[key] ?: 0) + 1
        }

        val uniqueColors = colorBuckets.size
        val maxColors = 512 // 8x8x8

        // 颜色种类越少，越简洁
        return (1f - uniqueColors.toFloat() / maxColors).coerceIn(0.2f, 1f)
    }

    /**
     * 地平线水平检测
     */
    private fun detectHorizon(pixels: IntArray, w: Int, h: Int): Float {
        // 检测水平边缘线
        var horizontalEdges = 0
        var totalEdges = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = abs(sobelX(pixels, x, y, w))
                val gy = abs(sobelY(pixels, x, y, w))

                if (gx + gy > 50) {
                    totalEdges++
                    if (gy > gx * 2) horizontalEdges++ // 水平边缘
                }
            }
        }

        return if (totalEdges > 0) {
            (horizontalEdges.toFloat() / totalEdges).coerceIn(0f, 1f)
        } else {
            0.5f
        }
    }

    // ==================== 主体检测 ====================

    /**
     * 简单主体检测 - 基于颜色对比度找到视觉重心
     */
    private fun detectSubject(pixels: IntArray, w: Int, h: Int): RectF? {
        // 计算每个区域的视觉权重（对比度 × 饱和度）
        val blockSize = 16
        val gridW = w / blockSize
        val gridH = h / blockSize
        var maxWeight = 0f
        var maxGX = 0
        var maxGY = 0

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                var weight = 0f
                for (by in 0 until blockSize) {
                    for (bx in 0 until blockSize) {
                        val x = gx * blockSize + bx
                        val y = gy * blockSize + by
                        if (x >= w || y >= h) continue

                        val idx = y * w + x
                        val r = (pixels[idx] shr 16) and 0xFF
                        val g = (pixels[idx] shr 8) and 0xFF
                        val b = pixels[idx] and 0xFF

                        // 饱和度作为权重
                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        val saturation = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f

                        // 亮度对比
                        val brightness = (r + g + b) / 3f / 255f
                        val contrast = abs(brightness - 0.5f) * 2

                        weight += saturation * 0.6f + contrast * 0.4f
                    }
                }

                if (weight > maxWeight) {
                    maxWeight = weight
                    maxGX = gx
                    maxGY = gy
                }
            }
        }

        if (maxWeight < 0.1f) return null

        // 扩展到包含周围相似块
        val subjectLeft = (maxGX * blockSize).toFloat() / w
        val subjectTop = (maxGY * blockSize).toFloat() / h
        val subjectRight = ((maxGX + 3) * blockSize).toFloat() / w
        val subjectBottom = ((maxGY + 3) * blockSize).toFloat() / h

        return RectF(
            subjectLeft.coerceIn(0f, 1f),
            subjectTop.coerceIn(0f, 1f),
            subjectRight.coerceIn(0f, 1f),
            subjectBottom.coerceIn(0f, 1f)
        )
    }

    // ==================== 指令生成 ====================

    /**
     * 根据分析结果生成调整指令
     */
    private fun generateCommands(
        scores: Map<String, Float>,
        subject: RectF?,
        w: Int, h: Int
    ): List<ComposeAccessibilityService.ComposeCommand> {
        val commands = mutableListOf<ComposeAccessibilityService.ComposeCommand>()

        subject?.let { s ->
            val cx = (s.left + s.right) / 2
            val cy = (s.top + s.bottom) / 2

            // --- 三分法校正：移动主体到最近的三分点 ---
            val thirdsScore = scores["thirds"] ?: 0f
            if (thirdsScore < 0.6f) {
                val targetX = listOf(1f / 3, 2f / 3).minBy { abs(cx - it) }
                val targetY = listOf(1f / 3, 2f / 3).minBy { abs(cy - it) }

                val dx = targetX - cx
                val dy = targetY - cy

                // 如果主体偏移较大，通过缩放+平移来调整
                if (abs(dx) > 0.1f || abs(dy) > 0.1f) {
                    // 建议放大以突出主体
                    if (abs(dx) > 0.15f) {
                        commands.add(ComposeAccessibilityService.ComposeCommand.ZoomIn(0.3f))
                    }
                }
            }

            // --- 黄金比例校正 ---
            val goldenScore = scores["golden"] ?: 0f
            if (goldenScore < 0.5f && thirdsScore < 0.5f) {
                // 如果三分法和黄金比例都不好，建议缩放调整视角
                val subjectSize = (s.right - s.left) * (s.bottom - s.top)
                if (subjectSize < 0.15f) {
                    // 主体太小，放大
                    commands.add(ComposeAccessibilityService.ComposeCommand.ZoomIn(0.5f))
                } else if (subjectSize > 0.6f) {
                    // 主体太大，缩小
                    commands.add(ComposeAccessibilityService.ComposeCommand.ZoomOut(0.3f))
                }
            }

            // --- 简洁性校正 ---
            val simplicityScore = scores["simplicity"] ?: 0f
            if (simplicityScore < 0.4f) {
                // 画面太杂乱，放大以简化
                commands.add(ComposeAccessibilityService.ComposeCommand.ZoomIn(0.4f))
            }
        }

        // --- 地平线校正 ---
        val horizonScore = scores["horizon"] ?: 0f
        if (horizonScore < 0.3f) {
            // 地平线不水平，提示用户（无法自动旋转，只能提示）
            // 这里不生成命令，由 UI 层显示提示
        }

        // --- 对称性校正（建筑场景） ---
        val symmetryScore = scores["symmetry"] ?: 0f
        if (symmetryScore < 0.4f && (scores["leading"] ?: 0f) > 0.5f) {
            // 有引导线但不对称，可能需要调整角度
            // 通过点击对焦区域来引导用户
        }

        // 限制同时执行的指令数量
        return commands.take(2)
    }

    // ==================== 工具方法 ====================

    private fun sobelX(pixels: IntArray, x: Int, y: Int, w: Int): Int {
        fun brightness(idx: Int): Int {
            val p = pixels[idx]
            return ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        }

        return -brightness((y - 1) * w + (x - 1)) + brightness((y - 1) * w + (x + 1)) +
                -2 * brightness(y * w + (x - 1)) + 2 * brightness(y * w + (x + 1)) +
                -brightness((y + 1) * w + (x - 1)) + brightness((y + 1) * w + (x + 1))
    }

    private fun sobelY(pixels: IntArray, x: Int, y: Int, w: Int): Int {
        fun brightness(idx: Int): Int {
            val p = pixels[idx]
            return ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        }

        return -brightness((y - 1) * w + (x - 1)) - 2 * brightness((y - 1) * w + x) - brightness((y - 1) * w + (x + 1)) +
                brightness((y + 1) * w + (x - 1)) + 2 * brightness((y + 1) * w + x) + brightness((y + 1) * w + (x + 1))
    }

    private fun resizeForAnalysis(bitmap: Bitmap, targetSize: Int): Bitmap {
        val scale = targetSize.toFloat() / max(bitmap.width, bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun getPixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    data class AnalysisResult(
        val score: Float,
        val scores: Map<String, Float>,
        val subject: RectF?,
        val commands: List<ComposeAccessibilityService.ComposeCommand>,
        val shouldExecute: Boolean,
        val analysisTimeMs: Long
    )
}
