# NapCat 桥接 APK 构建说明

单包离线方案：Android 应用（GUI）+ 内嵌 proot Debian + NTQQ + NapCat。
仅 **arm64-v8a**，内部分发。

产出：`app-release.apk`（约 **628MB**，含预置 NTQQ、rootfs 与运行库）。

当前版本：**1.6.4-typing-fast** / versionCode 14（在 `app/build.gradle.kts` 查看/修改）。

---

## 1. 环境要求

| 组件 | 路径 / 版本 |
|------|-------------|
| JDK | `D:\Windows8.1\jdk21`（Java 21） |
| Android SDK | `F:\env\android-sdk`（compileSdk 35） |
| Gradle | `F:\env\gradle-8.7` |
| Gradle 缓存 | `F:\env\gradle_home`（勿与其它工程混用） |
| 项目 | `F:\env\qbridge_unified` |

**约束：一次只跑一个 Gradle 任务**，并行会写坏 build 目录。

**环境变量坑**：`ANDROID_SDK_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_PREFS_ROOT` 任一存在都会导致
`AndroidLocationsBuildService ... Could not create provider` 构建失败（报错在 build.gradle.kts 第 1 行，具有迷惑性）。
构建前必须移除（见 §3.2）。

---

## 2. 预置资源（assets/preload）

这些文件打进 APK，安装时离线释放，不依赖外网。

| 文件 | 约 | 作用 |
|------|-----|------|
| `bootstrap-aarch64.zip` | 29MB | Termux 风格 PREFIX（bin/lib 等） |
| `debian-rootfs-aarch64-full.tar.xz` | 163MB | Debian bookworm arm64 rootfs（含 xvfb、NapCat 等） |
| `linuxqq_arm64.deb` | 196MB | LinuxQQ / NTQQ arm64 |
| `napcat-shell.zip` | 28MB | NapCat.Shell |
| `qq-libs.tar` | 175MB | QQ/GTK/NSS/X11 等动态库（**仅含 `lib/`，无 `usr/` 条目**） |
| `napcat-install.sh` | 小 | 在线安装脚本（备用，预置齐全时不走） |
| `qq_session_action.txt` | 小 | 会话修复动作：`rename` / `restore` / `none`（见 §5.3） |

### 2.1 rootfs 与 QQ 包

- rootfs 可用官方 Debian arm64 + 所需 deb 打成 tar.xz。文件名含 `rootfs` 即可被识别
  （精确名 `debian-rootfs-aarch64.tar.xz` 失败后会按 `contains("rootfs")` 兜底）。
- QQ 使用官方 arm64 deb（如 `QQ_3.2.32_260812_arm64_01.deb`），重命名为 `linuxqq_arm64.deb` 放入 preload。
- 下载源可参考 NapCat-Installer 或 `https://cdn-go.cn/qq-web/im.qq.com_new/latest/rainbow/linuxConfig.js` 中的 `armDownloadUrl.deb`。

### 2.2 qq-libs.tar

rootfs 里往往缺 QQ 直接依赖（`libnspr4`、`libnss3`、GTK、`libxcb-render` 等）。
从 Debian 仓库下载对应 arm64 deb，抽出 `lib/aarch64-linux-gnu/*.so*`，并生成 soname 拷贝（如 `libtinfo.so.6.4` → `libtinfo.so.6`），打成 `qq-libs.tar`。

安装时由 `TermuxBootstrap.installQQAssets()` 解进 rootfs。
**只在必要时解压**：关键库缺失或 tar 资产大小变化（标记文件 `files/qq_libs_done`），
否则每次启动都会重复解压 175MB（历史 bug，勿回退）。

### 2.3 jniLibs（arm64-v8a）

Android 只打包 `lib*.so`，可执行文件需改名。必须包含：

| 库 | 说明 |
|----|------|
| `libproot.so` | proot 主程序（从 Termux `usr/bin/proot` 拷贝） |
| `libprootloader.so` / `libprootloader32.so` | proot 自带 loader（`libexec/proot/loader*`） |
| `libtalloc.so` | proot 依赖；若 soname 为 `libtalloc.so.2`，需改二进制字符串为 `libtalloc.so` |
| `libbash.so`、`libtinfo.so` 等 | shell / 工具 |

**注意：**

- 必须从 `nativeLibraryDir` 执行，勿从 `filesDir` exec（SELinux `execute_no_trans`）。
- proot 硬编码 loader 路径在 com.termux，运行时用 `PROOT_LOADER` / `PROOT_LOADER_32` 指到 jniLibs。
- 启动脚本须 **`unset LD_PRELOAD`**，否则 termux-exec 会把路径改写成 com.termux。

