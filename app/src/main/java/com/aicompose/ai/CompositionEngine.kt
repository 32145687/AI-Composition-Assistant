package com.aicompose.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.aicompose.ar.SceneTracker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 构图分析结果 — 包含 AR 所需的全部信息
 */
data class CompositionResult(
    val score: Float,
    val grade: String, val gradeEmoji: String,
    val rules: List<RuleResult>,
    val suggestions: List<String>,
    val subject: RectF?,
    val subjectConfidence: Float,
    val sceneType: SceneType,
    val thirdsPoint: Pair<Float, Float>?,
    // AR 相关
    val strongEdges: List<PointF>,     // 场景中的强边缘点（用于锚定引导线）
    val vanishingPoint: PointF?,       // 消失点（透视引导线的汇聚点）
    val horizonY: Float?,              // 地平线 Y 位置（归一化）
    val analysisTimeMs: Long
)

data class RuleResult(val name: String, val score: Float, val icon: String, val passed: Boolean)

enum class SceneType(val label: String, val icon: String) {
    PORTRAIT("人像", "👤"), LANDSCAPE("风景", "🏔️"), ARCHITECTURE("建筑", "🏛️"),
    FOOD("美食", "🍜"), NIGHT("夜景", "🌙"), MACRO("微距", "🌸"), GENERAL("通用", "📷")
}

/**
 * 构图分析引擎 — 算法 + TFLite + 场景特征提取
 */
class CompositionEngine(private val tfliteScorer: TFLiteScorer? = null) {

    private var lastScore = 50f

