package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File

/**
 * PREFIX 安装：优先 assets/preload 离线释放，不联网下载。
 */
object TermuxBootstrap {
    fun detectArch(): String {
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        return when {
            abis.contains("arm64-v8a") -> "aarch64"
            abis.contains("armeabi-v7a") || abis.contains("armeabi") -> "arm"
            abis.contains("x86_64") -> "x86_64"
            abis.contains("x86") -> "i686"
            else -> "aarch64"
        }
    }

    fun isInstalled(ctx: Context): Boolean = ShellExec.bootstrapReady(ctx)

    fun install(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val prefix = ShellExec.prefixDir(ctx)
        val home = ShellExec.homeDir(ctx)
        val cache = File(ctx.cacheDir, "preload")
        prefix.mkdirs(); home.mkdirs(); cache.mkdirs()
        ShellExec.logsDir(ctx).mkdirs()

        if (!NativeExec.hasBash(ctx)) {
            onProgress("缺少 jniLibs/libbash.so")
            return false
        }

        // 1) bootstrap 从 assets 解压
        val zipName = PreloadAssets.assetName(ctx, "bootstrap-aarch64.zip", "bootstrap.zip")
        if (zipName == null) {
            onProgress("assets 中无 bootstrap-aarch64.zip")
            return false
        }
        onProgress("从 APK 释放 bootstrap …")
        val zip = PreloadAssets.extractTo(ctx, zipName, cache, zipName, onProgress)
        onProgress("解压 bootstrap 到 $prefix")
        if (!PreloadAssets.unzipTo(zip, prefix)) {
            onProgress("bootstrap 解压失败")
            return false
        }
        rewriteBootstrapPaths(ctx, onProgress)
        File(prefix, "bin").listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        File(prefix, "libexec").listFiles()?.forEach { if (it.isFile) it.setExecutable(true, false) }
        File(home, ".termux").mkdirs()
        File(home, ".termux/termux.properties").writeText("use-black-ui=true\n")

        val r = ShellExec.runBash(ctx, "echo bootstrap-ok", timeoutMs = 15000)
        onProgress("bootstrap 验证 code=${r.code}")
        return true
    }

