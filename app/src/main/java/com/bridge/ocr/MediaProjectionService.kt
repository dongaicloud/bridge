package com.bridge.ocr

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bridge.BridgeApp
import com.bridge.MainActivity

/**
 * MediaProjection 前台服务
 * 用于 MediaProjection API 要求的前台服务
 */
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val NOTIFICATION_ID = 2001

        var isRunning = false
            private set

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaProjectionService onCreate")
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MediaProjectionService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MediaProjectionService onDestroy")
        isRunning = false
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BridgeApp.CHANNEL_ID)
            .setContentTitle("Bridge 投屏服务")
            .setContentText("正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
