package com.aicompose.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 构图分析结果
 */
data class CompositionResult(
    val score: Float,
    val grade: String,
    val gradeEmoji: String,
    val rules: List<RuleResult>,
    val suggestions: List<String>,
    val subject: RectF?,
    val analysisTimeMs: Long
)

data class RuleResult(
    val name: String,
    val score: Float,
    val icon: String,
    val passed: Boolean
)

/**
 * AI 构图分析引擎 — 纯算法实现，无外部依赖
 */
class CompositionEngine {

    companion object {
        private const val TAG = "ComposeEngine"
    }

    private var lastScore = 50f

    fun analyze(bitmap: Bitmap): CompositionResult {
        val t0 = System.currentTimeMillis()

        // 缩放到分析尺寸
        val size = 256
        val scale = size.toFloat() / max(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt()
        val h = (bitmap.height * scale).toInt()
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        // 检测主体
        val subject = detectSubject(pixels, w, h)

        // 8 项构图法则评分
        val rules = listOf(
            checkRuleOfThirds(subject, w, h),
            checkGoldenRatio(subject, w, h),
            checkSymmetry(pixels, w, h),
            checkLeadingLines(pixels, w, h),
            checkVisualBalance(pixels, w, h),
            checkSimplicity(pixels, w, h),
            checkHorizon(pixels, w, h),
            checkDepth(pixels, w, h)
        )

        // 加权总分
        val weights = floatArrayOf(0.22f, 0.13f, 0.12f, 0.13f, 0.10f, 0.10f, 0.10f, 0.10f)
        var total = 0f
        for (i in rules.indices) total += rules[i].score * weights[i]
        total = (total * 100).coerceIn(0f, 100f)

        // 平滑
        total = lastScore * 0.3f + total * 0.7f
        lastScore = total

        // 等级
        val (grade, emoji) = when {
            total >= 85 -> "优秀" to "🌟"
            total >= 70 -> "良好" to "👍"
            total >= 50 -> "一般" to "👌"
            total >= 35 -> "偏弱" to "👎"
            else -> "较差" to "📸"
        }

        // 建议
        val suggestions = buildSuggestions(rules, subject, w, h)

        small.recycle()
        val elapsed = System.currentTimeMillis() - t0

        return CompositionResult(
            score = total,
            grade = grade,
            gradeEmoji = emoji,
            rules = rules,
            suggestions = suggestions,
            subject = subject,
            analysisTimeMs = elapsed
        )
    }

    // ==================== 主体检测 ====================

    private fun detectSubject(pixels: IntArray, w: Int, h: Int): RectF? {
        val blockSize = 16
        val gw = w / blockSize
        val gh = h / blockSize
        var maxWeight = 0f
        var bestGX = 0
        var bestGY = 0

        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var weight = 0f
                for (by in 0 until blockSize) {
                    for (bx in 0 until blockSize) {
                        val x = gx * blockSize + bx
                        val y = gy * blockSize + by
                        if (x >= w || y >= h) continue
                        val p = pixels[y * w + x]
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
                        val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
                        val bright = (r + g + b) / 3f / 255f
                        weight += sat * 0.6f + abs(bright - 0.5f) * 0.8f
                    }
                }
                if (weight > maxWeight) { maxWeight = weight; bestGX = gx; bestGY = gy }
            }
        }

