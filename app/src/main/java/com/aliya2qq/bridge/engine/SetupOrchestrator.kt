package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File

/**
 * GUI 按钮与引擎层之间的薄编排层：初始化 / 启停 NapCat。
 */
object SetupOrchestrator {
    /** 释放会话修复动作配置（rename / restore / none），引擎读 home 下这份。 */
    fun extractAssets(ctx: Context) {
        try {
            ctx.assets.open("qq_session_action.txt").use { input ->
                val out = File(ShellExec.homeDir(ctx), "qq_session_action.txt")
                out.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            BridgeLog.w("释放 qq_session_action.txt 失败: ${e.message}")
        }
    }

    fun initialize(ctx: Context, onProgress: (String) -> Unit) {
        extractAssets(ctx)
        NapCatManager.ensureInstalled(ctx, onProgress)
    }

    fun startNapCat(ctx: Context, qqUin: String?) {
        extractAssets(ctx)
        NapCatManager.start(ctx, qqUin)
    }

    fun stopAll(ctx: Context) {
        NapCatManager.stop(ctx)
    }
}
