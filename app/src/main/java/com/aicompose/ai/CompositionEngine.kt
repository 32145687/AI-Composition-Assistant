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
    val score: Float,               // 0-100
    val grade: String,
    val gradeEmoji: String,
    val rules: List<RuleResult>,
    val suggestions: List<String>,
    val subject: RectF?,            // 主体位置（归一化 0-1）
    val subjectConfidence: Float,   // 主体置信度
    val sceneType: SceneType,
    val thirdsPoint: Pair<Float, Float>?,  // 推荐的三分点
    val analysisTimeMs: Long
)

data class RuleResult(
    val name: String,
    val score: Float,   // 0-1
    val icon: String,
    val passed: Boolean
)

enum class SceneType(val label: String, val icon: String) {
    PORTRAIT("人像", "👤"),
    LANDSCAPE("风景", "🏔️"),
    ARCHITECTURE("建筑", "🏛️"),
    FOOD("美食", "🍜"),
    NIGHT("夜景", "🌙"),
    MACRO("微距", "🌸"),
    GENERAL("通用", "📷")
}

/**
 * AI 构图引擎 — 混合方案：算法 + 启发式规则
 */
class CompositionEngine {

    private var lastScore = 50f

    fun analyze(bitmap: Bitmap): CompositionResult {
        val t0 = System.currentTimeMillis()

        // 缩放
        val targetSize = 300
        val scale = targetSize.toFloat() / max(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. 场景识别
        val scene = classifyScene(pixels, w, h)

        // 2. 主体检测
        val subject = detectSubject(pixels, w, h)
        val subjectConf = if (subject != null) {
            val area = (subject.right - subject.left) * (subject.bottom - subject.top)
            area.coerceIn(0f, 1f)
        } else 0f

        // 3. 构图法则评分
        val rules = mutableListOf<RuleResult>()
        rules.add(checkRuleOfThirds(subject))
        rules.add(checkGoldenRatio(subject))
        rules.add(checkSymmetry(pixels, w, h))
        rules.add(checkLeadingLines(pixels, w, h))
        rules.add(checkBalance(pixels, w, h))
        rules.add(checkSimplicity(pixels, w, h))
        rules.add(checkHorizon(pixels, w, h))
        rules.add(checkDepth(pixels, w, h))

        // 4. 加权总分
        val weights = floatArrayOf(0.22f, 0.13f, 0.12f, 0.13f, 0.10f, 0.10f, 0.10f, 0.10f)
        var total = 0f
        for (i in rules.indices) total += rules[i].score * weights[i]
        total = (total * 100).coerceIn(0f, 100f)

        // 场景加分
        if (scene == SceneType.LANDSCAPE && rules[3].score > 0.6f) total += 5f
        if (scene == SceneType.PORTRAIT && rules[0].score > 0.6f) total += 5f
        if (scene == SceneType.ARCHITECTURE && rules[2].score > 0.6f) total += 5f
        total = total.coerceIn(0f, 100f)

        // 平滑
        total = lastScore * 0.4f + total * 0.6f
        lastScore = total

        val (grade, emoji) = when {
            total >= 85 -> "优秀" to "🌟"
            total >= 70 -> "良好" to "👍"
            total >= 50 -> "一般" to "👌"
            total >= 35 -> "偏弱" to "👎"
            else -> "较差" to "📸"
        }

        // 5. 推荐三分点（将主体移动到这里）
        val thirdsPoint = subject?.let { s ->
            val cx = (s.left + s.right) / 2
            val cy = (s.top + s.bottom) / 2
            val targetX = listOf(1f / 3, 2f / 3).minBy { abs(cx - it) }
            val targetY = listOf(1f / 3, 2f / 3).minBy { abs(cy - it) }
            Pair(targetX, targetY)
        }

        // 6. 建议
        val suggestions = buildSuggestions(rules, subject, scene, thirdsPoint)

        small.recycle()
        return CompositionResult(
            score = total, grade = grade, gradeEmoji = emoji,
            rules = rules, suggestions = suggestions,
            subject = subject, subjectConfidence = subjectConf,
            sceneType = scene, thirdsPoint = thirdsPoint,
            analysisTimeMs = System.currentTimeMillis() - t0
        )
    }

    // ==================== 场景识别 ====================

    private fun classifyScene(pixels: IntArray, w: Int, h: Int): SceneType {
        var skinCount = 0; var skyCount = 0; var greenCount = 0
        var totalBrightness = 0L; var totalSat = 0f; var count = 0
        var edgeCount = 0

        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
            val bright = (r + g + b) / 3

            totalBrightness += bright; totalSat += sat; count++

            // 肤色检测 (HSV: H=0-25, S=40-200, V>80)
            if (r > 95 && g > 40 && b > 20 && r > g && r > b && abs(r - g) > 15 && sat > 0.1f) skinCount++

            // 天空检测 (蓝色)
            if (b > 120 && b > r && b > g && sat > 0.2f) skyCount++

            // 绿色植物
            if (g > 80 && g > r * 1.2f && g > b * 1.2f) greenCount++

            // 边缘
            if (x > 0 && y > 0) {
                val gx = abs(r - Color.red(pixels[y * w + (x - 1)]))
                val gy = abs(r - Color.red(pixels[(y - 1) * w + x]))
                if (gx + gy > 60) edgeCount++
            }
        }

        val total = count.toFloat()
        val avgBright = totalBrightness.toFloat() / total
        val avgSat = totalSat / total
        val skinRatio = skinCount / total
        val skyRatio = skyCount / total
        val edgeRatio = edgeCount / total

        return when {
            skinRatio > 0.12f -> SceneType.PORTRAIT
            skyRatio > 0.25f && edgeRatio < 0.15f -> SceneType.LANDSCAPE
            edgeRatio > 0.2f && avgSat < 0.3f -> SceneType.ARCHITECTURE
            avgSat > 0.4f && edgeRatio < 0.1f -> SceneType.FOOD
            avgBright < 60 -> SceneType.NIGHT
            avgSat > 0.5f && edgeRatio > 0.15f -> SceneType.MACRO
            else -> SceneType.GENERAL
        }
    }

