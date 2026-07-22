package com.aicompose.ar

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 场景特征跟踪器
 * 用简化 ORB + 光流法跟踪帧间运动，让 AR 引导线锁定在场景上
 */
class SceneTracker {

    companion object {
        private const val TAG = "SceneTracker"
        private const val MAX_FEATURES = 80
        private const val MATCH_THRESHOLD = 30  // 描述子匹配阈值
        private const val GRID_SIZE = 16        // 特征检测网格大小
    }

    // 当前帧特征点
    private var prevFeatures = mutableListOf<FeaturePoint>()
    private var prevPixels: IntArray? = null
    private var prevW = 0; private var prevH = 0

    // 场景运动（dx, dy, scale, rotation）
    var sceneMotion = SceneMotion(0f, 0f, 1f, 0f)
        private set

    // 锁定的场景特征（用于锚定引导线）
    var lockedPoints = mutableListOf<LockedPoint>()
        private set

    data class FeaturePoint(val x: Float, val y: Float, val descriptor: IntArray, val response: Float)
    data class LockedPoint(val sceneX: Float, val sceneY: Float, var screenX: Float, var screenY: Float, var confidence: Float)
    data class SceneMotion(val dx: Float, val dy: Float, val scale: Float, val rotation: Float)

    /**
     * 处理新帧，返回场景运动量
     */
    fun trackFrame(pixels: IntArray, w: Int, h: Int): SceneMotion {
        // 1. 检测 FAST 角点
        val features = detectFAST(pixels, w, h)

        // 2. 计算描述子
        val withDesc = features.map { computeDescriptor(pixels, w, h, it) }

        // 3. 与上一帧匹配
        val prevP = prevPixels
        val motion = if (prevP != null && prevFeatures.isNotEmpty() && withDesc.isNotEmpty()) {
            val matches = matchFeatures(withDesc, prevFeatures)
            estimateMotion(matches)
        } else {
            SceneMotion(0f, 0f, 1f, 0f)
        }

        sceneMotion = motion

        // 4. 更新锁定点
        updateLockedPoints(motion, w, h)

        // 5. 保存当前帧
        prevFeatures = withDesc.toMutableList()
        prevPixels = pixels.copyOf()
        prevW = w; prevH = h

        return motion
    }

    /**
     * 锁定场景中的关键位置（三分点、主体边缘等）
     */
    fun lockScenePoints(subjectNorm: android.graphics.RectF?, w: Int, h: Int) {
        lockedPoints.clear()

        // 锁定三分点位置的特征
        val thirdsX = listOf(w / 3f, 2 * w / 3f)
        val thirdsY = listOf(h / 3f, 2 * h / 3f)

        for (tx in thirdsX) for (ty in thirdsY) {
            // 找最近的特征点
            val nearest = prevFeatures.minByOrNull {
                (it.x - tx) * (it.x - tx) + (it.y - ty) * (it.y - ty)
            }
            if (nearest != null) {
                val dist = sqrt((nearest.x - tx) * (nearest.x - tx) + (nearest.y - ty) * (nearest.y - ty))
                if (dist < w * 0.15f) {
                    lockedPoints.add(LockedPoint(nearest.x / w, nearest.y / h, nearest.x, nearest.y, 1f))
                }
            }
        }

        // 锁定主体四角
        subjectNorm?.let { s ->
            val corners = listOf(
                s.left to s.top, s.right to s.top,
                s.left to s.bottom, s.right to s.bottom
            )
            for ((nx, ny) in corners) {
                lockedPoints.add(LockedPoint(nx, ny, nx * w, ny * h, 0.8f))
            }
        }
    }

    // ==================== FAST 角点检测 ====================

    private fun detectFAST(pixels: IntArray, w: Int, h: Int): List<FeaturePoint> {
        val features = mutableListOf<FeaturePoint>()
        val threshold = 20
        val step = max(1, min(w, h) / GRID_SIZE)

        for (y in 3 until h - 3 step step) {
            for (x in 3 until w - 3 step step) {
                val center = brightness(pixels, x, y, w)

                // FAST-9: 检查圆上16个点是否有连续9个都比中心亮/暗
                val circle = intArrayOf(
                    brightness(pixels, x, y - 3, w),     // 0
                    brightness(pixels, x + 1, y - 3, w),  // 1
                    brightness(pixels, x + 2, y - 2, w),  // 2
                    brightness(pixels, x + 3, y - 1, w),  // 3
                    brightness(pixels, x + 3, y, w),      // 4
                    brightness(pixels, x + 3, y + 1, w),  // 5
                    brightness(pixels, x + 2, y + 2, w),  // 6
                    brightness(pixels, x + 1, y + 3, w),  // 7
                    brightness(pixels, x, y + 3, w),      // 8
                    brightness(pixels, x - 1, y + 3, w),  // 9
                    brightness(pixels, x - 2, y + 2, w),  // 10
                    brightness(pixels, x - 3, y + 1, w),  // 11
                    brightness(pixels, x - 3, y, w),      // 12
                    brightness(pixels, x - 3, y - 1, w),  // 13
                    brightness(pixels, x - 2, y - 2, w),  // 14
                    brightness(pixels, x - 1, y - 3, w)   // 15
                )

                if (isFASTCorner(circle, center, threshold)) {
                    // 计算响应强度
                    var response = 0
                    for (v in circle) response += abs(v - center)
                    features.add(FeaturePoint(x.toFloat(), y.toFloat(), IntArray(0), response.toFloat()))
                }
            }
        }

        // 按响应强度排序，取前 MAX_FEATURES 个
        return features.sortedByDescending { it.response }.take(MAX_FEATURES)
    }

