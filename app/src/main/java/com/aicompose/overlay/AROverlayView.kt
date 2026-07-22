package com.aicompose.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.aicompose.ai.CompositionResult
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AR 构图叠加层 — 场景感知 + 动态引导
 *
 * 功能:
 * 1. 三分/黄金/对角/中心 引导线（跟随场景运动）
 * 2. 主体检测框 + 四角标记
 * 3. AR 移动箭头（从主体指向推荐位置）
 * 4. 消失点标记 + 透视引导线
 * 5. 地平线指示器
 * 6. 实时评分面板
 * 7. 底部建议文字
 */
class AROverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var result: CompositionResult? = null
        set(v) { field = v; postInvalidate() }

    // 场景运动偏移（像素）
    var motionDx = 0f; var motionDy = 0f

    var guideMode: GuideMode = GuideMode.THIRDS
        set(v) { field = v; postInvalidate() }

    enum class GuideMode { THIRDS, GOLDEN, DIAGONAL, CENTER, NONE }

    // === 画笔 ===
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val goldenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 215, 0); strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 230, 118); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 230, 118); style = Paint.Style.FILL
    }
    private val subjectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 200, 255); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val subjectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 0, 200, 255); style = Paint.Style.FILL
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 200, 255); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 180, 0); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 180, 0); style = Paint.Style.FILL
    }
    private val vpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 100, 100); strokeWidth = 1.5f
        style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val vpDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 100, 100); style = Paint.Style.FILL
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 100, 200, 255); strokeWidth = 2f
        style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0); style = Paint.Style.FILL
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val suggPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 220, 0); textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 200, 255); style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        val r = result

        // 场景运动偏移（AR 跟随）
        val ox = motionDx; val oy = motionDy

        // 1. 引导线（始终绘制，即使没有分析结果）
        drawGuide(c, w, h, ox, oy)

        if (r == null) {
            // 还没有分析结果时显示等待提示
            val text = "等待 AI 分析..."
            suggPaint.textSize = 22f
            val tw = suggPaint.measureText(text)
            c.drawRoundRect(w/2 - tw/2 - 14f, h - 150f, w/2 + tw/2 + 14f, h - 115f, 18f, 18f, bgPaint)
            c.drawText(text, w/2, h - 125f, suggPaint)
            return
        }

        // 1. 引导线（跟随场景偏移）
        drawGuide(c, w, h, ox, oy)

        // 2. 场景边缘点（可视化）
        drawEdgePoints(c, w, h, r)

        // 3. 消失点 + 透视引导线
        drawVanishingPoint(c, w, h, r)

        // 4. 地平线
        drawHorizon(c, w, h, r)

        // 5. 主体框
        drawSubject(c, w, h, r)

        // 6. AR 箭头
        drawArrow(c, w, h, r)

        // 7. 评分面板
        drawScorePanel(c, w, h, r)

        // 8. 建议
        drawSuggestions(c, w, h, r)
    }

    private fun drawGuide(c: Canvas, w: Float, h: Float, ox: Float, oy: Float) {
        when (guideMode) {
            GuideMode.THIRDS -> {
                for (i in 1..2) {
                    c.drawLine(w * i / 3 + ox, 0f, w * i / 3 + ox, h, gridLine)
                    c.drawLine(0f, h * i / 3 + oy, w, h * i / 3 + oy, gridLine)
                }
                for (i in 1..2) for (j in 1..2) {
                    c.drawCircle(w * i / 3 + ox, h * j / 3 + oy, 7f, crossPaint)
                    c.drawCircle(w * i / 3 + ox, h * j / 3 + oy, 3f, dotPaint)
                }
            }
            GuideMode.GOLDEN -> {
                for (x in listOf(0.382f, 0.618f)) c.drawLine(w * x + ox, 0f, w * x + ox, h, goldenLine)
                for (y in listOf(0.382f, 0.618f)) c.drawLine(0f, h * y + oy, w, h * y + oy, goldenLine)
                for (x in listOf(0.382f, 0.618f)) for (y in listOf(0.382f, 0.618f))
                    c.drawCircle(w * x + ox, h * y + oy, 7f, crossPaint)
            }
            GuideMode.DIAGONAL -> {
                c.drawLine(ox, oy, w + ox, h + oy, gridLine)
                c.drawLine(w + ox, oy, ox, h + oy, gridLine)
            }
            GuideMode.CENTER -> {
                c.drawLine(w / 2 + ox, 0f, w / 2 + ox, h, gridLine)
                c.drawLine(0f, h / 2 + oy, w, h / 2 + oy, gridLine)
                c.drawCircle(w / 2 + ox, h / 2 + oy, min(w, h) * 0.08f, crossPaint)
            }
            GuideMode.NONE -> {}
        }
    }

    private fun drawEdgePoints(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        for (pt in r.strongEdges.take(60)) {
            c.drawCircle(pt.x * w, pt.y * h, 2f, edgePaint)
        }
    }

    private fun drawVanishingPoint(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val vp = r.vanishingPoint ?: return
        val vpx = vp.x * w; val vpy = vp.y * h

        // 十字标记
        val sz = 15f
        c.drawLine(vpx - sz, vpy, vpx + sz, vpy, vpPaint)
        c.drawLine(vpx, vpy - sz, vpx, vpy + sz, vpPaint)
        c.drawCircle(vpx, vpy, 5f, vpDotPaint)

        // 从四角画透视引导线到消失点
        val corners = listOf(0f to 0f, w to 0f, 0f to h, w to h)
        for ((cx, cy) in corners) {
            c.drawLine(cx, cy, vpx, vpy, vpPaint)
        }

        labelPaint.textSize = 14f
        c.drawText("VP", vpx + 10, vpy - 10, labelPaint)
    }

    private fun drawHorizon(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val hy = r.horizonY ?: return
        val y = hy * h
        c.drawLine(0f, y, w, y, horizonPaint)

        // 水平标记
        labelPaint.textSize = 14f
        c.drawText("水平线", 10f, y - 8f, labelPaint)
    }

    private fun drawSubject(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val s = r.subject ?: return
        val l = s.left * w; val t = s.top * h; val ri = s.right * w; val b = s.bottom * h

        c.drawRect(l, t, ri, b, subjectFill)
        c.drawRect(l, t, ri, b, subjectStroke)

        val cl = 18f
        c.drawLine(l, t, l + cl, t, cornerPaint); c.drawLine(l, t, l, t + cl, cornerPaint)
        c.drawLine(ri, t, ri - cl, t, cornerPaint); c.drawLine(ri, t, ri, t + cl, cornerPaint)
        c.drawLine(l, b, l + cl, b, cornerPaint); c.drawLine(l, b, l, b - cl, cornerPaint)
        c.drawLine(ri, b, ri - cl, b, cornerPaint); c.drawLine(ri, b, ri, b - cl, cornerPaint)

        labelPaint.textSize = 15f
        c.drawText("${(r.subjectConfidence * 100).toInt()}%", l + 2, t - 8f, labelPaint)
    }

    private fun drawArrow(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val s = r.subject ?: return
        val tp = r.thirdsPoint ?: return
        val cx = (s.left + s.right) / 2 * w; val cy = (s.top + s.bottom) / 2 * h
        val tx = tp.first * w; val ty = tp.second * h
        val dx = tx - cx; val dy = ty - cy
        val dist = sqrt(dx * dx + dy * dy)

        if (dist < w * 0.04f) return

        // 箭头线
        c.drawLine(cx, cy, tx, ty, arrowPaint)

        // 箭头头部（三角形）
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        val headLen = 18f; val headAngle = Math.PI * 0.82
        val ax1 = tx + headLen * cos(angle + headAngle).toFloat()
        val ay1 = ty + headLen * sin(angle + headAngle).toFloat()
        val ax2 = tx + headLen * cos(angle - headAngle).toFloat()
        val ay2 = ty + headLen * sin(angle - headAngle).toFloat()
        val path = Path()
        path.moveTo(tx, ty); path.lineTo(ax1, ay1); path.lineTo(ax2, ay2); path.close()
        c.drawPath(path, arrowFill)

        // 目标圆圈
        c.drawCircle(tx, ty, 10f, crossPaint)
        c.drawCircle(tx, ty, 4f, dotPaint)

        // 距离文字
        val moveText = when {
            abs(dx) > abs(dy) * 2 -> if (dx > 0) "→ 右移" else "← 左移"
            abs(dy) > abs(dx) * 2 -> if (dy > 0) "↓ 下移" else "↑ 上移"
            else -> if (dx > 0) "↘" else if (dx < 0) "↙" else if (dy > 0) "↓" else "↑"
        }
        labelPaint.textSize = 18f
        labelPaint.color = Color.argb(220, 255, 200, 0)
        c.drawText(moveText, (cx + tx) / 2 - 20, (cy + ty) / 2 - 10, labelPaint)
        labelPaint.color = Color.argb(200, 255, 255, 255)
    }

    private fun drawScorePanel(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val pw = 155f; val ph = 115f; val px = 14f; val py = 36f
        c.drawRoundRect(px, py, px + pw, py + ph, 12f, 12f, bgPaint)

        val sc = when {
            r.score >= 80 -> 0xFF00E676.toInt(); r.score >= 60 -> 0xFF00E5FF.toInt()
            r.score >= 40 -> 0xFFFFAB00.toInt(); else -> 0xFFFF1744.toInt()
        }

        scorePaint.color = sc; scorePaint.textSize = 42f
        c.drawText("${r.score.toInt()}", px + 12, py + 46, scorePaint)
        labelPaint.textSize = 16f
        c.drawText("${r.gradeEmoji} ${r.grade}", px + 60, py + 26, labelPaint)
        labelPaint.textSize = 13f
        c.drawText("${r.sceneType.icon} ${r.sceneType.label}", px + 60, py + 44, labelPaint)

        var yOff = py + 64f
        labelPaint.textSize = 12f
        for (rule in r.rules.sortedByDescending { it.score }.take(4)) {
            val barX = px + 58f; val barY = yOff - 4f; val barW = 80f; val barH = 4f
            c.drawRoundRect(barX, barY, barX + barW, barY + barH, 2f, 2f,
                Paint().apply { color = Color.argb(30, 255, 255, 255) })
            c.drawRoundRect(barX, barY, barX + barW * rule.score, barY + barH, 2f, 2f,
                Paint().apply { color = sc })
            c.drawText("${rule.icon}${rule.name}", px + 12, yOff, labelPaint)
            yOff += 16f
        }
    }

    private fun drawSuggestions(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val suggs = r.suggestions
        if (suggs.isEmpty()) {
            if (r.score >= 75) {
                val text = "✅ 构图良好，可以拍摄"
                val tw = suggPaint.measureText(text)
                c.drawRoundRect(w / 2 - tw / 2 - 14f, h - 150f, w / 2 + tw / 2 + 14f, h - 115f, 18f, 18f, bgPaint)
                c.drawText(text, w / 2, h - 125f, suggPaint)
            }
            return
        }
        val text = suggs.first()
        val tw = suggPaint.measureText(text)
        val bgX = w / 2 - tw / 2 - 14f; val bgY = h - 150f
        c.drawRoundRect(bgX, bgY, bgX + tw + 28f, bgY + 35f, 18f, 18f, bgPaint)
        c.drawText(text, w / 2, bgY + 26f, suggPaint)
    }

    fun cycleGuide() {
        val modes = GuideMode.entries
        guideMode = modes[(modes.indexOf(guideMode) + 1) % modes.size]
    }
}
