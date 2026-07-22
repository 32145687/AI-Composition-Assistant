package com.aicompose.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.aicompose.ai.CompositionResult
import kotlin.math.min

/**
 * AR 构图叠加层 — 三分网格 + 主体标记 + 评分 + 建议
 */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var result: CompositionResult? = null
        set(v) { field = v; postInvalidate() }

    var guideMode: GuideMode = GuideMode.THIRDS
        set(v) { field = v; postInvalidate() }

    enum class GuideMode { THIRDS, GOLDEN, DIAGONAL, CENTER, NONE }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val goldenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 215, 0); strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }
    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 200, 255); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private val subjectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 0, 200, 255); style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 52f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); textSize = 20f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 0); textSize = 26f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 1f, 1f, Color.BLACK); textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 230, 118); style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()

        drawGuide(c, w, h)
        drawSubject(c, w, h)
        drawScorePanel(c, w, h)
        drawSuggestions(c, w, h)
    }

    private fun drawGuide(c: Canvas, w: Float, h: Float) {
        when (guideMode) {
            GuideMode.THIRDS -> {
                for (i in 1..2) {
                    c.drawLine(w*i/3, 0f, w*i/3, h, linePaint)
                    c.drawLine(0f, h*i/3, w, h*i/3, linePaint)
                }
                for (i in 1..2) for (j in 1..2) c.drawCircle(w*i/3, h*j/3, 5f, dotPaint)
            }
            GuideMode.GOLDEN -> {
                val gx = listOf(0.382f, 0.618f); val gy = listOf(0.382f, 0.618f)
                for (x in gx) c.drawLine(w*x, 0f, w*x, h, goldenPaint)
                for (y in gy) c.drawLine(0f, h*y, w, h*y, goldenPaint)
            }
            GuideMode.DIAGONAL -> {
                c.drawLine(0f, 0f, w, h, linePaint); c.drawLine(w, 0f, 0f, h, linePaint)
            }
            GuideMode.CENTER -> {
                c.drawLine(w/2, 0f, w/2, h, linePaint); c.drawLine(0f, h/2, w, h/2, linePaint)
                c.drawCircle(w/2, h/2, min(w,h)*0.1f, linePaint)
            }
            GuideMode.NONE -> {}
        }
    }

    private fun drawSubject(c: Canvas, w: Float, h: Float) {
        val s = result?.subject ?: return
        val l = s.left*w; val t = s.top*h; val r = s.right*w; val b = s.bottom*h
        c.drawRect(l, t, r, b, subjectFill)
        c.drawRect(l, t, r, b, subjectPaint)
        val cl = 18f
        for (corner in listOf(
            floatArrayOf(l,t,l+cl,t,l,t+cl), floatArrayOf(r,t,r-cl,t,r,t+cl),
            floatArrayOf(l,b,l+cl,b,l,b-cl), floatArrayOf(r,b,r-cl,b,r,b-cl)
        )) {
            c.drawLine(corner[0],corner[1],corner[2],corner[3],subjectPaint)
            c.drawLine(corner[0],corner[1],corner[4],corner[5],subjectPaint)
        }
    }

    private fun drawScorePanel(c: Canvas, w: Float, h: Float) {
        val r = result ?: return
        val pw = 170f; val ph = 130f; val px = 16f; val py = 50f
        c.drawRoundRect(px, py, px+pw, py+ph, 14f, 14f, bgPaint)

        val scoreColor = when { r.score >= 80 -> 0xFF00E676.toInt(); r.score >= 60 -> 0xFF00E5FF.toInt()
            r.score >= 40 -> 0xFFFFAB00.toInt(); else -> 0xFFFF1744.toInt() }
        scorePaint.color = scoreColor; scorePaint.textSize = 46f
        c.drawText("${r.score.toInt()}", px+14, py+50, scorePaint)
        labelPaint.textSize = 18f
        c.drawText("${r.gradeEmoji} ${r.grade}", px+70, py+30, labelPaint)

        var yOff = py + 70f
        labelPaint.textSize = 15f
        for (rule in r.rules.sortedByDescending { it.score }.take(4)) {
            val barX = px + 65f; val barY = yOff - 5f; val barW = 80f; val barH = 5f
            val bgBar = Paint().apply { color = Color.argb(40, 255, 255, 255) }
            val fgBar = Paint().apply { color = scoreColor }
            c.drawRoundRect(barX, barY, barX+barW, barY+barH, 2f, 2f, bgBar)
            c.drawRoundRect(barX, barY, barX+barW*rule.score, barY+barH, 2f, 2f, fgBar)
            c.drawText("${rule.icon} ${rule.name}", px+14, yOff, labelPaint)
            yOff += 18f
        }
    }

    private fun drawSuggestions(c: Canvas, w: Float, h: Float) {
        val suggs = result?.suggestions ?: return
        if (suggs.isEmpty()) return
        val text = suggs.first()
        val tw = hintPaint.measureText(text)
        val pad = 20f; val bgX = w/2 - tw/2 - pad; val bgY = h - 180f
        c.drawRoundRect(bgX, bgY, bgX+tw+pad*2, bgY+44f, 22f, 22f, bgPaint)
        c.drawText(text, w/2, bgY+32f, hintPaint)
    }

    fun cycleGuide() {
        val modes = GuideMode.entries
        guideMode = modes[(modes.indexOf(guideMode) + 1) % modes.size]
    }
}
