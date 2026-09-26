package com.aliya2qq.bridge.engine

import android.content.Context
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File

enum class NapCatState {
    NEED_BOOTSTRAP,
    NEED_INSTALL,
    READY,
    INSTALLING,
    STARTING,
    RUNNING,
    WAIT_LOGIN,
    STOPPING,
    ERROR,
}

/**
 * NapCat 生命周期管理：状态机、离线优先的环境安装（bootstrap、rootfs、NTQQ）、
 * 进程启停与探活。
 */
object NapCatManager {
    @Volatile
    var state: NapCatState = NapCatState.NEED_BOOTSTRAP
        private set

    var onState: ((NapCatState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private val stateListeners = mutableListOf<(NapCatState) -> Unit>()

    fun addStateListener(l: (NapCatState) -> Unit) {
        synchronized(stateListeners) { stateListeners.add(l) }
    }

    fun removeStateListener(l: (NapCatState) -> Unit) {
        synchronized(stateListeners) { stateListeners.remove(l) }
    }

    private fun setState(s: NapCatState) {
        state = s
        onState?.invoke(s)
        synchronized(stateListeners) { stateListeners.toList().forEach { it(s) } }
        log("状态切换：$s")
    }

    fun log(msg: String) {
        BridgeLog.i("[napcat] $msg")
        onLog?.invoke(msg)
    }

    fun readyMarker(ctx: Context): File = File(ctx.filesDir, "napcat_ready")
    fun qqUinFile(ctx: Context): File = File(ctx.filesDir, "qq_uin.txt")

    fun refreshState(ctx: Context) {
        if (!ShellExec.bootstrapReady(ctx)) {
            setState(NapCatState.NEED_BOOTSTRAP)
            return
        }
        if (!readyMarker(ctx).exists()) {
            setState(NapCatState.NEED_INSTALL)
            return
        }
        setState(if (isNapCatRunning(ctx)) NapCatState.RUNNING else NapCatState.READY)
    }

    fun isNapCatRunning(ctx: Context): Boolean {
        // $PREFIX/bin/pgrep 被 SELinux execute_no_trans 拒绝，永远返回 NO；
        // 优先直接扫 /proc（QQ 与本应用同 uid，cmdline 可读），toybox 兜底。
        if (scanForQQ()) return true
        val r = ShellExec.runBash(
            ctx,
            "/system/bin/pgrep -f 'opt/QQ/[q]q' >/dev/null 2>&1 && echo YES || echo NO",
            timeoutMs = 12000,
        )
        return r.stdout.contains("YES")
    }

    private fun scanForQQ(): Boolean {
        val procs = java.io.File("/proc").listFiles { f -> f.name.all { it in '0'..'9' } } ?: return false
        for (p in procs) {
            try {
                val cmd = p.resolve("cmdline").readBytes().toString(Charsets.UTF_8)
                if (cmd.contains("opt/QQ/qq")) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    fun ensureInstalled(ctx: Context, onProgress: (String) -> Unit = {}): Boolean {
        synchronized(this) {
            setState(NapCatState.INSTALLING)
            val ok = try {
                if (!NativeExec.hasBash(ctx)) {
                    onProgress("缺少 jniLibs/libbash.so，无法执行")
                    setState(NapCatState.ERROR)
                    return false
                }
                if (!readyMarker(ctx).exists()) {
                    onProgress("正在准备 PREFIX / NapCat 环境…")
                    if (!TermuxBootstrap.install(ctx) { onProgress(it); log(it) }) {
                        setState(NapCatState.ERROR)
                        return false
                    }
                    onProgress("正在安装 NapCat 环境（proot Debian）…")
                    if (!installNapCatStack(ctx) { onProgress(it); log(it) }) {
                        setState(NapCatState.ERROR)
                        return false
                    }
                    readyMarker(ctx).writeText("jniLibs\n")
                }
                // 升级包：即使 ready 也补 NTQQ
                if (!QQInstaller.isQQInstalled(ctx)) {
                    onProgress("预置/安装 NTQQ …")
                    if (!QQInstaller.ensureQQ(ctx) { onProgress(it); log(it) }) {
                        setState(NapCatState.ERROR)
                        return false
                    }
                }
                true
            } catch (e: Exception) {
                BridgeLog.e("ensureInstalled", e)
                onProgress("安装失败: ${e.message}")
                setState(NapCatState.ERROR)
                false
            }
            if (ok) {
                setState(NapCatState.READY)
                onProgress("环境就绪")
            }
            return ok
        }
    }

    /** 离线优先：解压预置 napcat-shell / rootfs；再装 NTQQ 并注入 NapCat。 */
    private fun installNapCatStack(ctx: Context, onProgress: (String) -> Unit): Boolean {
        onProgress("检查 APK 预置包…")
        val rootfsOk = TermuxBootstrap.installRootfsFromAssets(ctx, onProgress)
        val napcatOk = TermuxBootstrap.installNapcatPackFromAssets(ctx, onProgress)
        val libsOk = TermuxBootstrap.installQQAssets(ctx, onProgress)
        onProgress("预置 rootfs=$rootfsOk napcat=$napcatOk qqlibs=$libsOk")
        // NTQQ：预置/缓存 deb，或设备侧按官方地址下载
        onProgress("安装 NTQQ + 注入 NapCat …")
        val qqOk = QQInstaller.ensureQQ(ctx, onProgress)
        if (rootfsOk && napcatOk && qqOk) {
            onProgress("预置包安装完成（含 NTQQ）")
            return true
        }
        if (rootfsOk && qqOk) {
            onProgress("NTQQ 就绪（NapCat 包使用 rootfs 内置）")
            return true
        }
        if (rootfsOk) {
            // rootfs 已好，QQ 可稍后重试；不要走在线脚本
            onProgress("rootfs 就绪，NTQQ 未完成 qq=$qqOk")
            return true
        }
        onProgress("预置不完整 rootfs=$rootfsOk napcat=$napcatOk qq=$qqOk，回退脚本安装…")
        val script = """
            set -e
            echo '[1/4] 安装 proot-distro / screen ...'
            pkg install -y proot-distro screen curl 2>/dev/null || apt install -y proot-distro screen curl
            echo '[2/4] 安装 Debian 容器 (alias=napcat) ...'
            proot-distro install debian --override-alias napcat || true
            echo '[3/4] 容器内安装 NapCat ...'
            proot-distro sh napcat -- bash -c '
              set -e
              apt update -y
              apt install -y sudo curl libgcrypt20 ca-certificates xvfb xauth procps
              curl -fsSL -o /tmp/napcat.sh https://nclatest.znin.net/NapNeko/NapCat-Installer/main/script/install.sh \\
                || curl -fsSL -o /tmp/napcat.sh https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.sh
              bash /tmp/napcat.sh --docker n --cli n
            '
            echo '[4/4] 安装完成'
        """.trimIndent()
        val scriptFile = File(ShellExec.homeDir(ctx), "install_napcat.sh")
        scriptFile.parentFile?.mkdirs()
        scriptFile.writeText(script)
        scriptFile.setExecutable(true, false)
        val r = ShellExec.runBash(ctx, "sh ${scriptFile.absolutePath} 2>&1", timeoutMs = 0)
        return r.code == 0 || r.stdout.contains("安装完成")
    }

    fun start(ctx: Context, qqUin: String? = null): Boolean {
        if (!ShellExec.bootstrapReady(ctx)) {
            log("执行通道未就绪")
            return false
        }
        if (state == NapCatState.NEED_INSTALL && !readyMarker(ctx).exists()) {
            log("环境未就绪，请先初始化")
            return false
        }
        if (isNapCatRunning(ctx)) {
            setState(NapCatState.RUNNING)
            return true
        }
        setState(NapCatState.STARTING)
        TermuxBootstrap.ensureRootfsLinks(ctx) { log(it) }
        if (!QQInstaller.isQQInstalled(ctx)) {
            log("NTQQ 未安装，先补齐…")
            if (!QQInstaller.ensureQQ(ctx) { log(it) }) {
                log("NTQQ 安装失败")
                setState(NapCatState.ERROR)
                return false
            }
        }
        qqUin?.takeIf { it.isNotBlank() }?.let { qqUinFile(ctx).writeText(it.trim()) }
        val uin = qqUin?.trim().orEmpty().ifBlank {
            if (qqUinFile(ctx).exists()) qqUinFile(ctx).readText().trim() else ""
        }
        val qqArgs = if (uin.isNotBlank()) "-q $uin" else ""
        val logFile = File(ShellExec.logsDir(ctx), "napcat.out.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText("")

        // 会话修复动作（files/home/qq_session_action.txt，缺失时读 assets）：rename/restore/none
        val actionFile = File(ShellExec.homeDir(ctx), "qq_session_action.txt")
        val sessionAction = (if (actionFile.isFile) actionFile.readText().trim() else "")
            .ifBlank {
                try {
                    ctx.assets.open("qq_session_action.txt").bufferedReader().use { it.readText().trim() }
                } catch (_: Exception) {
                    ""
                }
            }
            .ifBlank { "none" }
        log("会话动作 sessionAction=$sessionAction")

        val nativeDir = NativeExec.nativeDir(ctx).absolutePath
        val proot = NativeExec.toolPath(ctx, "proot")?.absolutePath
            ?: "$nativeDir/libproot.so"
        val rootfs = File(ShellExec.prefixDir(ctx), "var/lib/proot-distro/installed-rootfs/napcat")
        val tmpDir = File(ctx.filesDir, "proot-tmp")
        tmpDir.mkdirs()
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)
        File(tmpDir, ".X11-unix").mkdirs()
        File(tmpDir, ".X11-unix").setWritable(true, false)
        File(tmpDir, "shm").mkdirs()
        File(tmpDir, "shm").setWritable(true, false)
        // qq-libs.tar 只含 lib/ 不含 usr/，老清单永远不满足导致每次启动都重复解压 175MB；
        // 改为：关键库缺失 或 资产版本变化时才解压，用标记文件记录已处理过的资产长度。
        val criticalLibs = listOf(
            File(rootfs, "lib/aarch64-linux-gnu/libxcb-render.so.0"),
            File(rootfs, "lib/aarch64-linux-gnu/libbz2.so.1.0"),
            File(rootfs, "lib/aarch64-linux-gnu/libplc4.so"),
        )
        val libsMarker = File(ctx.filesDir, "qq_libs_done")
        val assetLen = libsTarAssetLen(ctx)
        val markerValid = libsMarker.isFile && libsMarker.readText().trim().toLongOrNull() == assetLen
        if (criticalLibs.any { !it.isFile } || !markerValid) {
            log("补齐 QQ 运行库…")
            val okLibs = TermuxBootstrap.installQQAssets(ctx) { log(it) }
            TermuxBootstrap.ensureRuntimeLinks(rootfs) { log(it) }
            if (okLibs && criticalLibs.all { it.isFile }) {
                libsMarker.writeText(assetLen.toString())
                log("QQ 运行库标记 libsMarker=$assetLen")
            } else {
                log("QQ 运行库补齐未完成，下次启动重试")
            }
        }
        // WebUI sw.js 依赖 src/assets/sw_template.js
        val napTarget = File(rootfs, "root/Napcat/opt/QQ/resources/app/app_launcher/napcat")
        val swDst = File(napTarget, "src/assets/sw_template.js")
        val staticSrc = sequenceOf(
            File(rootfs, "root/Napcat/static"),
            File(ctx.filesDir, "opt/Napcat/static"),
        ).firstOrNull { it.isDirectory }
        if (!swDst.isFile && staticSrc != null) {
            val swSrc = File(staticSrc, "sw_template.js")
            if (swSrc.isFile) {
                swDst.parentFile?.mkdirs()
                swSrc.copyTo(swDst, overwrite = true)
                log("已补齐 sw_template.js")
            }
        }
        // WebUI 静态资源必须完整，否则懒加载 JS 会被 SPA 兜底成 text/html
        val keyAsset = File(napTarget, "static/assets/qq_login-BTnYtD5Z.js")
        val mainAsset = File(napTarget, "static/assets/index-DwG8CNol.js")
        log("WebUI main=${mainAsset.isFile}/${mainAsset.length()} lazy=${keyAsset.isFile}/${keyAsset.length()}")
        if (staticSrc != null && (!keyAsset.isFile || keyAsset.length() < 100 || !mainAsset.isFile)) {
            log("同步 WebUI 静态资源…")
            try {
                staticSrc.copyRecursively(File(napTarget, "static"), overwrite = true)
                log("WebUI assets=${File(napTarget, "static/assets").list()?.size ?: 0} lazy2=${keyAsset.isFile}/${keyAsset.length()}")
            } catch (e: Exception) {
                log("同步静态资源失败: ${e.message}")
            }
        }
        val bashPath = File(rootfs, "usr/bin/bash")
        val loader = listOf(
            File(rootfs, "lib/ld-linux-aarch64.so.1"),
            File(rootfs, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
        ).firstOrNull { it.exists() }
        log("proot=$proot")
        log("rootfs=${rootfs.absolutePath} exists=${rootfs.isDirectory}")
        log("bash exists=${bashPath.isFile} exec=${bashPath.canExecute()} size=${bashPath.length()}")
        log("loader exists=${loader != null} path=${loader?.absolutePath ?: "MISSING"}")
        log("libtinfo6=${File(rootfs, "lib/aarch64-linux-gnu/libtinfo.so.6").exists()}")
        log("prootLoader=${File(nativeDir, "libprootloader.so").exists()} ${File(nativeDir, "libprootloader32.so").exists()}")
        log("prootTmp=${tmpDir.absolutePath} w=${tmpDir.canWrite()}")

        val prootLoader = "$nativeDir/libprootloader.so"
        val prootLoader32 = "$nativeDir/libprootloader32.so"
        // proot 硬编码默认 loader 在 com.termux，复制到本地 PREFIX 并用 env 覆盖
        val loaderDir = File(ShellExec.prefixDir(ctx), "libexec/proot")
        loaderDir.mkdirs()
        File(nativeDir, "libprootloader.so").copyTo(File(loaderDir, "loader"), overwrite = true)
        File(nativeDir, "libprootloader32.so").copyTo(File(loaderDir, "loader32"), overwrite = true)
        File(loaderDir, "loader").setExecutable(true, false)
        File(loaderDir, "loader32").setExecutable(true, false)
        val script = """
            set -x
            unset LD_PRELOAD
            export LD_LIBRARY_PATH="$nativeDir"
            export PROOT_TMP_DIR="${tmpDir.absolutePath}"
            export TMPDIR="${'$'}PROOT_TMP_DIR"
            export PROOT_NO_SECCOMP=1
            export SESSION_ACTION="$sessionAction"
            export PROOT_LOADER="$prootLoader"
            export PROOT_LOADER_32="$prootLoader32"
            chmod +x "$prootLoader" "$prootLoader32" "$proot" 2>/dev/null || true
            mkdir -p "${'$'}PROOT_TMP_DIR"
            chmod 700 "${'$'}PROOT_TMP_DIR" 2>/dev/null || true
            echo "PROOT_LOADER=${'$'}PROOT_LOADER $(test -x "$prootLoader" && echo Y || echo N)" >>"${logFile.absolutePath}" 2>&1
            $proot -0 /system/bin/sh -c 'echo PROOT_HOST_OK' >>"${logFile.absolutePath}" 2>&1
            $proot -0 --rootfs=$rootfs -w / /usr/bin/bash -c 'echo NAPCAT_IN_PROOT; pwd; ls /root/Napcat/opt/QQ/qq' >>"${logFile.absolutePath}" 2>&1
            $proot --rootfs=$rootfs -w / -b /proc -b /dev -b /sys -b ${tmpDir.absolutePath}:/tmp -b ${tmpDir.absolutePath}/shm:/dev/shm \
              /usr/bin/bash -c '
              export HOME=/root
              export TMPDIR=/tmp
              export XDG_RUNTIME_DIR=/tmp
              export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
              export LIBGL_ALWAYS_SOFTWARE=1
              export ELECTRON_DISABLE_SECURITY_WARNINGS=1
              if [ "${'$'}SESSION_ACTION" = "rename" ] && [ -d /root/.config/QQ ] && [ ! -d /root/.config/QQ.bak ]; then
                mv /root/.config/QQ /root/.config/QQ.bak && echo QQ_SESSION_RENAMED
              fi
              if [ "${'$'}SESSION_ACTION" = "restore" ] && [ -d /root/.config/QQ.bak ] && [ ! -d /root/.config/QQ ]; then
                mv /root/.config/QQ.bak /root/.config/QQ && echo QQ_SESSION_RESTORED
              fi
              mkdir -p /tmp /tmp/.X11-unix /dev/shm /root /root/.config/QQ/NapCat/temp /root/.config/QQ/nt_qq /root/.config/QQ/crash_files 2>/dev/null || true
              chmod 1777 /tmp /tmp/.X11-unix /dev/shm 2>/dev/null || true
              chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true
              echo NAPCAT_IN_PROOT
              ls -la /root/Napcat/opt/QQ/qq 2>&1 | head -3
              if [ -f /root/Napcat/opt/QQ/qq ]; then
                if [ -x /usr/bin/Xvfb ]; then
                  rm -f /tmp/.X99-lock /tmp/.X11-unix/X99 2>/dev/null || true
                  # Xvfb 的 link() 在 proot 下 EPERM，预创建锁文件绕过
                  printf '%%s' "$$" > /tmp/.X99-lock
                  /usr/bin/Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -ac >/root/xvfb.log 2>&1 &
                  export DISPLAY=:99
                  sleep 2
                  echo Xvfb_pid=$!
                  cat /root/xvfb.log 2>&1 | tail -15
                  ls -la /tmp/.X11-unix 2>&1 | head -5
                fi
                # Electron/Chromium 可走 headless，X 起不来时仍能跑 NapCat WebUI
                export ELECTRON_OZONE_PLATFORM_HINT=headless
                /root/Napcat/opt/QQ/qq --no-sandbox --ozone-platform=headless --disable-gpu --disable-dev-shm-usage ${qqArgs}
              else
                echo "QQ missing" >&2
                ls /root/Napcat 2>&1 | head >&2
                exit 3
              fi
            ' >>"${logFile.absolutePath}" 2>&1
        """.trimIndent()
        val scriptFile = File(ShellExec.homeDir(ctx), "start_napcat.sh")
        scriptFile.writeText(script)
        scriptFile.setExecutable(true, false)

        val startCmd = "nohup ${ShellExec.preferredShell(ctx)} -c 'sh ${scriptFile.absolutePath}' >/dev/null 2>&1 & echo STARTED"
        val r = ShellExec.runBash(ctx, startCmd, timeoutMs = 10000)
        log("启动: ${r.stdout.trim()} ${r.stderr.trim()}")
        Thread.sleep(2000)
        // QQ/proot 冷启动较慢，轮询到进程出现再切状态
        var up = false
        for (i in 0 until 15) {
            if (isNapCatRunning(ctx)) {
                up = true
                break
            }
            Thread.sleep(1000)
        }
        return if (up) {
            setState(NapCatState.WAIT_LOGIN)
            true
        } else {
            // 可能仍在拉起，保持 STARTING 并再延迟刷新一次
            setState(NapCatState.STARTING)
            Thread {
                Thread.sleep(8000)
                if (isNapCatRunning(ctx)) setState(NapCatState.WAIT_LOGIN)
            }.start()
            true
        }
    }

    fun stop(ctx: Context) {
        setState(NapCatState.STOPPING)
        killOwnProcesses()
        ShellExec.runBash(
            ctx,
            "/system/bin/pkill -f 'opt/QQ/[q]q' 2>/dev/null; /system/bin/pkill -f 'start_napcat[.]sh' 2>/dev/null; /system/bin/pkill -f 'proot-[d]istro' 2>/dev/null; /system/bin/pkill -f '[X]vfb' 2>/dev/null; echo STOPPED",
            timeoutMs = 15000,
        )
        Thread.sleep(800)
        if (scanForQQ()) {
            killOwnProcesses()
            Thread.sleep(500)
        }
        setState(if (ShellExec.bootstrapReady(ctx) && readyMarker(ctx).exists()) NapCatState.READY else NapCatState.NEED_INSTALL)
    }

    /** SELinux 禁止 exec $PREFIX 里的 pkill；QQ 及其包装进程与本应用同 uid，直接扫 /proc kill。 */
    private fun killOwnProcesses() {
        val procs = java.io.File("/proc").listFiles { f -> f.name.all { it in '0'..'9' } } ?: return
        val myPid = android.os.Process.myPid()
        for (p in procs) {
            val pid = p.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            try {
                val cmd = p.resolve("cmdline").readBytes().toString(Charsets.UTF_8)
                if (cmd.contains("opt/QQ/qq") || cmd.contains("start_napcat.sh") ||
                    cmd.contains("libproot") || cmd.contains("proot-distro") || cmd.contains("Xvfb")
                ) {
                    android.os.Process.killProcess(pid)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun libsTarAssetLen(ctx: Context): Long {
        return try {
            val name = PreloadAssets.assetName(ctx, "qq-libs.tar")
            if (name.isNullOrBlank()) -1L else ctx.assets.open(name).use { it.available().toLong() }
        } catch (_: Exception) {
            -1L
        }
    }

    fun openWebUiUrl(): String = "http://127.0.0.1:6099"

    @Volatile
    var webUiToken: String? = null
        private set

    /** 从任意日志行识别 WebUI Token（兼容 ANSI 色码 / URL 形式）。 */
    fun noteWebUiToken(line: String) {
        val cleaned = line.replace(Regex("\\u001b\\[[0-9;]*m"), "")
        val t = Regex("WebUi Token:\\s*([0-9a-fA-F]{6,})").find(cleaned)?.groupValues?.get(1)
            ?: Regex("token=([0-9a-fA-F]{6,})").find(cleaned)?.groupValues?.get(1)
        if (!t.isNullOrBlank()) {
            webUiToken = t
        }
    }

    fun resolveWebUiToken(ctx: android.content.Context): String? {
        webUiToken?.let { return it }
        val logFile = File(ShellExec.logsDir(ctx), "napcat.out.log")
        try {
            if (logFile.isFile) {
                // 只扫文件尾部，避免大日志 OOM
                val raf = java.io.RandomAccessFile(logFile, "r")
                val len = raf.length()
                val start = (len - 256 * 1024).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt().coerceAtMost(256 * 1024))
                raf.readFully(bytes)
                raf.close()
                val text = String(bytes, Charsets.UTF_8)
                text.lineSequence().forEach { noteWebUiToken(it) }
            }
        } catch (_: Exception) {
        }
        return webUiToken
    }

    /** 带 token 的 WebUI 登录地址。 */
    fun webUiUrl(ctx: android.content.Context): String {
        val token = resolveWebUiToken(ctx)
        return if (token.isNullOrBlank()) {
            "http://127.0.0.1:6099/webui"
        } else {
            "http://127.0.0.1:6099/webui?token=$token"
        }
    }
}