        if (maxWeight < 0.05f) return null
        val margin = 2
        return RectF(
            ((bestGX - margin) * blockSize).coerceAtLeast(0).toFloat() / w,
            ((bestGY - margin) * blockSize).coerceAtLeast(0).toFloat() / h,
            ((bestGX + margin + 1) * blockSize).coerceAtMost(w).toFloat() / w,
            ((bestGY + margin + 1) * blockSize).coerceAtMost(h).toFloat() / h
        )
    }

    // ==================== 构图法则 ====================

    private fun checkRuleOfThirds(s: RectF?, w: Int, h: Int): RuleResult {
        if (s == null) return RuleResult("三分法", 0.5f, "📐", false)
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        val pts = listOf(1f/3 to 1f/3, 2f/3 to 1f/3, 1f/3 to 2f/3, 2f/3 to 2f/3)
        val minD = pts.minOf { (tx, ty) -> sqrt((cx-tx)*(cx-tx) + (cy-ty)*(cy-ty)) }
        val score = (1f - minD / 0.2f).coerceIn(0f, 1f)
        return RuleResult("三分法", score, "📐", score > 0.6f)
    }

    private fun checkGoldenRatio(s: RectF?, w: Int, h: Int): RuleResult {
        if (s == null) return RuleResult("黄金比例", 0.5f, "✨", false)
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        val pts = listOf(0.382f to 0.382f, 0.618f to 0.382f, 0.382f to 0.618f, 0.618f to 0.618f)
        val minD = pts.minOf { (gx, gy) -> sqrt((cx-gx)*(cx-gx) + (cy-gy)*(cy-gy)) }
        val score = (1f - minD / 0.15f).coerceIn(0f, 1f)
        return RuleResult("黄金比例", score, "✨", score > 0.6f)
    }

    private fun checkSymmetry(pixels: IntArray, w: Int, h: Int): RuleResult {
        var diff = 0L; var count = 0
        for (y in 0 until h) for (x in 0 until w/2) {
            val l = pixels[y*w+x]; val r = pixels[y*w+(w-1-x)]
            diff += abs(Color.red(l)-Color.red(r)) + abs(Color.green(l)-Color.green(r)) + abs(Color.blue(l)-Color.blue(r))
            count++
        }
        val score = (1f - diff.toFloat()/(count*3*255) * 3).coerceIn(0f, 1f)
        return RuleResult("对称性", score, "🪞", score > 0.6f)
    }

    private fun checkLeadingLines(pixels: IntArray, w: Int, h: Int): RuleResult {
        var edges = 0
        for (y in 1 until h-1) for (x in 1 until w-1) {
            val gx = sobelX(pixels, x, y, w); val gy = sobelY(pixels, x, y, w)
            if (sqrt((gx*gx+gy*gy).toFloat()) > 40) edges++
        }
        val ratio = edges.toFloat() / (w*h)
        val score = when { ratio in 0.05f..0.25f -> 0.8f; ratio in 0.03f..0.35f -> 0.5f; else -> 0.3f }
        return RuleResult("引导线", score, "↗️", score > 0.6f)
    }

    private fun checkVisualBalance(pixels: IntArray, w: Int, h: Int): RuleResult {
        val q = LongArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y*w+x]; val b = (Color.red(p)+Color.green(p)+Color.blue(p))/3L
            val qi = if (y < h/2) (if (x < w/2) 0 else 1) else (if (x < w/2) 2 else 3)
            q[qi] += b
        }
        val total = q.sum().toFloat(); if (total == 0f) return RuleResult("视觉平衡", 0.5f, "⚖️", false)
        val maxDev = q.maxOf { abs(it/total - 0.25f) }
        val score = (1f - maxDev*4).coerceIn(0f, 1f)
        return RuleResult("视觉平衡", score, "⚖️", score > 0.5f)
    }

    private fun checkSimplicity(pixels: IntArray, w: Int, h: Int): RuleResult {
        val buckets = HashSet<Int>()
        for (p in pixels) {
            val r = Color.red(p)/32; val g = Color.green(p)/32; val b = Color.blue(p)/32
            buckets.add((r shl 6) or (g shl 3) or b)
        }
        val score = (1f - buckets.size.toFloat()/512).coerceIn(0.2f, 1f)
        return RuleResult("简洁性", score, "🎯", score > 0.5f)
    }

    private fun checkHorizon(pixels: IntArray, w: Int, h: Int): RuleResult {
        var hEdges = 0; var tEdges = 0
        for (y in 1 until h-1) for (x in 1 until w-1) {
            val gx = abs(sobelX(pixels, x, y, w)); val gy = abs(sobelY(pixels, x, y, w))
            if (gx+gy > 50) { tEdges++; if (gy > gx*2) hEdges++ }
        }
        val score = if (tEdges > 0) (hEdges.toFloat()/tEdges).coerceIn(0f, 1f) else 0.5f
        return RuleResult("水平线", score, "📏", score > 0.4f)
    }

    private fun checkDepth(pixels: IntArray, w: Int, h: Int): RuleResult {
        var top = 0L; var bot = 0L; val half = h/2; val cnt = w*half
        for (y in 0 until half) for (x in 0 until w) { val p = pixels[y*w+x]; top += (Color.red(p)+Color.green(p)+Color.blue(p))/3 }
        for (y in half until h) for (x in 0 until w) { val p = pixels[y*w+x]; bot += (Color.red(p)+Color.green(p)+Color.blue(p))/3 }
        val score = (abs(top-bot).toFloat()/(cnt*255)).coerceIn(0f, 1f)
        return RuleResult("纵深感", score, "🏔️", score > 0.4f)
    }

    // ==================== 建议生成 ====================

    private fun buildSuggestions(rules: List<RuleResult>, s: RectF?, w: Int, h: Int): List<String> {
        val list = mutableListOf<String>()
        val thirds = rules[0]; val golden = rules[1]; val sym = rules[2]
        val lead = rules[3]; val balance = rules[4]; val simple = rules[5]

        if (!thirds.passed && s != null) {
            val cx = (s.left+s.right)/2; val cy = (s.top+s.bottom)/2
            val tx = listOf(1f/3, 2f/3).minBy { abs(cx-it) }
            val ty = listOf(1f/3, 2f/3).minBy { abs(cy-it) }
            if (abs(cx-tx) > 0.08f) list.add("📱 将主体${if (cx<tx) "右移" else "左移"}到三分线")
            if (abs(cy-ty) > 0.08f) list.add("📱 ${if (cy<ty) "降低" else "抬高"}拍摄角度")
        }
        if (!golden.passed && !thirds.passed && s != null) {
            val area = (s.right-s.left)*(s.bottom-s.top)
            if (area < 0.15f) list.add("🔍 放大镜头突出主体")
            if (area > 0.6f) list.add("🔍 缩小镜头简化画面")
        }
        if (!sym.passed && lead.score > 0.5f) list.add("🏛️ 尝试对称构图")
        if (!balance.passed) list.add("⚖️ 调整画面左右重量平衡")
        if (simple.score < 0.4f) list.add("🎯 简化背景，减少干扰元素")
        if (rules[6].score < 0.3f) list.add("📏 保持手机水平，避免歪斜")
        return list.take(3)
    }

    private fun sobelX(p: IntArray, x: Int, y: Int, w: Int): Int {
        fun b(i: Int) = Color.red(p[i]) + Color.green(p[i]) + Color.blue(p[i])
        return -b((y-1)*w+(x-1)) + b((y-1)*w+(x+1)) - 2*b(y*w+(x-1)) + 2*b(y*w+(x+1)) - b((y+1)*w+(x-1)) + b((y+1)*w+(x+1))
    }
    private fun sobelY(p: IntArray, x: Int, y: Int, w: Int): Int {
        fun b(i: Int) = Color.red(p[i]) + Color.green(p[i]) + Color.blue(p[i])
        return -b((y-1)*w+(x-1)) - 2*b((y-1)*w+x) - b((y-1)*w+(x+1)) + b((y+1)*w+(x-1)) + 2*b((y+1)*w+x) + b((y+1)*w+(x+1))
    }
}