    private fun isFASTCorner(circle: IntArray, center: Int, threshold: Int): Boolean {
        // 检查是否有连续9个点都比中心亮或都比中心暗
        val brighter = BooleanArray(16)
        val darker = BooleanArray(16)
        for (i in 0 until 16) {
            brighter[i] = circle[i] - center > threshold
            darker[i] = center - circle[i] > threshold
        }

        for (start in 0 until 16) {
            var brightCount = 0; var darkCount = 0
            for (j in 0 until 9) {
                if (brighter[(start + j) % 16]) brightCount++
                if (darker[(start + j) % 16]) darkCount++
            }
            if (brightCount >= 9 || darkCount >= 9) return true
        }
        return false
    }

    // ==================== 简化 BRIEF 描述子 ====================

    private fun computeDescriptor(pixels: IntArray, w: Int, h: Int, fp: FeaturePoint): FeaturePoint {
        val x = fp.x.toInt(); val y = fp.y.toInt()
        if (x < 5 || x >= w - 5 || y < 5 || y >= h - 5) return fp.copy(descriptor = IntArray(16))

        // 16字节简化描述子：周围16个点与中心的亮度差
        val center = brightness(pixels, x, y, w)
        val desc = IntArray(16)
        val offsets = arrayOf(
            -3 to -3, 0 to -3, 3 to -3, 3 to 0,
            3 to 3, 0 to 3, -3 to 3, -3 to 0,
            -2 to -2, 2 to -2, 2 to 2, -2 to 2,
            -1 to 0, 1 to 0, 0 to -1, 0 to 1
        )
        for (i in offsets.indices) {
            val (dx, dy) = offsets[i]
            desc[i] = if (brightness(pixels, x + dx, y + dy, w) > center) 1 else 0
        }
        return fp.copy(descriptor = desc)
    }

    // ==================== 特征匹配 ====================

    private fun matchFeatures(current: List<FeaturePoint>, prev: List<FeaturePoint>): List<Pair<FeaturePoint, FeaturePoint>> {
        val matches = mutableListOf<Pair<FeaturePoint, FeaturePoint>>()

        for (c in current) {
            var bestDist = Int.MAX_VALUE; var bestMatch: FeaturePoint? = null
            for (p in prev) {
                val dist = hammingDistance(c.descriptor, p.descriptor)
                if (dist < bestDist) { bestDist = dist; bestMatch = p }
            }
            if (bestDist < MATCH_THRESHOLD && bestMatch != null) {
                matches.add(c to bestMatch)
            }
        }

        return matches
    }

    private fun hammingDistance(a: IntArray, b: IntArray): Int {
        var dist = 0
        for (i in a.indices) if (a[i] != b[i]) dist++
        return dist
    }

    // ==================== 运动估计 ====================

    private fun estimateMotion(matches: List<Pair<FeaturePoint, FeaturePoint>>): SceneMotion {
        if (matches.size < 3) return SceneMotion(0f, 0f, 1f, 0f)

        // 用中位数估计平移
        val dxs = matches.map { it.first.x - it.second.x }.sorted()
        val dys = matches.map { it.first.y - it.second.y }.sorted()
        val medianDx = dxs[dxs.size / 2]
        val medianDy = dys[dys.size / 2]

        // 用匹配点距离比估计缩放
        var scaleSum = 0f; var scaleCount = 0
        for (i in 0 until min(matches.size, 20)) {
            for (j in i + 1 until min(matches.size, 20)) {
                val d1 = dist(matches[i].first, matches[j].first)
                val d2 = dist(matches[i].second, matches[j].second)
                if (d2 > 5f) {
                    scaleSum += d1 / d2; scaleCount++
                }
            }
        }
        val scale = if (scaleCount > 0) scaleSum / scaleCount else 1f

        return SceneMotion(medianDx, medianDy, scale.coerceIn(0.5f, 2f), 0f)
    }

    private fun updateLockedPoints(motion: SceneMotion, w: Int, h: Int) {
        for (lp in lockedPoints) {
            lp.screenX += motion.dx
            lp.screenY += motion.dy
            lp.screenX = lp.screenX.coerceIn(0f, w.toFloat())
            lp.screenY = lp.screenY.coerceIn(0f, h.toFloat())
            lp.confidence *= 0.95f  // 逐渐衰减
        }
        lockedPoints.removeAll { it.confidence < 0.2f }
    }

    private fun brightness(pixels: IntArray, x: Int, y: Int, w: Int): Int {
        val p = pixels[y * w + x]
        return ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
    }

    private fun dist(a: FeaturePoint, b: FeaturePoint): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    fun reset() {
        prevFeatures.clear()
        prevPixels = null
        lockedPoints.clear()
        sceneMotion = SceneMotion(0f, 0f, 1f, 0f)
    }
}
