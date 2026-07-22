package com.aicompose.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.aicompose.ai.CompositionResult
import com.aicompose.ai.SceneType
import kotlin.math.min

/**
 * AR 构图叠加层
 * 完全透明背景，只绘制引导线、主体框、评分
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var result: CompositionResult? = null
        set(v) { field = v; postInvalidate() }

    var guideMode: GuideMode = GuideMode.THIRDS
        set(v) { field = v; postInvalidate() }

    enum class GuideMode { THIRDS, GOLDEN, DIAGONAL, CENTER, NONE }

    // === 画笔 ===
    private val gridLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val goldenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 215, 0); strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 230, 118); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 230, 118); style = Paint.Style.FILL
    }
    private val subjectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 200, 255); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val subjectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(25, 0, 200, 255); style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 180, 0); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 0, 0, 0); style = Paint.Style.FILL
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val suggPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 220, 0)
        textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 200, 255); strokeWidth = 3f; style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        val r = result ?: return

        // 1. 引导线网格
        drawGuide(c, w, h)

        // 2. 主体检测框 + 角标
        drawSubject(c, w, h, r)

        // 3. AR 移动箭头（引导用户调整主体位置）
        drawArrow(c, w, h, r)

        // 4. 左上角评分面板
        drawScorePanel(c, w, h, r)

        // 5. 底部建议
        drawSuggestions(c, w, h, r)
    }

    private fun drawGuide(c: Canvas, w: Float, h: Float) {
        when (guideMode) {
            GuideMode.THIRDS -> {
                for (i in 1..2) {
                    c.drawLine(w * i / 3, 0f, w * i / 3, h, gridLine)
                    c.drawLine(0f, h * i / 3, w, h * i / 3, gridLine)
                }
                // 交叉点小圆
                for (i in 1..2) for (j in 1..2) {
                    c.drawCircle(w * i / 3, h * j / 3, 6f, crosshairPaint)
                    c.drawCircle(w * i / 3, h * j / 3, 3f, dotPaint)
                }
            }
            GuideMode.GOLDEN -> {
                for (x in listOf(0.382f, 0.618f)) c.drawLine(w * x, 0f, w * x, h, goldenLine)
                for (y in listOf(0.382f, 0.618f)) c.drawLine(0f, h * y, w, h * y, goldenLine)
                for (x in listOf(0.382f, 0.618f)) for (y in listOf(0.382f, 0.618f))
                    c.drawCircle(w * x, h * y, 6f, crosshairPaint)
            }
            GuideMode.DIAGONAL -> {
                c.drawLine(0f, 0f, w, h, gridLine); c.drawLine(w, 0f, 0f, h, gridLine)
                c.drawLine(0f, 0f, w / 2, h, gridLine); c.drawLine(w, 0f, w / 2, h, gridLine)
            }
            GuideMode.CENTER -> {
                c.drawLine(w / 2, 0f, w / 2, h, gridLine); c.drawLine(0f, h / 2, w, h / 2, gridLine)
                c.drawCircle(w / 2, h / 2, min(w, h) * 0.08f, crosshairPaint)
                c.drawCircle(w / 2, h / 2, 3f, dotPaint)
            }
            GuideMode.NONE -> {}
        }
    }

    private fun drawSubject(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val s = r.subject ?: return
        val l = s.left * w; val t = s.top * h; val ri = s.right * w; val b = s.bottom * h

        // 半透明填充
        c.drawRect(l, t, ri, b, subjectFill)

        // 虚线边框
        c.drawRect(l, t, ri, b, subjectStroke)

        // 四角标记
        val cl = 20f
        // 左上
        c.drawLine(l, t, l + cl, t, cornerPaint); c.drawLine(l, t, l, t + cl, cornerPaint)
        // 右上
        c.drawLine(ri, t, ri - cl, t, cornerPaint); c.drawLine(ri, t, ri, t + cl, cornerPaint)
        // 左下
        c.drawLine(l, b, l + cl, b, cornerPaint); c.drawLine(l, b, l, b - cl, cornerPaint)
        // 右下
        c.drawLine(ri, b, ri - cl, b, cornerPaint); c.drawLine(ri, b, ri, b - cl, cornerPaint)

        // 置信度标签
        labelPaint.textSize = 16f
        c.drawText("${(r.subjectConfidence * 100).toInt()}%", l, t - 8f, labelPaint)
    }

    /**
     * AR 箭头 — 从主体当前位置指向推荐三分点
     */
    private fun drawArrow(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val s = r.subject ?: return
        val tp = r.thirdsPoint ?: return

        val cx = (s.left + s.right) / 2 * w
        val cy = (s.top + s.bottom) / 2 * h
        val tx = tp.first * w
        val ty = tp.second * h

        val dx = tx - cx; val dy = ty - cy
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // 只有偏移足够大才显示箭头
        if (dist < w * 0.05f) return

        // 箭头线
        val path = Path()
        path.moveTo(cx, cy)
        path.lineTo(tx, ty)
        c.drawPath(path, arrowPaint)

        // 箭头头部
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        val headLen = 20f
        val a1 = angle + Math.PI * 0.85
        val a2 = angle - Math.PI * 0.85
        c.drawLine(tx, ty, tx + headLen * Math.cos(a1).toFloat(), ty + headLen * Math.sin(a1).toFloat(), arrowPaint)
        c.drawLine(tx, ty, tx + headLen * Math.cos(a2).toFloat(), ty + headLen * Math.sin(a2).toFloat(), arrowPaint)

        // 目标点圆圈
        c.drawCircle(tx, ty, 10f, crosshairPaint)
        c.drawCircle(tx, ty, 4f, dotPaint)
    }

    private fun drawScorePanel(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val pw = 160f; val ph = 120f; val px = 16f; val py = 40f
        c.drawRoundRect(px, py, px + pw, py + ph, 12f, 12f, bgPaint)

        val scoreColor = when {
            r.score >= 80 -> 0xFF00E676.toInt()
            r.score >= 60 -> 0xFF00E5FF.toInt()
            r.score >= 40 -> 0xFFFFAB00.toInt()
            else -> 0xFFFF1744.toInt()
        }

        // 分数
        scorePaint.color = scoreColor; scorePaint.textSize = 44f
        c.drawText("${r.score.toInt()}", px + 12, py + 48, scorePaint)

        // 等级
        labelPaint.textSize = 17f
        c.drawText("${r.gradeEmoji} ${r.grade}", px + 65, py + 28, labelPaint)

        // 场景
        labelPaint.textSize = 14f
        c.drawText("${r.sceneType.icon} ${r.sceneType.label}", px + 65, py + 48, labelPaint)

        // 子项分数条
        var yOff = py + 68f
        labelPaint.textSize = 13f
        for (rule in r.rules.sortedByDescending { it.score }.take(4)) {
            val barX = px + 62f; val barY = yOff - 4f; val barW = 82f; val barH = 5f
            val bgBar = Paint().apply { color = Color.argb(35, 255, 255, 255) }
            val fgBar = Paint().apply { color = scoreColor }
            c.drawRoundRect(barX, barY, barX + barW, barY + barH, 2f, 2f, bgBar)
            c.drawRoundRect(barX, barY, barX + barW * rule.score, barY + barH, 2f, 2f, fgBar)
            c.drawText("${rule.icon}${rule.name}", px + 12, yOff, labelPaint)
            yOff += 17f
        }
    }

    private fun drawSuggestions(c: Canvas, w: Float, h: Float, r: CompositionResult) {
        val suggs = r.suggestions
        if (suggs.isEmpty()) {
            // 构图好时显示鼓励
            if (r.score >= 75) {
                val text = "✅ 构图良好，可以拍摄"
                val tw = suggPaint.measureText(text)
                c.drawRoundRect(w / 2 - tw / 2 - 16f, h - 160f, w / 2 + tw / 2 + 16f, h - 120f, 20f, 20f, bgPaint)
                c.drawText(text, w / 2, h - 132f, suggPaint)
            }
            return
        }

        // 第一条建议（最优先）
        val text = suggs.first()
        val tw = suggPaint.measureText(text)
        val bgX = w / 2 - tw / 2 - 16f; val bgY = h - 160f
        c.drawRoundRect(bgX, bgY, bgX + tw + 32f, bgY + 38f, 19f, 19f, bgPaint)
        c.drawText(text, w / 2, bgY + 28f, suggPaint)
    }

    fun cycleGuide() {
        val modes = GuideMode.entries
        guideMode = modes[(modes.indexOf(guideMode) + 1) % modes.size]
    }
}
