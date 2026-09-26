package com.aliya2qq.bridge.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** 简易日志：Android Log + 可选文件 + 内存缓冲（供 UI 滚动显示）。 */
object BridgeLog {
    const val TAG = "Aliya2QQ"

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private var file: File? = null
    private var fileReady = false
    val buffer = CopyOnWriteArrayList<String>()
    var maxBuffer = 2000

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun setLogFile(path: String?) {
        fileReady = false
        file = if (path.isNullOrBlank()) null else File(path)
        file?.parentFile?.mkdirs()
        fileReady = true
    }

    fun i(msg: String) = log("I", msg)
    fun w(msg: String) = log("W", msg)
    fun e(msg: String, t: Throwable? = null) {
        log("E", msg + (t?.let { " | $it" } ?: ""))
        t?.let { Log.e(TAG, msg, it) }
    }

    fun d(msg: String) = log("D", msg)

    /** 原始行（安装脚本/二维码等），不加 [bridge] 前缀。 */
    fun raw(msg: String) {
        buffer.add(msg)
        while (buffer.size > maxBuffer) buffer.removeAt(0)
        try {
            if (fileReady && file != null) {
                file!!.appendText(msg + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
        listener?.invoke(msg)
    }

    private fun log(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level [bridge] $msg"
        when (level) {
            "E" -> Log.e(TAG, msg)
            "W" -> Log.w(TAG, msg)
            "D" -> Log.d(TAG, msg)
            else -> Log.i(TAG, msg)
        }
        buffer.add(line)
        while (buffer.size > maxBuffer) buffer.removeAt(0)
        try {
            if (fileReady && file != null) {
                file!!.appendText(line + "\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
        listener?.invoke(line)
    }

    fun clearBuffer() {
        buffer.clear()
    }
}

/** 可重置的异步事件：set 后唤醒所有等待者，wait 可带超时。 */
class AsyncEvent {
    private val lock = Object()
    private var signalled = false
    private val waiters = ArrayList<() -> Unit>()

    fun set() = synchronized(lock) {
        signalled = true
        waiters.toList().forEach { it() }
        waiters.clear()
    }

    fun clear() = synchronized(lock) {
        signalled = false
    }

    suspend fun wait(timeoutMs: Long = Long.MAX_VALUE): Boolean {
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                synchronized(lock) {
                    if (signalled) {
                        cont.resume(true) {}
                        return@suspendCancellableCoroutine
                    }
                    val cb: () -> Unit = {
                        if (cont.isActive) cont.resume(true) {}
                    }
                    waiters.add(cb)
                    cont.invokeOnCancellation {
                        synchronized(lock) { waiters.remove(cb) }
                    }
                }
            }
        } ?: false
    }
}
