package com.aliya2qq.bridge.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aliya2qq.bridge.App
import com.aliya2qq.bridge.R
import com.aliya2qq.bridge.ui.MainActivity

/** 可选前台服务：在桥接之外单独保活 NapCat/安装过程。 */
class EngineService : Service() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, EngineService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EngineService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, buildNotification())
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("NapCat 引擎运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