    fun analyze(bitmap: Bitmap): CompositionResult {
        val t0 = System.currentTimeMillis()

        val targetSize = 280
        val scale = targetSize.toFloat() / max(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        val scene = classifyScene(pixels, w, h)
        val subject = detectSubject(pixels, w, h)
        val subjectConf = subject?.let { (it.right - it.left) * (it.bottom - it.top) } ?: 0f

        // 构图法则
        val rules = listOf(
            checkRuleOfThirds(subject), checkGoldenRatio(subject),
            checkSymmetry(pixels, w, h), checkLeadingLines(pixels, w, h),
            checkBalance(pixels, w, h), checkSimplicity(pixels, w, h),
            checkHorizon(pixels, w, h), checkDepth(pixels, w, h)
        )

        // 算法分
        val weights = floatArrayOf(0.22f, 0.13f, 0.12f, 0.13f, 0.10f, 0.10f, 0.10f, 0.10f)
        var algoScore = 0f; for (i in rules.indices) algoScore += rules[i].score * weights[i]
        algoScore = (algoScore * 100).coerceIn(0f, 100f)

        // DL 分
        val dlScore = tfliteScorer?.score(bitmap)
        var total = if (dlScore != null) dlScore * 0.6f + algoScore * 0.4f else algoScore

        // 场景加分
        if (scene == SceneType.LANDSCAPE && rules[3].score > 0.6f) total += 5f
        if (scene == SceneType.PORTRAIT && rules[0].score > 0.6f) total += 5f
        if (scene == SceneType.ARCHITECTURE && rules[2].score > 0.6f) total += 5f
        total = total.coerceIn(0f, 100f)
        total = lastScore * 0.4f + total * 0.6f; lastScore = total

        val (grade, emoji) = when {
            total >= 85 -> "优秀" to "🌟"; total >= 70 -> "良好" to "👍"
            total >= 50 -> "一般" to "👌"; total >= 35 -> "偏弱" to "👎"
            else -> "较差" to "📸"
        }

        val thirdsPoint = subject?.let { s ->
            val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
            listOf(1f / 3, 2f / 3).minBy { abs(cx - it) } to
                    listOf(1f / 3, 2f / 3).minBy { abs(cy - it) }
        }

        // AR 特征提取
        val edges = extractStrongEdges(pixels, w, h)
        val vp = detectVanishingPoint(pixels, w, h)
        val horizon = detectHorizonLine(pixels, w, h)

        val suggestions = buildSuggestions(rules, subject, scene, thirdsPoint)

        small.recycle()
        return CompositionResult(
            score = total, grade = grade, gradeEmoji = emoji,
            rules = rules, suggestions = suggestions,
            subject = subject, subjectConfidence = subjectConf,
            sceneType = scene, thirdsPoint = thirdsPoint,
            strongEdges = edges, vanishingPoint = vp, horizonY = horizon,
            analysisTimeMs = System.currentTimeMillis() - t0
        )
    }

    // ==================== AR 特征提取 ====================

    /**
     * 提取强边缘点 — 用于锚定 AR 引导线
     * 取 Hough 线条的端点和交点
     */
    private fun extractStrongEdges(pixels: IntArray, w: Int, h: Int): List<PointF> {
        val edges = mutableListOf<PointF>()
        val step = 4

        for (y in 2 until h - 2 step step) {
            for (x in 2 until w - 2 step step) {
                val gx = sobelX(pixels, x, y, w)
                val gy = sobelY(pixels, x, y, w)
                val mag = sqrt((gx * gx + gy * gy).toFloat())
                if (mag > 60) {
                    edges.add(PointF(x.toFloat() / w, y.toFloat() / h))
                }
            }
        }

        // 非极大值抑制：保留局部最大值
        return edges.take(100)
    }

    /**
     * 检测消失点 — 透视引导线的汇聚点
     */
    private fun detectVanishingPoint(pixels: IntArray, w: Int, h: Int): PointF? {
        // 收集强边缘的方向
        val lines = mutableListOf<Pair<PointF, PointF>>()
        for (y in 3 until h - 3 step 6) {
            for (x in 3 until w - 3 step 6) {
                val gx = sobelX(pixels, x, y, w); val gy = sobelY(pixels, x, y, w)
                val mag = sqrt((gx * gx + gy * gy).toFloat())
                if (mag > 50) {
                    // 沿梯度方向延伸
                    val len = 20f
                    val nx = -gy / mag * len; val ny = gx / mag * len
                    lines.add(PointF(x - nx, y - ny) to PointF(x + nx, y + ny))
                }
            }
        }

        if (lines.size < 5) return null

        // 简化版消失点检测：找线条延长线最密集的区域
        // 用网格投票
        val gridRes = 20
        val votes = IntArray(gridRes * gridRes)
        for ((p1, p2) in lines.take(50)) {
            val dx = p2.x - p1.x; val dy = p2.y - p1.y
            // 延长线到图像中心区域
            val cx = w / 2f; val cy = h / 2f
            val t = ((cx - p1.x) * dx + (cy - p1.y) * dy) / (dx * dx + dy * dy + 0.001f)
            val ix = p1.x + dx * t; val iy = p1.y + dy * t
            val gx = (ix / w * gridRes).toInt().coerceIn(0, gridRes - 1)
            val gy2 = (iy / h * gridRes).toInt().coerceIn(0, gridRes - 1)
            votes[gy2 * gridRes + gx]++
        }

        var maxVotes = 0; var bestIdx = 0
        for (i in votes.indices) if (votes[i] > maxVotes) { maxVotes = votes[i]; bestIdx = i }

        return if (maxVotes > 3) {
            val gx = bestIdx % gridRes; val gy2 = bestIdx / gridRes
            PointF((gx + 0.5f) / gridRes, (gy2 + 0.5f) / gridRes)
        } else null
    }

    /**
     * 检测地平线位置
     */
    private fun detectHorizonLine(pixels: IntArray, w: Int, h: Int): Float? {
        // 扫描每行的水平边缘强度
        val rowStrength = FloatArray(h)
        for (y in 1 until h - 1) {
            var strength = 0f
            for (x in 1 until w - 1) {
                val gy = abs(sobelY(pixels, x, y, w))
                if (gy > 30) strength += gy
            }
            rowStrength[y] = strength / w
        }

        // 找最强水平线
        var bestY = 0; var bestStrength = 0f
        for (y in h / 4 until h * 3 / 4) {
            if (rowStrength[y] > bestStrength) {
                bestStrength = rowStrength[y]; bestY = y
            }
        }

        return if (bestStrength > 5f) bestY.toFloat() / h else null
    }

    // ==================== 场景识别 ====================

    private fun classifyScene(pixels: IntArray, w: Int, h: Int): SceneType {
        var skinCount = 0; var skyCount = 0; var edgeCount = 0
        var totalBright = 0L; var totalSat = 0f; var count = 0

        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y * w + x]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC else 0f
            totalBright += (r + g + b) / 3; totalSat += sat; count++

            if (r > 95 && g > 40 && b > 20 && r > g && r > b && abs(r - g) > 15) skinCount++
            if (b > 120 && b > r && b > g && sat > 0.2f) skyCount++
            if (x > 0 && y > 0) {
                val gx = abs(r - Color.red(pixels[y * w + (x - 1)]))
                val gy = abs(r - Color.red(pixels[(y - 1) * w + x]))
                if (gx + gy > 60) edgeCount++
            }
        }

