package com.aicompose.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.aicompose.ai.CompositionEngine

/**
 * AI 构图叠加层
 * 悬浮在小米相机上方，显示构图分析结果
 */
class ComposeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 画笔
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 200, 0)
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val subjectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 200, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val thirdsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    var analysisResult: CompositionEngine.AnalysisResult? = null
        set(value) {
            field = value
            invalidate()
        }

    var lastAction: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var showGrid = true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 三分法网格
        if (showGrid) {
            drawThirdsGrid(canvas, w, h)
        }

        // 2. 主体标记
        drawSubjectMarker(canvas, w, h)

        // 3. 评分面板（左上角）
        drawScorePanel(canvas, w, h)

        // 4. 动作提示（底部）
        drawActionHint(canvas, w, h)
    }

    private fun drawThirdsGrid(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..2) {
            canvas.drawLine(w * i / 3, 0f, w * i / 3, h, thirdsPaint)
            canvas.drawLine(0f, h * i / 3, w, h * i / 3, thirdsPaint)
        }
        // 交叉点
        val dotPaint = Paint(thirdsPaint).apply {
            style = Paint.Style.FILL
        }
        for (i in 1..2) {
            for (j in 1..2) {
                canvas.drawCircle(w * i / 3, h * j / 3, 5f, dotPaint)
            }
        }
    }

    private fun drawSubjectMarker(canvas: Canvas, w: Float, h: Float) {
        val subject = analysisResult?.subject ?: return

        val left = subject.left * w
        val top = subject.top * h
        val right = subject.right * w
        val bottom = subject.bottom * h

        // 半透明填充
        val fillPaint = Paint(subjectPaint).apply {
            style = Paint.Style.FILL
            color = Color.argb(30, 0, 200, 255)
        }
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // 边框
        canvas.drawRect(left, top, right, bottom, subjectPaint)

        // 角标
        val cornerLen = 20f
        val cornerPaint = Paint(subjectPaint).apply {
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }

        // 四个角
        canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
        canvas.drawLine(left, top, left, top + cornerLen, cornerPaint)

        canvas.drawLine(right, top, right - cornerLen, top, cornerPaint)
        canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)

        canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left, bottom - cornerLen, cornerPaint)

        canvas.drawLine(right, bottom, right - cornerLen, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)
    }

    private fun drawScorePanel(canvas: Canvas, w: Float, h: Float) {
        val result = analysisResult ?: return

        val panelW = 180f
        val panelH = 120f
        val panelX = 20f
        val panelY = 60f

        // 背景
        canvas.drawRoundRect(
            panelX, panelY, panelX + panelW, panelY + panelH,
            12f, 12f, bgPaint
        )

        // 评分
        val scoreColor = when {
            result.score >= 80 -> Color.parseColor("#00E676")
            result.score >= 60 -> Color.parseColor("#00E5FF")
            result.score >= 40 -> Color.parseColor("#FFAB00")
            else -> Color.parseColor("#FF1744")
        }
        scorePaint.color = scoreColor
        scorePaint.textSize = 48f
        canvas.drawText("${result.score.toInt()}", panelX + 15, panelY + 55, scorePaint)

        // 分数标签
        labelPaint.textSize = 18f
        canvas.drawText("构图评分", panelX + 85, panelY + 30, labelPaint)

        // 子项分数
        labelPaint.textSize = 16f
        var yOff = panelY + 80f
        val topScores = result.scores.entries.sortedByDescending { it.value }.take(3)
        for ((key, value) in topScores) {
            val name = when (key) {
                "thirds" -> "三分法"
                "golden" -> "黄金比"
                "symmetry" -> "对称"
                "leading" -> "引导线"
                "balance" -> "平衡"
                "depth" -> "纵深"
                "simplicity" -> "简洁"
                "horizon" -> "水平"
                else -> key
            }
            val barW = 60f
            val barH = 6f
            val barX = panelX + 70f
            val barY = yOff - 6f

            // 背景条
            val bgBarPaint = Paint().apply {
                color = Color.argb(60, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, 3f, 3f, bgBarPaint)

            // 进度条
            val fgBarPaint = Paint().apply {
                color = scoreColor
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(barX, barY, barX + barW * value, barY + barH, 3f, 3f, fgBarPaint)

            canvas.drawText(name, panelX + 15, yOff, labelPaint)
            yOff += 20f
        }
    }

    private fun drawActionHint(canvas: Canvas, w: Float, h: Float) {
        val action = lastAction ?: return

        val textW = hintPaint.measureText(action)
        val padding = 24f
        val bgX = (w - textW) / 2 - padding
        val bgY = h - 200f

        // 背景
        val hintBgPaint = Paint(bgPaint).apply {
            color = Color.argb(160, 0, 0, 0)
        }
        canvas.drawRoundRect(
            bgX, bgY,
            bgX + textW + padding * 2, bgY + 50f,
            25f, 25f, hintBgPaint
        )

        // 文字
        canvas.drawText(action, bgX + padding, bgY + 35f, hintPaint)
    }
}
