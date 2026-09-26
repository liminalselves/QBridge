package com.aliya2qq.bridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aliya2qq.bridge.App
import com.aliya2qq.bridge.R
import com.aliya2qq.bridge.core.Bridge
import com.aliya2qq.bridge.config.ConfigLoader
import com.aliya2qq.bridge.db.Database
import com.aliya2qq.bridge.misskey.MisskeyClient
import com.aliya2qq.bridge.platforms.createPlatform
import com.aliya2qq.bridge.ui.MainActivity
import com.aliya2qq.bridge.util.BridgeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 前台服务：承载桥接核心的长连接运行。
 */
class BridgeService : Service() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: BridgeService? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }

        fun configFile(ctx: Context): File = File(ctx.filesDir, "config.json")
        fun defaultDataDir(ctx: Context): String = File(ctx.filesDir, "data").absolutePath
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridgeJob: kotlinx.coroutines.Job? = null
    private var db: Database? = null
    private var bridge: Bridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        if (!isRunning) {
            isRunning = true
            startBridge()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        serviceScope.launch {
            try {
                bridge?.stop()
            } catch (e: Exception) {
                BridgeLog.e("停止桥接异常", e)
            }
            try {
                db?.close()
            } catch (_: Exception) {
            }
        }
        serviceScope.cancel()
        BridgeLog.i("桥接已停止")
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
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startBridge() {
        bridgeJob = serviceScope.launch {
            try {
                val cfgPath = configFile(this@BridgeService)
                val cfg = ConfigLoader.load(cfgPath, defaultDataDir(this@BridgeService))
                BridgeLog.setLogFile(cfg.logFile.ifBlank { null })
                val database = Database(File(cfg.dataDir, "bridge.db").absolutePath)
                db = database
                val platform = createPlatform(cfg)
                val misskey = MisskeyClient(cfg)
                val b = Bridge(cfg, database, platform, misskey, serviceScope)
                bridge = b
                platform.setMessageHandler { msg -> b.handlePlatformMessage(msg) }
                b.start()
                BridgeLog.i("桥接启动完成，平台 ${platform.displayName} -> Misskey ${cfg.misskey.host}")
                // 主循环：平台 run_forever（断线重连）
                platform.runForever()
            } catch (e: Exception) {
                BridgeLog.e("桥接运行失败", e)
                isRunning = false
                stopSelf()
            }
        }
    }
}
