package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线优先安装 LinuxQQ（NTQQ）到 proot rootfs 的 /root/Napcat/opt/QQ。
 * 复用 NapCat-Installer 的布局与注入方式；设备侧用 Java 下载 deb（不依赖 rootfs 网络工具）。
 */
object QQInstaller {
    /** 与 NapCat-Installer 当前目标一致；也支持从 linuxConfig.js 解析。 */
    private val FALLBACK_DEB_URLS = listOf(
        "https://qqdl.gtimg.cn/qqfile/QQNTV2/9.9.36/release/9ee04bef/QQ_3.2.34_260924_arm64_01.deb",
        "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.32/beta/727ce4e5/linuxqq_3.2.30-50828_arm64.deb",
    )

    private const val CONFIG_URL =
        "https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js"

    fun rootfsNapcatDir(ctx: Context): File =
        File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat/root/Napcat")

    fun qqExecutable(ctx: Context): File =
        File(rootfsNapcatDir(ctx), "opt/QQ/qq")

    fun isQQInstalled(ctx: Context): Boolean =
        qqExecutable(ctx).isFile && File(rootfsNapcatDir(ctx), "opt/QQ/resources/app/loadNapCat.js").isFile

    fun ensureQQ(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        synchronized(this) {
            if (isQQInstalled(ctx)) {
                onProgress("NTQQ 已就绪")
                return true
            }
            val base = rootfsNapcatDir(ctx)
            base.mkdirs()

            val deb = findOrDownloadDeb(ctx, onProgress) ?: run {
                onProgress("未拿到 LinuxQQ 安装包")
                return false
            }
            onProgress("解包 ${deb.name} (${deb.length() / 1024 / 1024}MB) …")
            val extracted = extractDeb(deb, base, onProgress)
            val qqBin = File(base, "opt/QQ/qq")
            if (!extracted && !qqBin.isFile) {
                onProgress("解包失败: ${deb.name}")
                return false
            }
            onProgress("注入 NapCat → opt/QQ …")
            injectNapCat(base, onProgress)
            val ok = isQQInstalled(ctx)
            onProgress(if (ok) "NTQQ 安装完成" else "NTQQ 安装后校验失败")
            return ok
        }
    }

