package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 优先从 APK assets/preload 离线释放，不联网下载。
 *
 * 预置约定（arm64-v8a）：
 *   preload/bootstrap-aarch64.zip   Termux bootstrap
 *   preload/napcat-shell.zip        NapCat.Shell
 *   preload/napcat-install.sh       安装脚本（可选）
 *   preload/debian-rootfs-aarch64.tar.xz  Debian rootfs（可选，有则完全离线）
 */
object PreloadAssets {
    const val DIR = "preload"

    fun list(ctx: Context): List<String> = try {
        ctx.assets.list(DIR)?.toList() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun has(ctx: Context, name: String): Boolean =
        list(ctx).any { it.equals(name, ignoreCase = true) }

    fun hasBootstrap(ctx: Context): Boolean =
        has(ctx, "bootstrap-aarch64.zip") || has(ctx, "bootstrap.zip")

    fun hasRootfs(ctx: Context): Boolean =
        list(ctx).any { it.contains("rootfs") }

    fun hasNapcatPack(ctx: Context): Boolean =
        has(ctx, "napcat-shell.zip")

    fun assetName(ctx: Context, vararg candidates: String): String? =
        list(ctx).firstOrNull { n -> candidates.any { it.equals(n, true) } }

    /** 释放 asset 到 destDir，返回文件。 */
    fun extractTo(ctx: Context, assetName: String, destDir: File, destName: String, onProgress: (String) -> Unit = {}): File {
        destDir.mkdirs()
        val out = File(destDir, destName)
        val assetSize = try {
            ctx.assets.openFd("$DIR/$assetName").use { it.length }
        } catch (_: Exception) {
            -1L
        }
        // 缓存命中且体积一致才复用
        if (out.exists() && out.length() > 1024 && (assetSize <= 0 || out.length() == assetSize)) {
            onProgress("缓存命中 ${out.name} ${out.length() / 1024}KB")
            return out
        }
        onProgress("释放 $assetName …")
        ctx.assets.open("$DIR/$assetName").use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n)
                    total += n
                    if (total % (8L * 1024 * 1024) < 256 * 1024) {
                        onProgress("释放中 ${total / 1024 / 1024}MB")
                    }
                }
            }
        }
        onProgress("已释放 ${out.name} ${out.length() / 1024}KB")
        BridgeLog.i("[preload] $assetName -> ${out.absolutePath}")
        return out
    }

    fun unzipTo(zip: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalPath)) {
                        entry = zin.nextEntry
                        continue
                    }
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                    entry = zin.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            BridgeLog.e("[preload] unzip failed", e)
            false
        }
    }

    /** 用 Java commons-compress 解压 tar.xz，避免 jniLibs/tar 的 linker 限制。 */
    fun extractArchive(ctx: Context, archive: File, destDir: File, onProgress: (String) -> Unit = {}): Boolean {
        destDir.mkdirs()
        onProgress("解压 ${archive.name} 到 ${destDir.name} …")
        return try {
            java.io.FileInputStream(archive).use { fis ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis).use { xz ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                        var entry = tar.nextTarEntry
                        var n = 0
                        while (entry != null) {
                            val name = entry.name.removePrefix("./").removePrefix("/")
                            if (name.isNotBlank() && !entry.isDirectory) {
                                val out = File(destDir, name)
                                if (out.canonicalPath.startsWith(destDir.canonicalPath)) {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it) }
                                    // 恢复执行位（Java tar 不会自动带 mode）
                                    val mode = entry.mode
                                    val exec = (mode and 0b001001001) != 0
                                    if (exec || name.startsWith("usr/bin/") || name.startsWith("bin/") ||
                                        name.startsWith("usr/sbin/") || name.startsWith("sbin/")
                                    ) {
                                        out.setExecutable(true, false)
                                    }
                                    n++
                                    if (n % 2000 == 0) onProgress("已解压 $n 个文件…")
                                }
                            }
                            entry = tar.nextTarEntry
                        }
                        onProgress("解压完成，共 $n 个文件")
                        return n > 0
                    }
                }
            }
        } catch (e: Exception) {
            onProgress("解压失败: ${e.message}")
            com.aliya2qq.bridge.util.BridgeLog.e("[preload] extractArchive", e)
            false
        }
    }
}