---

## 3. 构建步骤

### 3.1 签名

`keystore/napcat-bridge-release.jks`
alias `napcatbridge`，口令见 `app/build.gradle.kts`。

### 3.2 一键编译

**PowerShell：**

```powershell
Set-Location F:\env\qbridge_unified

Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue
$env:JAVA_HOME = 'D:\Windows8.1\jdk21'
$env:ANDROID_HOME = 'F:\env\android-sdk'
$env:ANDROID_USER_HOME = 'F:\env\android-user-home'
$env:GRADLE_USER_HOME = 'F:\env\gradle_home'

# 唯一允许的构建命令（勿并行）
& 'F:\env\gradle-8.7\bin\gradle.bat' :app:assembleRelease --no-daemon
```

**Git Bash：**

```bash
cd /f/env/qbridge_unified
env -u ANDROID_SDK_HOME -u ANDROID_SDK_ROOT -u ANDROID_PREFS_ROOT \
  JAVA_HOME='D:\Windows8.1\jdk21' \
  ANDROID_HOME='F:\env\android-sdk' \
  ANDROID_USER_HOME='F:\env\android-user-home' \
  GRADLE_USER_HOME='F:\env\gradle_home' \
  'F:\env\gradle-8.7\bin\gradle.bat' :app:assembleRelease --no-daemon
```

产物：`F:\env\qbridge_unified\app\build\outputs\apk\release\app-release.apk`

拷贝到分发目录（**构建后必须执行，否则 dist 里是旧包**）：

```bash
cp -f /f/env/qbridge_unified/app/build/outputs/apk/release/app-release.apk \
      "/d/Windows11/桌面/code/百亿补贴/Qbridge/dist/NapCatBridge-release.apk"
```

**版本号**：发布前在 `app/build.gradle.kts` 递增 `versionCode` 并更新 `versionName`
（命名惯例：`1.6.x-<主题>`，如 `1.6.4-typing-fast`）。用 `adb install -r` 升级时 versionCode 必须不小于已装版本。

### 3.3 安装到设备

```bash
adb install -r -t /f/env/qbridge_unified/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.aliya2qq.bridge/.ui.MainActivity
```

- 大包安装可能超过 1 分钟，以 `Success` 为准。
- `install -r` **保留应用数据**（rootfs、绑定、配置都在），升级不需要重新初始化。
- 彻底重置数据用「卸载重装」（`adb uninstall` + `adb install`）；本机 ColorOS 禁止 shell `pm clear`。

---

## 4. 应用内安装流程（首次启动）

1. **电池白名单**：首启自动弹「忽略电池优化」系统授权框，**必须点允许**
   （否则灭屏/后台时系统会杀 proot/QQ 子进程，见 §5.4）。
2. **Bootstrap**：释放 PREFIX，重写 com.termux → 本包路径。
3. **rootfs**：解压 Debian 到 `files/usr/var/lib/proot-distro/installed-rootfs/napcat`。
4. **runtime 链接**：补 `/lib/ld-linux-aarch64.so.1`、soname、`/bin/sh`（tar 打包时丢掉符号链接，需用真实文件拷贝）。
5. **NTQQ**：解 `linuxqq_arm64.deb` → `/root/Napcat/opt/QQ`，注入 NapCat（`loadNapCat.js` + `app_launcher/napcat`）。
6. **qq-libs**：解运行库进 rootfs（一次性，之后由标记文件跳过）。
7. **sw_template**：拷到 `src/assets/`，供 WebUI 生成 `sw.js`。

全程离线，约 2~5 分钟。主界面状态变为 **「环境：就绪」** 后点 **「启动NapCat」**。

### 4.1 QQ 登录（-q 快速登录）

- 在 **设置 → QQ 号（快速登录，可空）** 填入 QQ 号一次即可，
  启动时读取（`qq_uin.txt` 持久化），之后每次启动自动带 `-q <uin>` 快速登录，**无需扫码**。
- 未填 QQ 号或存储的登录态已失效时，NapCat 进入扫码等待（`waiting_qrcode`）：
  用日志 ASCII 二维码 / 主界面二维码图片 / `txz.qq.com` 解码 URL 扫码一次即可。
- 存储会话被系统杀进程等异常破坏时，快速登录会失败并回退扫码；
  应用的会话修复动作（`qq_session_action.txt=rename`）会在下次启动把损坏的
  `/root/.config/QQ` 改名为 `QQ.bak` 保留现场（Worker 段错误的应急开关）。

---

## 5. 运行要点