    private fun findOrDownloadDeb(ctx: Context, onProgress: (String) -> Unit): File? {
        val cache = File(ctx.cacheDir, "qq")
        cache.mkdirs()
        // 1) 已缓存
        cache.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".deb") && it.length() > 10_000_000 }?.let {
            onProgress("命中缓存 ${it.name}")
            return it
        }
        // 2) assets 预置（优先 linuxqq_arm64.deb）
        val assetDeb = PreloadAssets.assetName(ctx, "linuxqq_arm64.deb", "linuxqq.deb", "QQ.deb")
            ?: PreloadAssets.list(ctx).firstOrNull { it.endsWith(".deb") }
        if (assetDeb != null) {
            onProgress("释放预置 $assetDeb …")
            return PreloadAssets.extractTo(ctx, assetDeb, cache, assetDeb, onProgress)
        }
        // 3) 在线下载（linuxConfig 优先）
        val urls = (listOfNotNull(fetchConfigDebUrl(onProgress)) + FALLBACK_DEB_URLS).distinct()
        for (u in urls) {
            val out = File(cache, "linuxqq_arm64.deb")
            onProgress("下载 NTQQ …")
            if (download(u, out, onProgress)) {
                if (out.length() > 10_000_000) {
                    onProgress("下载完成 ${out.length() / 1024 / 1024}MB")
                    return out
                }
                out.delete()
                onProgress("下载内容异常，换源…")
            }
        }
        return null
    }

    private fun fetchConfigDebUrl(onProgress: (String) -> Unit): String? = try {
        val text = httpGet(CONFIG_URL, 15000)
        val m = Regex("\"deb\":\"(https:[^\"]+arm64[^\"]+\\.deb)\"").find(text)
        val u = m?.groupValues?.get(1)
        if (u != null) onProgress("linuxConfig → $u")
        u
    } catch (e: Exception) {
        onProgress("linuxConfig 不可用: ${e.message}")
        null
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.inputStream.use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.setRequestProperty("Referer", "https://im.qq.com/linuxqq/index.shtml")
            if (conn.responseCode != 200) {
                onProgress("HTTP ${conn.responseCode} $url")
                return false
            }
            val total = conn.contentLengthLong
            FileOutputStream(dest).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var n: Int
                    var got = 0L
                    var last = 0L
                    while (input.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        got += n
                        if (got - last > 8L * 1024 * 1024) {
                            last = got
                            val pct = if (total > 0) " ${got * 100 / total}%" else ""
                            onProgress("下载中 ${got / 1024 / 1024}MB$pct")
                        }
                    }
                }
            }
            dest.length() > 1024
        } catch (e: Exception) {
            BridgeLog.e("[qq] download $url", e)
            onProgress("下载失败: ${e.message}")
            false
        }
    }

    /** deb = ar(data.tar.*)；支持 xz/gz/无压缩。 */
    fun extractDeb(deb: File, destDir: File, onProgress: (String) -> Unit): Boolean {
        destDir.mkdirs()
        return try {
            java.io.FileInputStream(deb).use { fis ->
                org.apache.commons.compress.archivers.ar.ArArchiveInputStream(fis).use { ar ->
                    var entry = ar.nextArEntry
                    var done = false
                    while (entry != null && !done) {
                        val name = entry.name.removePrefix("./")
                        if (name.startsWith("data.tar")) {
                            onProgress("解 data: $name")
                            extractTarStream(openTarPayload(ar, name), destDir, onProgress)
                            done = true
                            // data.tar 会吃光剩余 ar 流，不要再读 nextArEntry
                            break
                        }
                        entry = ar.nextArEntry
                    }
                    done
                }
            }
        } catch (e: Exception) {
            // tar 关闭时可能弄关底层 ar 流；若已解出 QQ 主程序则视为成功
            val qq = File(destDir, "opt/QQ/qq")
            if (qq.isFile && qq.length() > 1000) {
                onProgress("解包收尾异常（已解出 QQ）: ${e.message}")
                true
            } else {
                BridgeLog.e("[qq] extractDeb", e)
                onProgress("解包错误: ${e.message}")
                false
            }
        }
    }

    private fun openTarPayload(raw: InputStream, name: String): InputStream {
        return when {
            name.endsWith(".xz") ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(raw)
            name.endsWith(".gz") ->
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(raw)
            name.endsWith(".zst") ->
                // commons-compress 需 zstd-jni；多数 QQ deb 为 xz。失败则走在线脚本。
                throw IllegalStateException("zstd deb not supported: $name")
            else -> raw
        }
    }

    private fun extractTarStream(input: InputStream, destDir: File, onProgress: (String) -> Unit) {
        try {
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tar ->
                var e = tar.nextTarEntry
                var n = 0
                while (e != null) {
                    val name = e.name.removePrefix("./").removePrefix("/")
                    if (name.isNotBlank()) {
                        val out = File(destDir, name)
                        if (out.canonicalPath.startsWith(destDir.canonicalPath)) {
                            if (e.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { tar.copyTo(it) }
                                val mode = e.mode
                                val exec = (mode and 0b001001001) != 0
                                if (exec || name.endsWith("/qq") || name.contains("opt/QQ/")) {
                                    out.setExecutable(true, false)
                                }
                                n++
                                if (n % 500 == 0) onProgress("已解 $n …")
                            }
                        }
                    }
                    e = tar.nextTarEntry
                }
                onProgress("deb 解出 $n 个文件")
            }
        } catch (ex: Exception) {
            val n = destDir.walkTopDown().count { it.isFile }
            if (n > 100) {
                onProgress("tar 收尾异常（已解 $n 个文件）: ${ex.message}")
            } else {
                throw ex
            }
        }
    }

    /** 对齐官方 install_napcat：拷贝 Shell → app_launcher/napcat，并写 loadNapCat.js。 */
    fun injectNapCat(base: File, onProgress: (String) -> Unit) {
        val qq = File(base, "opt/QQ")
        if (!qq.isDirectory) {
            onProgress("缺少 opt/QQ，跳过注入")
            return
        }
        val target = File(qq, "resources/app/app_launcher/napcat")
        target.mkdirs()
        var copied = 0
        base.listFiles()?.forEach { f ->
            if (f.name == "opt") return@forEach
            val dest = File(target, f.name)
            try {
                if (f.isDirectory) f.copyRecursively(dest, overwrite = true)
                else f.copyTo(dest, overwrite = true)
                copied++
            } catch (e: Exception) {
                BridgeLog.e("[qq] copy ${f.name}", e)
            }
        }
        // 保证入口脚本存在
        if (!File(target, "napcat.mjs").isFile) {
            File(target, "napcat.mjs").writeText("console.log('napcat.mjs missing')\n")
        }
        // WebUI 的 sw.js 生成读 src/assets/sw_template.js，发行包里文件在 static/
        try {
            val swSrc = sequenceOf(
                File(target, "static/sw_template.js"),
                File(target, "sw_template.js"),
                File(base, "static/sw_template.js"),
            ).firstOrNull { it.isFile }
            if (swSrc != null) {
                val swDst = File(target, "src/assets/sw_template.js")
                swDst.parentFile?.mkdirs()
                swSrc.copyTo(swDst, overwrite = true)
            }
        } catch (e: Exception) {
            BridgeLog.e("[qq] sw_template", e)
        }
        val loadJs = File(qq, "resources/app/loadNapCat.js")
        // 路径必须是 proot 内绝对路径
        loadJs.writeText(
            "(async () => {await import('file:///root/Napcat/opt/QQ/resources/app/app_launcher/napcat/napcat.mjs');})();\n",
        )
        val pkg = File(qq, "resources/app/package.json")
        if (pkg.isFile) {
            val text = pkg.readText()
            val patched = text.replace(Regex("\"main\"\\s*:\\s*\"[^\"]*\""), "\"main\": \"./loadNapCat.js\"")
            if (patched != text) pkg.writeText(patched)
            else if (!text.contains("loadNapCat.js")) {
                // 极简补丁
                pkg.writeText(text.replaceFirst("{", "{\n  \"main\": \"./loadNapCat.js\","))
            }
        }
        qq.walkTopDown().forEach { f -> if (f.isFile) f.setExecutable(true, false) }
        onProgress("NapCat 注入完成 (拷贝 $copied 项)")
    }
}
