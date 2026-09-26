package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shell 执行：优先 jniLibs（nativeLibraryDir）中的 ELF，
 * 其次 /system/bin/sh。绝不 exec filesDir 内二进制（SELinux execute_no_trans）。
 */
object ShellExec {
    fun prefixDir(ctx: Context): File = File(ctx.filesDir, "usr")
    fun homeDir(ctx: Context): File = File(ctx.filesDir, "home")
    fun logsDir(ctx: Context): File = File(ctx.filesDir, "logs")
    fun binDir(ctx: Context): File = File(ctx.filesDir, "bin")

    fun baseEnv(ctx: Context): MutableMap<String, String> {
        val prefix = prefixDir(ctx)
        val home = homeDir(ctx)
        val native = NativeExec.nativeDir(ctx).absolutePath
        return mutableMapOf(
            "PREFIX" to prefix.absolutePath,
            "HOME" to home.absolutePath,
            "TMPDIR" to File(ctx.cacheDir, "tmp").absolutePath,
            "PATH" to "$native:${prefix.absolutePath}/bin:/system/bin:/system/xbin",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
            "EXTERNAL_STORAGE" to (System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"),
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )
    }

    data class Result(val code: Int, val stdout: String, val stderr: String)

    fun run(
        ctx: Context,
        command: List<String>,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 0L,
    ): Result {
        val pb = ProcessBuilder(command)
        pb.directory(workDir ?: homeDir(ctx))
        pb.redirectErrorStream(false)
        val e = pb.environment()
        e.putAll(baseEnv(ctx))
        e.putAll(env)
        // 禁止继承 termux-exec：会把可执行路径改写成 com.termux
        if (!env.containsKey("LD_PRELOAD")) e.remove("LD_PRELOAD")
        val logFile = File(logsDir(ctx), "shell.log")
        logFile.parentFile?.mkdirs()
        val pretty = command.joinToString(" ")
        BridgeLog.i("[shell] $pretty")
        try {
            logFile.appendText(">>> $pretty\n")
        } catch (_: Exception) {
        }
        val p = try {
            pb.start()
        } catch (ex: Exception) {
            BridgeLog.e("[shell] start failed", ex)
            return Result(-1, "", ex.toString())
        }
        val outT = StringBuilder()
        val errT = StringBuilder()
        val outThread = Thread {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    outT.append(line).append('\n')
                    try { logFile.appendText(line + "\n") } catch (_: Exception) {}
                    BridgeLog.raw(line!!)
                }
            }
        }
        val errThread = Thread {
            BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    errT.append(line).append('\n')
                    try { logFile.appendText("! $line\n") } catch (_: Exception) {}
                    BridgeLog.raw("!! $line")
                }
            }
        }
        outThread.start()
        errThread.start()
        val finished = if (timeoutMs > 0) p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) else {
            p.waitFor(); true
        }
        if (!finished) {
            p.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            BridgeLog.e("[shell] timeout after ${timeoutMs}ms")
            return Result(-1, outT.toString(), errT.toString() + "\nTIMEOUT")
        }
        outThread.join(2000)
        errThread.join(2000)
        return Result(p.exitValue(), outT.toString(), errT.toString())
    }

    /** 统一入口：系统 sh（Android 必然可执行）。 */
    fun runBash(ctx: Context, script: String, workDir: File? = null, timeoutMs: Long = 0L): Result {
        return NativeExec.bashOrSh(ctx, script, workDir, timeoutMs)
    }

    fun preferredShell(ctx: Context): String = "/system/bin/sh"

    /** jniLibs 是否带了可执行 bash。 */
    fun bootstrapReady(ctx: Context): Boolean = NativeExec.hasBash(ctx)
}