| 项 | 说明 |
|----|------|
| proot | `PROOT_NO_SECCOMP=1`、`PROOT_TMP_DIR`、`PROOT_LOADER`、`unset LD_PRELOAD` |
| 显示 | Xvfb 在 proot 下 `link()` 锁文件易失败；使用 `--ozone-platform=headless --disable-dev-shm-usage` |
| WebUI | `http://127.0.0.1:6099/webui`；token 显示在主界面「WebUI Token」栏（每 10s 刷新识别） |
| OneBot | WS 服务端 `127.0.0.1:3001`（在 NapCat WebUI 网络配置里建，配置随 rootfs 持久化） |
| 进程检测 | `isNapCatRunning` 直接扫 `/proc`（同 uid 可读），`$PREFIX/bin/pgrep` 被 SELinux 禁止，勿用 |
| 进程清理 | `stop` 用 Kotlin `Process.killProcess` 扫描同 uid + `/system/bin/pkill`（toybox），模式串用 `[q]` 字符类防自匹配 |

### 5.1 OneBot WS 连接稳定性（勿回退）

`OneBotAdapter` 的 OkHttpClient **不得设置 `pingInterval(20s)`**：
proot 下 NapCat 的 pong 可能延迟超过 20s，OkHttp 会因 pong 超时把连接杀掉，
形成「连接 → 20s EOF → 3s 重连」循环，表现为**消息发出后经常无响应**
（重连空窗期事件永久丢失）。服务端每 10s 推 heartbeat，断连由 onFailure 触发重连即可。

### 5.2 「正在输入」功能

等待 Misskey 回复期间，每 **0.5s** 调一次 NapCat 扩展 API `set_input_status`
（参数 `{"user_id": "<对方QQ>", "event_type": 1}`，仅私聊），
直到回复到达或超时。QQ 输入状态衰减极快，刷新间隔不可加大到秒级以上。

### 5.3 会话修复动作（qq_session_action.txt）

`assets/qq_session_action.txt` 每次启动被释放到 `files/home/`，`start()` 读取后传给容器脚本：

- `rename`：`/root/.config/QQ` 存在且无 `.bak` 时改名为 `QQ.bak`（保留现场）
- `restore`：反向恢复
- `none`：不动

改名/恢复发生在 `mkdir /root/.config/QQ/NapCat/temp` **之前**（顺序勿换，
否则历史「mkdir 非递归 ENOENT → Worker 崩溃」会复发）。

### 5.4 后台保活（ColorOS/Oppo）

- 首启弹窗申请「忽略电池优化」，允许后写入 `deviceidle whitelist`。
- 若仍出现 proot/QQ 子进程被杀（现象：qq 进程在、`libproot.so` 消失、6099/3001 全下线、
  容器内报 `Unable to access /tmp`），用 adb 关闭幻影进程猎杀：

```bash
adb shell device_config put activity_manager max_phantom_processes 2147483647
# settings put global settings_enable_monitor_phantom_procs false 在本机无权限，忽略
adb shell am set-standby-bucket com.aliya2qq.bridge active
```

`device_config` 在重启后可能回退，需重设。测试期间尽量保持应用前台、屏幕常亮。

---

## 6. NapCat WebUI API（自动化测试用）

- **登录**：`POST /api/auth/login`，body `{"hash": SHA256(token + ".napcat"), "totpCode": ""}`
  （注意不是明文 token；前端在 `static/assets/index-*.js` 里可见协议）。
- 返回的 `data.Credential` 是**短时效**的，每次接口调用前重新登录获取。
- 调用头：`Authorization: Bearer <Credential>`（**不带**引号；前端 localStorage 存的是 JSON 带引号形式，直接用会 401）。
- 常用接口：
  - `POST /api/QQLogin/CheckLoginStatus` → isLogin / loginPhase / qrcodeurl
  - `POST /api/QQLogin/GetQQLoginQrcode` → `{"qrcode": "https://txz.qq.com/p?k=..."}`（约 2 分钟有效）
  - `POST /api/QQLogin/GetQuickLoginList` / `SetQuickLogin`（body `{"uin":"..."}`）
  - `POST /api/QQLogin/set_input_status`（OneBot 动作，走 3001 WS 亦可）
- PC 访问需先 `adb forward tcp:16099 tcp:6099`。

---

## 7. 常用排查命令

```bash
# 进程 / 端口（应用日志 TAG 是 Aliya2QQ）
adb shell ps -A | grep -E "qq$|libproot"
adb shell netstat -tln | grep -E "3001|6099"

# 应用日志（bridge/napcat/会话动作都打在这个 TAG）
adb logcat -s Aliya2QQ:V

# 界面结构（注意：日志面板是自绘 View，dump 读不到内容）
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .

# 设备上日志文件在应用私有目录，release 包无法 run-as，
# 只能通过应用 UI 日志页（LogPump 桥接）查看
```

