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
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.aicompose.App
import com.aicompose.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 屏幕捕获服务
 * 通过 MediaProjection 捕获屏幕画面，转为 Bitmap 供 AI 分析
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_SCALE = 0.5f  // 捕获分辨率缩放（降低以提高性能）

        private var mediaProjection: MediaProjection? = null

        // 帧流 - 供 AI 分析引擎消费
        private val _frameFlow = MutableSharedFlow<Bitmap>(replay = 1, extraBufferCapacity = 2)
        val frameFlow: SharedFlow<Bitmap> = _frameFlow

        // 捕获状态
        val isCapturing = MutableSharedFlow<Boolean>(replay = 1)

        fun setProjection(projection: MediaProjection) {
            mediaProjection = projection
        }
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth = 540
    private var captureHeight = 1200
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "屏幕捕获服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection 为空，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        setupCapture(projection)
        return START_STICKY
    }

    private fun setupCapture(projection: MediaProjection) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        captureWidth = (metrics.widthPixels * CAPTURE_SCALE).toInt()
        captureHeight = (metrics.heightPixels * CAPTURE_SCALE).toInt()
        val dpi = metrics.densityDpi

        Log.d(TAG, "捕获分辨率: ${captureWidth}x${captureHeight}, DPI=$dpi")

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    serviceScope.launch {
                        _frameFlow.emit(bitmap)
                        isCapturing.emit(true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "帧处理失败", e)
            } finally {
                image.close()
            }
        }, null)

        virtualDisplay = projection.createVirtualDisplay(
            "AIComposeCapture",
            captureWidth, captureHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        Log.d(TAG, "屏幕捕获已启动")

        // 监听投影停止
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection 已停止")
                stopSelf()
            }
        }, null)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * captureWidth

        val bitmap = Bitmap.createBitmap(
            captureWidth + rowPadding / pixelStride,
            captureHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪掉 padding
        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, App.CHANNEL_ID)
            .setContentTitle("AI构图助手")
            .setContentText("正在分析取景画面...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        serviceScope.launch { isCapturing.emit(false) }
        super.onDestroy()
        Log.d(TAG, "屏幕捕获服务已销毁")
    }
}
