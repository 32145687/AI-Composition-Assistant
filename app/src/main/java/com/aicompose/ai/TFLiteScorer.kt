package com.aicompose.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.ln

/**
 * TFLite 深度学习评分封装
 *
 * 当前模型: MobileNet V1 量化版 (4.1MB)
 * - 输入: [1, 224, 224, 3] uint8
 * - 输出: [1, 1001] float32 (ImageNet 类别概率)
 *
 * 评分策略 (基于 MobileNet 输出的启发式):
 * - 高置信度单主体 → 构图聚焦，分数高
 * - 多类别均匀分布 → 画面杂乱，分数低
 * - 结合算法层的构图法则做最终融合
 *
 * 替换为 NIMA 模型后，评分会更准确
 */
class TFLiteScorer(private val context: Context) {

    companion object {
        private const val TAG = "TFLite"
        private const val MODEL_FILE = "nima_aesthetic.tflite"
        private const val INPUT_SIZE = 224
    }

    private var interpreter: Interpreter? = null
    private var outputSize = 1001  // 默认 MobileNet
    val isAvailable: Boolean

    init {
        isAvailable = try {
            val model = loadModel()
            if (model != null) {
                val opts = Interpreter.Options().apply { setNumThreads(4) }
                interpreter = Interpreter(model, opts)

                // 读取实际输出维度
                val interp = interpreter!!
                val outShape = interp.getOutputTensor(0).shape()
                outputSize = if (outShape.isNotEmpty()) outShape.last() else 1001

                Log.d(TAG, "模型加载成功, 输出维度: $outputSize")
                true
            } else {
                Log.w(TAG, "模型文件不存在: $MODEL_FILE")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败", e)
            false
        }
    }

    private fun loadModel(): MappedByteBuffer? {
        return try {
            val fd = context.assets.openFd(MODEL_FILE)
            val stream = FileInputStream(fd.fileDescriptor)
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 深度学习美学评分
     * @return 0-100 分数，null 表示模型不可用
     */
    fun score(bitmap: Bitmap): Float? {
        val interp = interpreter ?: return null

        return try {
            val t0 = System.currentTimeMillis()

            // 1. 预处理
            val input = preprocess(bitmap)

            // 2. 推理
            val output = Array(1) { FloatArray(outputSize) }
            interp.run(input, output)

            // 3. 后处理
            val score = postprocess(output[0])

            val elapsed = System.currentTimeMillis() - t0
            Log.d(TAG, "DL评分: ${String.format("%.1f", score)} (${elapsed}ms)")

            score
        } catch (e: Exception) {
            Log.e(TAG, "推理失败", e)
            null
        }
    }

    /**
     * 预处理: Bitmap → 模型输入
     */
    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }

        if (resized !== bitmap) resized.recycle()
        return buffer
    }

    /**
     * 后处理: 模型输出 → 美学分数
     *
     * 策略:
     * - NIMA 模型 (output=10): 加权平均 → 0-100
     * - MobileNet (output=1001): 启发式评分
     *   * 最大概率高的 → 主体明确 → 加分
     *   * 信息熵低的 → 画面简洁 → 加分
     */
    private fun postprocess(output: FloatArray): Float {
        return if (outputSize == 10) {
            // NIMA 风格: 加权平均
            val probs = softmax(output)
            var nimaScore = 0f
            for (i in probs.indices) nimaScore += (i + 1) * probs[i]
            ((nimaScore - 1f) / 9f * 100f).coerceIn(0f, 100f)
        } else {
            // MobileNet 启发式
            mobileNetAestheticScore(output)
        }
    }

    /**
     * 从 MobileNet 输出推导美学分数
     *
     * 原理:
     * 1. 最大概率 → 主体明确度 (高=好)
     * 2. 信息熵 → 画面复杂度 (低=简洁=好)
     * 3. Top-5 集中度 → 主题聚焦度 (高=好)
     */
    private fun mobileNetAestheticScore(logits: FloatArray): Float {
        // Softmax
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sumExps = exps.sum()
        val probs = FloatArray(exps.size) { exps[it] / sumExps }

        // 指标1: 最大概率 (主体明确度)
        val maxProb = probs.maxOrNull() ?: 0f
        val subjectClarity = (maxProb * 3f).coerceIn(0f, 1f)  // 归一化

        // 指标2: 信息熵 (简洁度)
        var entropy = 0f
        for (p in probs) {
            if (p > 1e-10f) entropy -= p * ln(p.toDouble()).toFloat()
        }
        val maxEntropy = ln(probs.size.toDouble()).toFloat()
        val simplicity = (1f - entropy / maxEntropy).coerceIn(0f, 1f)

        // 指标3: Top-5 集中度 (聚焦度)
        val sorted = probs.sortedDescending()
        val top5Sum = sorted.take(5).sum()
        val focus = top5Sum.coerceIn(0f, 1f)

        // 加权融合
        val rawScore = subjectClarity * 0.4f + simplicity * 0.35f + focus * 0.25f

        // 映射到 30-90 范围 (避免极端分数)
        return (30f + rawScore * 60f).coerceIn(0f, 100f)
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sum = exps.sum()
        return FloatArray(exps.size) { exps[it] / sum }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