    // ==================== 主体检测 ====================

    private fun detectSubject(pixels: IntArray, w: Int, h: Int): RectF? {
        // 用显著性检测：中心-周围对比
        val blockSize = 12
        val gw = w / blockSize; val gh = h / blockSize
        val saliencyMap = FloatArray(gw * gh)

        // 计算每个块的平均颜色
        val blockColors = Array(gw * gh) { FloatArray(3) }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var rSum = 0f; var gSum = 0f; var bSum = 0f; var cnt = 0
            for (by in 0 until blockSize) for (bx in 0 until blockSize) {
                val x = gx * blockSize + bx; val y = gy * blockSize + by
                if (x >= w || y >= h) continue
                val p = pixels[y * w + x]
                rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p); cnt++
            }
            val idx = gy * gw + gx
            if (cnt > 0) {
                blockColors[idx][0] = rSum / cnt
                blockColors[idx][1] = gSum / cnt
                blockColors[idx][2] = bSum / cnt
            }
        }

        // 计算显著性：与周围区域的颜色差异
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val idx = gy * gw + gx
            var diff = 0f; var neighborCount = 0
            for (dy in -2..2) for (dx in -2..2) {
                val ny = gy + dy; val nx = gx + dx
                if (ny < 0 || ny >= gh || nx < 0 || nx >= gw || (dy == 0 && dx == 0)) continue
                val nIdx = ny * gw + nx
                diff += abs(blockColors[idx][0] - blockColors[nIdx][0]) +
                        abs(blockColors[idx][1] - blockColors[nIdx][1]) +
                        abs(blockColors[idx][2] - blockColors[nIdx][2])
                neighborCount++
            }
            saliencyMap[idx] = if (neighborCount > 0) diff / neighborCount else 0f
        }

        // 找最显著区域
        var maxSal = 0f; var bestGX = 0; var bestGY = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val sal = saliencyMap[gy * gw + gx]
            if (sal > maxSal) { maxSal = sal; bestGX = gx; bestGY = gy }
        }

        if (maxSal < 10f) return null

        // 扩展区域（包含周围相似块）
        var minGX = bestGX; var maxGX = bestGX; var minGY = bestGY; var maxGY = bestGY
        val threshold = maxSal * 0.4f
        for (gy in (bestGY - 3).coerceAtLeast(0)..(bestGY + 3).coerceAtMost(gh - 1)) {
            for (gx in (bestGX - 3).coerceAtLeast(0)..(bestGX + 3).coerceAtMost(gw - 1)) {
                if (saliencyMap[gy * gw + gx] > threshold) {
                    minGX = min(minGX, gx); maxGX = max(maxGX, gx)
                    minGY = min(minGY, gy); maxGY = max(maxGY, gy)
                }
            }
        }

        return RectF(
            (minGX * blockSize).toFloat() / w,
            (minGY * blockSize).toFloat() / h,
            ((maxGX + 1) * blockSize).coerceAtMost(w).toFloat() / w,
            ((maxGY + 1) * blockSize).coerceAtMost(h).toFloat() / h
        )
    }

    // ==================== 构图法则 ====================

    private fun checkRuleOfThirds(s: RectF?): RuleResult {
        if (s == null) return RuleResult("三分法", 0.5f, "📐", false)
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        val pts = listOf(1f/3 to 1f/3, 2f/3 to 1f/3, 1f/3 to 2f/3, 2f/3 to 2f/3)
        val minD = pts.minOf { (tx, ty) -> sqrt((cx-tx)*(cx-tx) + (cy-ty)*(cy-ty)) }
        val score = (1f - minD / 0.25f).coerceIn(0f, 1f)
        return RuleResult("三分法", score, "📐", score > 0.5f)
    }

    private fun checkGoldenRatio(s: RectF?): RuleResult {
        if (s == null) return RuleResult("黄金比例", 0.5f, "✨", false)
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        val pts = listOf(0.382f to 0.382f, 0.618f to 0.382f, 0.382f to 0.618f, 0.618f to 0.618f)
        val minD = pts.minOf { (gx, gy) -> sqrt((cx-gx)*(cx-gx) + (cy-gy)*(cy-gy)) }
        val score = (1f - minD / 0.2f).coerceIn(0f, 1f)
        return RuleResult("黄金比例", score, "✨", score > 0.5f)
    }

    private fun checkSymmetry(pixels: IntArray, w: Int, h: Int): RuleResult {
        var diff = 0L; var cnt = 0
        for (y in 0 until h) for (x in 0 until w / 2) {
            val l = pixels[y * w + x]; val r = pixels[y * w + (w - 1 - x)]
            diff += abs(Color.red(l) - Color.red(r)) + abs(Color.green(l) - Color.green(r)) + abs(Color.blue(l) - Color.blue(r))
            cnt++
        }
        val score = (1f - diff.toFloat() / (cnt * 3 * 255) * 3).coerceIn(0f, 1f)
        return RuleResult("对称性", score, "🪞", score > 0.55f)
    }

    private fun checkLeadingLines(pixels: IntArray, w: Int, h: Int): RuleResult {
        var edges = 0; var hEdges = 0; var vEdges = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = sobelX(pixels, x, y, w); val gy = sobelY(pixels, x, y, w)
            val mag = sqrt((gx * gx + gy * gy).toFloat())
            if (mag > 35) { edges++; if (abs(gy) > abs(gx) * 2) hEdges++; if (abs(gx) > abs(gy) * 2) vEdges++ }
        }
        val ratio = edges.toFloat() / (w * h)
        val lineStrength = (hEdges + vEdges).toFloat() / max(1, edges)
        val score = when {
            ratio in 0.04f..0.3f && lineStrength > 0.3f -> 0.8f
            ratio in 0.02f..0.4f -> 0.5f
            else -> 0.3f
        }
        return RuleResult("引导线", score, "↗️", score > 0.6f)
    }

    private fun checkBalance(pixels: IntArray, w: Int, h: Int): RuleResult {
        val q = LongArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]; val b = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3L
            q[if (y < h / 2) (if (x < w / 2) 0 else 1) else (if (x < w / 2) 2 else 3)] += b
        }
        val total = q.sum().toFloat()
        if (total == 0f) return RuleResult("视觉平衡", 0.5f, "⚖️", false)
        val maxDev = q.maxOf { abs(it / total - 0.25f) }
        val score = (1f - maxDev * 4).coerceIn(0f, 1f)
        return RuleResult("视觉平衡", score, "⚖️", score > 0.5f)
    }

    private fun checkSimplicity(pixels: IntArray, w: Int, h: Int): RuleResult {
        val buckets = HashSet<Int>()
        for (p in pixels) {
            val r = Color.red(p) / 32; val g = Color.green(p) / 32; val b = Color.blue(p) / 32
            buckets.add((r shl 6) or (g shl 3) or b)
        }
        val score = (1f - buckets.size.toFloat() / 512).coerceIn(0.15f, 1f)
        return RuleResult("简洁性", score, "🎯", score > 0.5f)
    }

    private fun checkHorizon(pixels: IntArray, w: Int, h: Int): RuleResult {
        var hEdges = 0; var tEdges = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = abs(sobelX(pixels, x, y, w)); val gy = abs(sobelY(pixels, x, y, w))
            if (gx + gy > 40) { tEdges++; if (gy > gx * 2) hEdges++ }
        }
        val score = if (tEdges > 0) (hEdges.toFloat() / tEdges).coerceIn(0f, 1f) else 0.5f
        return RuleResult("水平线", score, "📏", score > 0.35f)
    }

    private fun checkDepth(pixels: IntArray, w: Int, h: Int): RuleResult {
        var top = 0L; var bot = 0L; val half = h / 2; val cnt = w * half
        for (y in 0 until half) for (x in 0 until w) { val p = pixels[y * w + x]; top += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 }
        for (y in half until h) for (x in 0 until w) { val p = pixels[y * w + x]; bot += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 }
        val score = (abs(top - bot).toFloat() / (cnt * 255)).coerceIn(0f, 1f)
        return RuleResult("纵深感", score, "🏔️", score > 0.35f)
    }

    // ==================== 建议 ====================

    private fun buildSuggestions(rules: List<RuleResult>, s: RectF?, scene: SceneType, tp: Pair<Float, Float>?): List<String> {
        val list = mutableListOf<String>()
        val thirds = rules[0]

        if (!thirds.passed && s != null && tp != null) {
            val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
            val dx = tp.first - cx; val dy = tp.second - cy
            if (abs(dx) > 0.1f) list.add(if (dx > 0) "📱 向右移动主体到三分线" else "📱 向左移动主体到三分线")
            if (abs(dy) > 0.1f) list.add(if (dy > 0) "📱 降低拍摄角度" else "📱 抬高拍摄角度")
        }
        if (s != null) {
            val area = (s.right - s.left) * (s.bottom - s.top)
            if (area < 0.12f) list.add("🔍 靠近主体或放大镜头")
            if (area > 0.65f) list.add("🔍 后退几步或缩小镜头")
        }
        when (scene) {
            SceneType.LANDSCAPE -> if (rules[3].score < 0.5f) list.add("🏔️ 利用道路/河流作引导线")
            SceneType.PORTRAIT -> if (rules[0].score < 0.5f) list.add("👤 将人物眼睛放在三分线")
            SceneType.ARCHITECTURE -> if (rules[2].score < 0.5f) list.add("🏛️ 正对建筑中轴线拍摄")
            SceneType.FOOD -> list.add("🍜 试试45°俯拍角度")
            else -> {}
        }
        if (rules[6].score < 0.3f) list.add("📏 手机有点歪，保持水平")
        return list.take(3)
    }

    private fun sobelX(p: IntArray, x: Int, y: Int, w: Int): Int {
        fun b(i: Int) = Color.red(p[i]) + Color.green(p[i]) + Color.blue(p[i])
        return -b((y - 1) * w + (x - 1)) + b((y - 1) * w + (x + 1)) - 2 * b(y * w + (x - 1)) + 2 * b(y * w + (x + 1)) - b((y + 1) * w + (x - 1)) + b((y + 1) * w + (x + 1))
    }

    private fun sobelY(p: IntArray, x: Int, y: Int, w: Int): Int {
        fun b(i: Int) = Color.red(p[i]) + Color.green(p[i]) + Color.blue(p[i])
        return -b((y - 1) * w + (x - 1)) - 2 * b((y - 1) * w + x) - b((y - 1) * w + (x + 1)) + b((y + 1) * w + (x - 1)) + 2 * b((y + 1) * w + x) + b((y + 1) * w + (x + 1))
    }
}