**注意**：日志面板里每行是单份（历史上曾有双写 bug，每行两遍，勿回退）；
应用退后台被冻结时 logcat 镜像也会停，重新拉回前台即恢复。

---

## 8. 项目结构（关键）

```
qbridge_unified/
├── app/
│   ├── build.gradle.kts          # applicationId, abiFilters, 签名, noCompress, 版本号
│   └── src/main/
│       ├── assets/               # 启停脚本 + qq_session_action.txt
│       ├── assets/preload/       # 离线资源（见 §2）
│       ├── jniLibs/arm64-v8a/    # proot / bash 等（见 §2.3）
│       └── java/com/aliya2qq/bridge/
│           ├── core/Bridge.kt          # 桥接核心、管道、正在输入
│           ├── engine/
│           │   ├── NapCatManager.kt    # 启停、proot 脚本、会话动作、token
│           │   ├── OneBotAdapter → ../platforms/
│           │   ├── QQInstaller.kt      # NTQQ deb + NapCat 注入
│           │   ├── TermuxBootstrap.kt  # PREFIX / rootfs / 运行库
│           │   ├── PreloadAssets.kt    # assets 释放
│           │   └── ShellExec.kt
│           ├── platforms/              # OneBotAdapter / PlatformHub（OneBot WS 客户端）
│           ├── misskey/MisskeyClient.kt
│           └── ui/
│               ├── MainActivity.kt     # 电池白名单申请在 onCreate
│               └── WebLoginActivity.kt
└── keystore/napcat-bridge-release.jks
```

---

## 9. 版本与包名

- `applicationId`：`com.aliya2qq.bridge`（勿用 `com.termux`）
- 版本历史（1.6.x）：

| 版本 | 要点 |
|------|------|
| 1.6.4-typing-fast (14) | 正在输入 0.5s 高频刷新（参考 AstrBot input_state 插件） |
| 1.6.3-battery (13) | 首启申请电池优化白名单 |
| 1.6.2-typing (12) | 等待 Misskey 回复时 QQ 显示正在输入（set_input_status） |
| 1.6.1-fix1 (11) | 移除 OkHttp pingInterval 修复 20s 断连；-q 快速登录链路确认 |
| 1.6.0-bundle-qq (10) | 全量预置 NTQQ；Worker 崩溃、状态机卡「启动中」等修复 |

近期已修复（回归测试时注意勿回退）：

1. `isNapCatRunning` 用 `/proc` 扫描（`pgrep` 被 SELinux 拒绝 → 状态机永久卡「启动中」）。
2. `stop` 用 Kotlin kill + `/system/bin/pkill`（`$PREFIX/bin/pkill` 被拒 → 进程杀不掉，二次启动覆写运行中进程的 mmap 库 → SIGSEGV）。
3. pgrep/pkill 模式串用 `[q]` 字符类，防调用 shell 自匹配（否则 start 误判已在运行、直接早退）。
4. qq-libs.tar 一次性解压（标记文件 + 资产大小），tar 无 `usr/` 条目，勿把 `usr/lib/...` 加回检查清单。
5. 日志双写修复（BridgeLog.listener 与 LogPump.onLine 不能同时 append）。
6. 启动脚本 `bash -n` 语法校验通过；`${'$'}` 转义与 SESSION_ACTION 传递勿动。

---

## 10. 注意事项

1. **一次一个构建/安装任务**，禁止并行 Gradle。
2. 二进制经 `adb shell cat` + Windows 重定向会损坏 CRLF，请用 `adb exec-out` 或 Python `write_bytes`。
3. `libproot.so` 若依赖 `libtalloc.so.2`，需改字符串为 `libtalloc.so` 或提供对应 soname。
4. rootfs tar 无符号链接时，必须在安装阶段创建 loader / soname / `bin/sh`（见 `ensureRuntimeLinks`）。
5. 预置资源变更后，注意 `PreloadAssets.extractTo` 会按 **文件大小** 判断缓存是否有效。
6. WebUI 静态资源缺失时，JS 会被 SPA 兜底成 `text/html`（MIME 报错）；`start()` 会检查并同步 `static/`。
7. UI 输入框（如 QQ 号）对 `adb shell input text` 的响应受输入法影响：
   先 `input keyevent 4` 收起键盘，再点字段，再 `input text`，必要时重试几次。
8. 反复执行已成功的设备操作（重复安装/轮询）会使设备发热甚至关机；
   等待设备上线用 `adb wait-for-device` 阻塞，不要高频轮询。
