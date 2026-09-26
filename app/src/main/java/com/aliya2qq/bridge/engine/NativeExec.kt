package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File

/**
 * 从 APK 的 jniLibs（nativeLibraryDir）执行 ELF。
 * 该目录 SELinux 允许 execute，可绕过 app_data_file 的 execute_no_trans 限制。
 *
 * 约定：把可执行文件打成 lib&lt;name&gt;.so 放进 jniLibs/&lt;abi&gt;/，
 * 例如 libbash.so / libbusybox.so / libproot.so。
 */
object NativeExec {
    fun nativeDir(ctx: Context): File =
        File(ctx.applicationInfo.nativeLibraryDir)

    fun toolPath(ctx: Context, name: String): File? {
        val dir = nativeDir(ctx)
        val candidates = listOf(
            File(dir, "lib${name}.so"),
            File(dir, name),
            File(dir, "lib${name}.so"),
        )
        val hit = candidates.firstOrNull { it.exists() && it.length() > 0 }
        if (hit == null) {
            BridgeLog.w("[native] miss ${name} in ${dir.absolutePath} exists=${dir.exists()} list=${dir.list()?.joinToString(",")}")
        }
        return hit
    }

    fun bash(ctx: Context): File? = toolPath(ctx, "bash")
    fun tar(ctx: Context): File? = toolPath(ctx, "tar")
    fun xz(ctx: Context): File? = toolPath(ctx, "xz")

    fun hasBash(ctx: Context): Boolean {
        val b = bash(ctx) != null
        if (!b) {
            BridgeLog.w("[native] hasBash=false nativeDir=${nativeDir(ctx).absolutePath}")
        }
        return b
    }

    fun hasTar(ctx: Context): Boolean = tar(ctx) != null

    fun env(ctx: Context): MutableMap<String, String> {
        val n = nativeDir(ctx).absolutePath
        return ShellExec.baseEnv(ctx).apply {
            // 让动态链接器优先在 jniLibs 找依赖 so
            val old = get("LD_LIBRARY_PATH")
            put("LD_LIBRARY_PATH", if (old.isNullOrBlank()) n else "$n:$old")
            val path = get("PATH") ?: ""
            put("PATH", "$n:$path")
        }
    }

    /** 优先 /system/bin/sh（Android 自带、必然可执行）。
     *  jniLibs 的 libbash.so 在本机仍报 syntax error，仅把 tar/xz 当工具用。 */
    fun bashOrSh(ctx: Context, script: String, workDir: File? = null, timeoutMs: Long = 0L): ShellExec.Result {
        return ShellExec.run(ctx, listOf("/system/bin/sh", "-c", script), workDir, env(ctx), timeoutMs)
    }
}
