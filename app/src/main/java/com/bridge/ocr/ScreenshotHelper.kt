package com.bridge.ocr

import android.app.Activity
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 截图服务
 * 使用 MediaProjection API 截取屏幕
 */
class ScreenshotHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotHelper"
        const val REQUEST_CODE_SCREENSHOT = 1001

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /**
     * 截图结果
     */
    data class ScreenshotResult(
        val success: Boolean,
        val bitmap: Bitmap? = null,
        val error: String? = null
    )

    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            return Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * 初始化 MediaProjection
     * @param resultCode 从 onActivityResult 获取的 resultCode
     * @param data 从 onActivityResult 获取的 Intent
     */
    fun initMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            Log.d(TAG, "MediaProjection initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaProjection", e)
            false
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = mediaProjection != null

    /**
     * 截取当前屏幕
     */
    suspend fun capture(): ScreenshotResult {
        return suspendCancellableCoroutine { continuation ->
            val projection = mediaProjection
            if (projection == null) {
                continuation.resume(ScreenshotResult(
                    success = false,
                    error = "MediaProjection not initialized"
                ))
                return@suspendCancellableCoroutine
            }

            try {
                val (width, height) = getScreenSize()
                val density = context.resources.displayMetrics.densityDpi

                Log.d(TAG, "Capturing screen: ${width}x${height}, density=$density")

                // 创建 ImageReader
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

                // 创建 VirtualDisplay
                virtualDisplay = projection.createVirtualDisplay(
                    "Screenshot",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    null
                )

                // 等待一帧
                Thread.sleep(100)

                // 获取最新的 Image
                val image: Image? = imageReader?.acquireLatestImage()

                if (image == null) {
                    continuation.resume(ScreenshotResult(
                        success = false,
                        error = "Failed to acquire image"
                    ))
                    return@suspendCancellableCoroutine
                }

                // 转换为 Bitmap
                val bitmap = imageToBitmap(image, width, height)
                image.close()

                // 清理
                virtualDisplay?.release()
                virtualDisplay = null

                Log.d(TAG, "Screenshot captured successfully")

                continuation.resume(ScreenshotResult(
                    success = true,
                    bitmap = bitmap
                ))

            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                continuation.resume(ScreenshotResult(
                    success = false,
                    error = e.message ?: "Screenshot failed"
                ))
            }
        }
    }

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪掉多余的 padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(TAG, "ScreenshotHelper released")
    }
}