        val avgBright = totalBright.toFloat() / count
        val avgSat = totalSat / count
        val edgeRatio = edgeCount.toFloat() / count

        return when {
            skinCount.toFloat() / count > 0.12f -> SceneType.PORTRAIT
            skyCount.toFloat() / count > 0.25f -> SceneType.LANDSCAPE
            edgeRatio > 0.2f && avgSat < 0.3f -> SceneType.ARCHITECTURE
            avgSat > 0.4f && edgeRatio < 0.1f -> SceneType.FOOD
            avgBright < 60 -> SceneType.NIGHT
            avgSat > 0.5f && edgeRatio > 0.15f -> SceneType.MACRO
            else -> SceneType.GENERAL
        }
    }

    // ==================== 主体检测（显著性） ====================

    private fun detectSubject(pixels: IntArray, w: Int, h: Int): RectF? {
        val bs = 10; val gw = w / bs; val gh = h / bs
        val blockColors = Array(gw * gh) { FloatArray(3) }

        for (gy in 0 until gh) for (gx in 0 until gw) {
            var rS = 0f; var gS = 0f; var bS = 0f; var c = 0
            for (by in 0 until bs) for (bx in 0 until bs) {
                val x = gx * bs + bx; val y = gy * bs + by
                if (x >= w || y >= h) continue
                val p = pixels[y * w + x]
                rS += Color.red(p); gS += Color.green(p); bS += Color.blue(p); c++
            }
            if (c > 0) { val i = gy * gw + gx; blockColors[i][0] = rS/c; blockColors[i][1] = gS/c; blockColors[i][2] = bS/c }
        }

        val saliency = FloatArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val idx = gy * gw + gx; var diff = 0f; var nc = 0
            for (dy in -2..2) for (dx in -2..2) {
                val ny = gy + dy; val nx = gx + dx
                if (ny in 0 until gh && nx in 0 until gw && (dy != 0 || dx != 0)) {
                    val ni = ny * gw + nx
                    diff += abs(blockColors[idx][0] - blockColors[ni][0]) +
                            abs(blockColors[idx][1] - blockColors[ni][1]) +
                            abs(blockColors[idx][2] - blockColors[ni][2])
                    nc++
                }
            }
            saliency[idx] = if (nc > 0) diff / nc else 0f
        }

        var maxSal = 0f; var bx = 0; var by = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (saliency[gy * gw + gx] > maxSal) { maxSal = saliency[gy * gw + gx]; bx = gx; by = gy }
        }
        if (maxSal < 8f) return null

        val threshold = maxSal * 0.35f
        var minX = bx; var maxX = bx; var minY = by; var maxY = by
        for (gy in (by - 4).coerceAtLeast(0)..(by + 4).coerceAtMost(gh - 1)) {
            for (gx in (bx - 4).coerceAtLeast(0)..(bx + 4).coerceAtMost(gw - 1)) {
                if (saliency[gy * gw + gx] > threshold) {
                    minX = min(minX, gx); maxX = max(maxX, gx); minY = min(minY, gy); maxY = max(maxY, gy)
                }
            }
        }

        return RectF(
            (minX * bs).toFloat() / w, (minY * bs).toFloat() / h,
            ((maxX + 1) * bs).coerceAtMost(w).toFloat() / w, ((maxY + 1) * bs).coerceAtMost(h).toFloat() / h
        )
    }

    // ==================== 构图法则 ====================

    private fun checkRuleOfThirds(s: RectF?): RuleResult {
        if (s == null) return RuleResult("三分法", 0.5f, "📐", false)
        val cx = (s.left + s.right) / 2; val cy = (s.top + s.bottom) / 2
        val minD = listOf(1f/3 to 1f/3, 2f/3 to 1f/3, 1f/3 to 2f/3, 2f/3 to 2f/3)
            .minOf { (tx, ty) -> sqrt((cx-tx)*(cx-tx) + (cy-ty)*(cy-ty)) }
        val sc = (1f - minD / 0.25f).coerceIn(0f, 1f)
        return RuleResult("三分法", sc, "📐", sc > 0.5f)
    }
    private fun checkGoldenRatio(s: RectF?): RuleResult {
        if (s == null) return RuleResult("黄金比例", 0.5f, "✨", false)
        val cx = (s.left+s.right)/2; val cy = (s.top+s.bottom)/2
        val minD = listOf(0.382f to 0.382f, 0.618f to 0.382f, 0.382f to 0.618f, 0.618f to 0.618f)
            .minOf { (gx, gy) -> sqrt((cx-gx)*(cx-gx)+(cy-gy)*(cy-gy)) }
        val sc = (1f - minD/0.2f).coerceIn(0f, 1f)
        return RuleResult("黄金比例", sc, "✨", sc > 0.5f)
    }
    private fun checkSymmetry(pixels: IntArray, w: Int, h: Int): RuleResult {
        var diff = 0L; var c = 0
        for (y in 0 until h) for (x in 0 until w/2) {
            val l = pixels[y*w+x]; val r = pixels[y*w+(w-1-x)]
            diff += abs(Color.red(l)-Color.red(r))+abs(Color.green(l)-Color.green(r))+abs(Color.blue(l)-Color.blue(r)); c++
        }
        val sc = (1f - diff.toFloat()/(c*3*255)*3).coerceIn(0f, 1f)
        return RuleResult("对称性", sc, "🪞", sc > 0.55f)
    }
    private fun checkLeadingLines(pixels: IntArray, w: Int, h: Int): RuleResult {
        var edges = 0; var lines = 0
        for (y in 1 until h-1) for (x in 1 until w-1) {
            val gx = sobelX(pixels,x,y,w); val gy = sobelY(pixels,x,y,w)
            val mag = sqrt((gx*gx+gy*gy).toFloat())
            if (mag>35){edges++;if(abs(gy)>abs(gx)*2||abs(gx)>abs(gy)*2)lines++}
        }
        val ratio = edges.toFloat()/(w*h)
        val sc = when{ratio in 0.04f..0.3f && lines.toFloat()/max(1,edges)>0.3f->0.8f;ratio in 0.02f..0.4f->0.5f;else->0.3f}
        return RuleResult("引导线", sc, "↗️", sc > 0.6f)
    }
    private fun checkBalance(pixels: IntArray, w: Int, h: Int): RuleResult {
        val q = LongArray(4)
        for (y in 0 until h) for (x in 0 until w) {
            val p = pixels[y*w+x]; val b = (Color.red(p)+Color.green(p)+Color.blue(p))/3L
            q[if(y<h/2)(if(x<w/2)0 else 1)else(if(x<w/2)2 else 3)]+=b
        }
        val total = q.sum().toFloat(); if(total==0f) return RuleResult("视觉平衡",0.5f,"⚖️",false)
        val sc = (1f - q.maxOf{abs(it/total-0.25f)}*4).coerceIn(0f,1f)
        return RuleResult("视觉平衡", sc, "⚖️", sc > 0.5f)
    }
    private fun checkSimplicity(pixels: IntArray, w: Int, h: Int): RuleResult {
        val buckets = HashSet<Int>()
        for (p in pixels) { val r=Color.red(p)/32;val g=Color.green(p)/32;val b=Color.blue(p)/32;buckets.add((r shl 6)or(g shl 3)or b) }
        val sc = (1f - buckets.size.toFloat()/512).coerceIn(0.15f,1f)
        return RuleResult("简洁性", sc, "🎯", sc > 0.5f)
    }
    private fun checkHorizon(pixels: IntArray, w: Int, h: Int): RuleResult {
        var hE=0;var tE=0
        for(y in 1 until h-1) for(x in 1 until w-1){val gx=abs(sobelX(pixels,x,y,w));val gy=abs(sobelY(pixels,x,y,w));if(gx+gy>40){tE++;if(gy>gx*2)hE++}}
        val sc = if(tE>0)(hE.toFloat()/tE).coerceIn(0f,1f) else 0.5f
        return RuleResult("水平线", sc, "📏", sc > 0.35f)
    }
    private fun checkDepth(pixels: IntArray, w: Int, h: Int): RuleResult {
        var top=0L;var bot=0L;val half=h/2;val cnt=w*half
        for(y in 0 until half) for(x in 0 until w){val p=pixels[y*w+x];top+=(Color.red(p)+Color.green(p)+Color.blue(p))/3}
        for(y in half until h) for(x in 0 until w){val p=pixels[y*w+x];bot+=(Color.red(p)+Color.green(p)+Color.blue(p))/3}
        val sc = (abs(top-bot).toFloat()/(cnt*255)).coerceIn(0f,1f)
        return RuleResult("纵深感", sc, "🏔️", sc > 0.35f)
    }

    // ==================== 建议 ====================

    private fun buildSuggestions(rules: List<RuleResult>, s: RectF?, scene: SceneType, tp: Pair<Float, Float>?): List<String> {
        val list = mutableListOf<String>()
        if (!rules[0].passed && s != null && tp != null) {
            val cx = (s.left+s.right)/2; val cy = (s.top+s.bottom)/2
            val dx = tp.first - cx; val dy = tp.second - cy
            if (abs(dx) > 0.1f) list.add(if (dx > 0) "📱 向右移动主体" else "📱 向左移动主体")
            if (abs(dy) > 0.1f) list.add(if (dy > 0) "📱 降低角度" else "📱 抬高角度")
        }
        if (s != null) {
            val area = (s.right-s.left)*(s.bottom-s.top)
            if (area < 0.12f) list.add("🔍 放大镜头突出主体")
            if (area > 0.65f) list.add("🔍 缩小镜头简化画面")
        }
        when (scene) {
            SceneType.LANDSCAPE -> if (rules[3].score < 0.5f) list.add("🏔️ 利用道路/河流引导视线")
            SceneType.PORTRAIT -> if (rules[0].score < 0.5f) list.add("👤 将人物放在三分线位置")
            SceneType.ARCHITECTURE -> if (rules[2].score < 0.5f) list.add("🏛️ 正对建筑中轴线")
            SceneType.FOOD -> list.add("🍜 试试45°俯拍")
            else -> {}
        }
        if (rules[6].score < 0.3f) list.add("📏 保持手机水平")
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
