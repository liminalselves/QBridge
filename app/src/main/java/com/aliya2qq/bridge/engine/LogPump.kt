package com.aliya2qq.bridge.engine

import com.aliya2qq.bridge.util.BridgeLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Tail 日志文件 → BridgeLog，并识别 ASCII 二维码块 / 登录相关输出。
 */
class LogPump(
    private val file: File,
    private val onLine: (String) -> Unit = {},
    private val onQrBlock: (List<String>) -> Unit = {},
    private val onImage: (File) -> Unit = {},
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var offset = 0L
    private val qrBuffer = ArrayList<String>()
    private var inQr = false
    private var lastImage: File? = null

    fun start() {
        if (running) return
        running = true
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("")
        offset = 0L
        thread = Thread {
            while (running) {
                try {
                    pumpOnce()
                    scanImages()
                } catch (e: Exception) {
                    BridgeLog.e("LogPump", e)
                }
                Thread.sleep(400)
            }
        }.also {
            it.isDaemon = true
            it.name = "log-pump"
            it.start()
        }
    }

    fun stop() {
        running = false
        thread = null
    }

    private fun pumpOnce() {
        if (!file.exists()) return
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < offset) offset = 0
            if (raf.length() == offset) return
            raf.seek(offset)
            var line: String?
            while (raf.readLine().also { line = it } != null) {
                val l = line ?: break
                // RandomAccessFile.readLine returns latin1-ish; fix unicode
                val text = String(l.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                handleLine(text)
            }
            offset = raf.filePointer
        }
    }

    private fun handleLine(line: String) {
        onLine(line)
        BridgeLog.raw(line)

        val trimmed = line.trimEnd()
        if (isQrLine(trimmed)) {
            if (!inQr) {
                inQr = true
                qrBuffer.clear()
                onLine("—— 检测到登录二维码 ——")
            }
            qrBuffer.add(trimmed)
        } else {
            if (inQr && qrBuffer.size >= 8) {
                onQrBlock(qrBuffer.toList())
                onLine("—— 二维码结束，请使用 QQ 扫码 ——")
            }
            inQr = false
            qrBuffer.clear()
        }

        val lower = line.lowercase()
        if (lower.contains("qrcode") || lower.contains("二维码") || lower.contains("scan") ||
            lower.contains("login") || lower.contains("请登录")
        ) {
            onLine("【提示】$line")
        }
    }

    private fun isQrLine(s: String): Boolean {
        if (s.length < 10) return false
        // 常见字符画二维码：█▀▄ ■□ ##  等
        val block = s.count { it in "█▀▄■□" }
        val hash = s.count { it == '#' }
        val space = s.count { it == ' ' }
        val pipe = s.count { it in "|│" }
        if (block >= 5) return true
        if (hash >= 8 && space >= 4) return true
        if (pipe >= 5 && (block + hash) >= 3) return true
        return false
    }

    private fun scanImages() {
        val dir = file.parentFile ?: return
        val imgs = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") &&
                f.name.contains("qr", ignoreCase = true)
        } ?: return
        val newest = imgs.maxByOrNull { it.lastModified() }
        if (newest != null && newest != lastImage && newest.length() > 100) {
            lastImage = newest
            onImage(newest)
        }
    }
}