    /** 确保 merged-usr / loader / soname 符号链接存在（可重复调用）。 */
    fun ensureRootfsLinks(ctx: Context, onProgress: (String) -> Unit = {}) {
        val dest = File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat")
        if (!dest.isDirectory) return
        val links = mapOf(
            "bin" to "usr/bin",
            "sbin" to "usr/sbin",
            "lib" to "usr/lib",
            "lib64" to "usr/lib64",
        )
        var n = 0
        links.forEach { (link, target) ->
            val f = File(dest, link)
            try {
                if (!f.exists()) {
                    java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(target))
                    n++
                }
            } catch (_: Exception) {
            }
        }
        if (n > 0) onProgress("rootfs 符号链接 +$n")
        ensureRuntimeLinks(dest, onProgress)
        // 修复可执行位
        var execN = 0
        listOf("usr/bin", "usr/sbin", "bin", "sbin").forEach { rel ->
            File(dest, rel).walkTopDown().forEach { f ->
                if (f.isFile) {
                    try {
                        f.setExecutable(true, false)
                        execN++
                    } catch (_: Exception) {
                    }
                }
            }
        }
        val bash = File(dest, "usr/bin/bash")
        if (bash.isFile) bash.setExecutable(true, false)
    }

    /**
     * 预置 rootfs 的 tar 在打包时丢掉了全部符号链接。
     * 动态装载需要：/lib/ld-linux-aarch64.so.1 以及 soname（libtinfo.so.6 等）。
     */
    fun ensureRuntimeLinks(dest: File, onProgress: (String) -> Unit = {}) {
        if (!dest.isDirectory) return
        var created = 0

        fun link(link: File, target: String) {
            try {
                val p = link.toPath()
                if (java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
                link.parentFile?.mkdirs()
                if (link.isDirectory) return
                java.nio.file.Files.createSymbolicLink(
                    p,
                    java.nio.file.Paths.get(target),
                )
                created++
            } catch (_: Exception) {
            }
        }

        val loaderReal = listOf(
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        ).map { File(dest, it) }.firstOrNull { it.isFile && it.length() > 0 }

        if (loaderReal != null) {
            // bash/env 的 PT_INTERP = /lib/ld-linux-aarch64.so.1
            // proot 对 rootfs 内符号链接解析不稳，这里直接拷贝真实文件
            listOf(
                "lib/ld-linux-aarch64.so.1",
                "usr/lib/ld-linux-aarch64.so.1",
                "lib64/ld-linux-aarch64.so.1",
                "usr/lib64/ld-linux-aarch64.so.1",
            ).forEach { rel ->
                val out = File(dest, rel)
                try {
                    out.parentFile?.mkdirs()
                    if (java.nio.file.Files.isSymbolicLink(out.toPath())) {
                        out.delete()
                    }
                    if (!out.isFile || out.length() != loaderReal.length()) {
                        loaderReal.copyTo(out, overwrite = true)
                        out.setExecutable(true, false)
                        created++
                    }
                } catch (_: Exception) {
                }
            }
        } else {
            onProgress("警告: 未找到 ld-linux-aarch64.so.1")
        }

        // /bin/sh 缺失会导致 shebang 脚本全挂；拷 dash/bash 作真实文件
        val shSrc = listOf("usr/bin/dash", "bin/dash", "usr/bin/bash", "bin/bash")
            .map { File(dest, it) }.firstOrNull { it.isFile && it.length() > 0 }
        if (shSrc != null) {
            listOf("bin/sh", "usr/bin/sh").forEach { rel ->
                val out = File(dest, rel)
                try {
                    out.parentFile?.mkdirs()
                    if (java.nio.file.Files.isSymbolicLink(out.toPath())) out.delete()
                    if (!out.isFile || out.length() != shSrc.length()) {
                        shSrc.copyTo(out, overwrite = true)
                        out.setExecutable(true, false)
                        created++
                    }
                } catch (_: Exception) {
                }
            }
        }

        // libfoo.so.1.2.3 → libfoo.so.1（拷贝，不依赖 symlink）
        val sonameRe = Regex("^(.+\\.so\\.(\\d+))(?:\\.\\d+)+$")
        listOf(
            "lib/aarch64-linux-gnu",
            "usr/lib/aarch64-linux-gnu",
            "lib",
            "usr/lib",
        ).forEach { rel ->
            val dir = File(dest, rel)
            val files = dir.listFiles() ?: return@forEach
            val names = files.map { it.name }.toSet()
            files.forEach { f ->
                if (!f.isFile) return@forEach
                val m = sonameRe.matchEntire(f.name) ?: return@forEach
                val soname = m.groupValues[1]
                if (soname in names) return@forEach
                try {
                    val out = File(dir, soname)
                    if (java.nio.file.Files.isSymbolicLink(out.toPath())) out.delete()
                    if (!out.isFile) {
                        f.copyTo(out, overwrite = true)
                        created++
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (created > 0) onProgress("runtime 链接 +$created (loader/soname)")
        // /tmp 必须是可写真实目录（xvfb/node 会写这里）
        try {
            val tmp = File(dest, "tmp")
            if (java.nio.file.Files.isSymbolicLink(tmp.toPath())) tmp.delete()
            if (!tmp.isDirectory) tmp.mkdirs()
            tmp.setWritable(true, false)
        } catch (_: Exception) {
        }
        // NapCat Worker 启动需要 /root/.config/QQ/NapCat/temp
        listOf(
            "root/.config/QQ/NapCat/temp",
            "root/.config/QQ/nt_qq",
            "root/.config/QQ",
        ).forEach { rel ->
            try {
                val d = File(dest, rel)
                if (!d.isDirectory) d.mkdirs()
                d.setWritable(true, false)
                d.setExecutable(true, false)
            } catch (_: Exception) {
            }
        }
    }

    fun installRootfsFromAssets(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val dest = File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat")
        if (File(dest, "usr/bin/bash").isFile && File(dest, "usr/bin/env").isFile) {
            onProgress("rootfs 已存在，跳过解压")
            ensureRootfsLinks(ctx, onProgress)
            return true
        }
        val name = PreloadAssets.assetName(ctx, "debian-rootfs-aarch64.tar.xz", "debian-rootfs.tar.xz", "rootfs-aarch64.tar.xz")
            ?: PreloadAssets.list(ctx).firstOrNull { it.contains("rootfs") }
        if (name == null) {
            onProgress("未预置 Debian rootfs")
            return false
        }
        val cache = File(ctx.cacheDir, "preload")
        val archive = PreloadAssets.extractTo(ctx, name, cache, name, onProgress)
        val ok = PreloadAssets.extractArchive(ctx, archive, dest, onProgress)
        if (ok) {
            // 恢复 merged-usr 必要符号链接（解压时因 Windows 权限跳过）
            val links = mapOf(
                "bin" to "usr/bin",
                "sbin" to "usr/sbin",
                "lib" to "usr/lib",
                "lib64" to "usr/lib64",
            )
            var n = 0
            links.forEach { (link, target) ->
                val f = File(dest, link)
                try {
                    if (!f.exists()) {
                        java.nio.file.Files.createSymbolicLink(
                            f.toPath(),
                            java.nio.file.Paths.get(target),
                        )
                        n++
                    }
                } catch (_: Exception) {
                }
            }
            onProgress("已创建 $n 个 rootfs 符号链接")
            ensureRuntimeLinks(dest, onProgress)
            // 批量恢复可执行位
            var execN = 0
            listOf("usr/bin", "usr/sbin", "bin", "sbin").forEach { rel ->
                File(dest, rel).walkTopDown().forEach { f ->
                    if (f.isFile && f.setExecutable(true, false)) execN++
                }
            }
            val bash = File(dest, "usr/bin/bash")
            if (bash.isFile) {
                bash.setExecutable(true, false)
                onProgress("bash executable=${bash.canExecute()} size=${bash.length()}")
            } else {
                onProgress("警告: usr/bin/bash 不存在")
            }
            onProgress("已设置 $execN 个文件可执行")
        }
        return ok
    }

    fun installNapcatPackFromAssets(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val name = PreloadAssets.assetName(ctx, "napcat-shell.zip", "NapCat.Shell.zip")
        if (name == null) {
            onProgress("未预置 napcat-shell.zip")
            return false
        }
        val cache = File(ctx.cacheDir, "preload")
        val zip = PreloadAssets.extractTo(ctx, name, cache, name, onProgress)
        val dest = File(ctx.filesDir, "opt/Napcat")
        dest.mkdirs()
        onProgress("解压 NapCat.Shell …")
        val ok = PreloadAssets.unzipTo(zip, dest)
        // 同步到 proot rootfs 内，供 QQ 注入使用
        val rootNapcat = File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat/root/Napcat")
        if (ok && File(dest, "napcat.mjs").isFile && !File(rootNapcat, "napcat.mjs").isFile) {
            rootNapcat.mkdirs()
            dest.copyRecursively(rootNapcat, overwrite = true)
            onProgress("已同步 NapCat → rootfs/root/Napcat")
        }
        return ok
    }

    /** 解压 QQ 运行库到 rootfs（GTK/NSS/X11 等）。 */
    fun installQQAssets(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        val name = PreloadAssets.assetName(ctx, "qq-libs.tar")
        if (name == null) {
            onProgress("未预置 qq-libs.tar")
            return false
        }
        val dest = File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat")
        if (!dest.isDirectory) return false
        val cache = File(ctx.cacheDir, "preload")
        val archive = PreloadAssets.extractTo(ctx, name, cache, name, onProgress)
        onProgress("解压 QQ 运行库 …")
        return try {
            java.io.FileInputStream(archive).use { fis ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(fis).use { tar ->
                    var e = tar.nextTarEntry
                    var n = 0
                    while (e != null) {
                        val rel = e.name.removePrefix("./").removePrefix("/")
                        if (rel.isNotBlank() && !e.isDirectory) {
                            val out = File(dest, rel)
                            if (out.canonicalPath.startsWith(dest.canonicalPath)) {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { tar.copyTo(it) }
                                n++
                            }
                        }
                        e = tar.nextTarEntry
                    }
                    onProgress("QQ 运行库 +$n")
                    n > 0
                }
            }
        } catch (ex: Exception) {
            onProgress("QQ 运行库解压失败: ${ex.message}")
            false
        }
    }

    private fun rewriteBootstrapPaths(ctx: Context, onProgress: (String) -> Unit) {
        val oldPrefix = "/data/data/com.termux/files"
        val dataDir = ctx.filesDir.parentFile?.absolutePath ?: "/data/data/${ctx.packageName}"
        val newPrefix = "$dataDir/files"
        if (oldPrefix == newPrefix) return
        onProgress("重写 bootstrap 路径")
        var count = 0
        File(ShellExec.prefixDir(ctx), "bin").walkTopDown().forEach { f ->
            if (!f.isFile || f.length() == 0L || f.length() > 8L * 1024 * 1024) return@forEach
            try {
                val bytes = f.readBytes()
                if (bytes.any { b -> b == 0.toByte() }) return@forEach
                val text = String(bytes, Charsets.UTF_8)
                if (!text.contains(oldPrefix)) return@forEach
                f.writeText(text.replace(oldPrefix, newPrefix), Charsets.UTF_8)
                f.setExecutable(true, false)
                count++
            } catch (_: Exception) {
            }
        }
        onProgress("路径重写 $count 处")
    }
}
