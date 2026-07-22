package com.aicompose.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aicompose.App
import com.aicompose.ui.MainActivity
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 屏幕捕获服务 — 稳定版
 * 帧通过队列传递，不使用 SharedFlow（避免生命周期问题）
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "Capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var resultCode: Int = 0
        @Volatile
        var resultData: Intent? = null

        // 帧队列 — 线程安全，消费者取走即移除
        val frameQueue = ConcurrentLinkedQueue<Bitmap>()

        @Volatile
        private var _running = false
        val isRunning: Boolean get() = _running
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projection: MediaProjection? = null
    private var captureThread: HandlerThread? = null
    private var captureW = 540
    private var captureH = 960

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val data = resultData
        if (data == null) {
            Log.e(TAG, "resultData 为空，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(resultCode, data)

            if (projection == null) {
                Log.e(TAG, "MediaProjection 为 null")
                stopSelf()
                return START_NOT_STICKY
            }

            setupCapture()
            _running = true
            Log.d(TAG, "屏幕捕获已启动 ${captureW}x${captureH}")

        } catch (e: Exception) {
            Log.e(TAG, "启动捕获失败", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun setupCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        val scale = 0.5f
        captureW = (metrics.widthPixels * scale).toInt()
        captureH = (metrics.heightPixels * scale).toInt()
        val dpi = metrics.densityDpi

        // 在后台线程处理帧，避免阻塞主线程
        captureThread = HandlerThread("CaptureThread").apply { start() }
        val handler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image: Image? = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image == null) return@setOnImageAvailableListener

            try {
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    while (frameQueue.size >= 2) {
                        val old = frameQueue.poll()
                        old?.recycle()
                    }
                    frameQueue.offer(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "帧处理异常", e)
            } finally {
                image.close()
            }
        }, handler)  // 指定后台线程 Handler

        virtualDisplay = projection!!.createVirtualDisplay(
            "AICompose", captureW, captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection 停止")
                stopSelf()
            }
        }, null)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureW

            val bmp = Bitmap.createBitmap(
                captureW + rowPadding / pixelStride, captureH,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, captureW, captureH)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap 失败", e)
            null
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, App.CHANNEL_ID)
                .setContentTitle("AI构图助手")
                .setContentText("正在分析取景画面...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("AI构图助手")
                .setContentText("正在分析取景画面...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        _running = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        while (frameQueue.isNotEmpty()) frameQueue.poll()?.recycle()
        super.onDestroy()
        Log.d(TAG, "屏幕捕获服务已销毁")
    }
}
