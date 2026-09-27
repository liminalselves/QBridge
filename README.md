# QBridge

在单个 Android APK 里运行 NapCat QQ 机器人，并把 QQ 消息与 Misskey（Aliya 会话）双向桥接。

应用内置 Termux 用户态运行时与 proot Debian：QQ 跑在容器里，桥接服务与控制界面跑在主进程，
日志面板直接显示登录二维码，扫码或 WebUI 均可完成登录。

## 功能

- 一键初始化：安装 Termux bootstrap、Debian rootfs、NTQQ 并注入 NapCat，全程日志可见
- NapCat启停与探活；日志面板实时输出，自动识别登录二维码（字符画/图片）
- WebUI自动登录：自动捕获并展示 NapCat WebUI Token，可复制、可在内嵌 WebView 自动填入
- QQ 私聊/群聊与特定Misskey双向桥接：聊天转发、通知推送、主动消息、防连发合并、长回复分段
- 等待 AI 回复期间自动刷新 QQ「正在输入」状态
- 群聊绑定与权限体系（绑定者 / 群管理员名单 / 全局管理员）
- 全部聊天指令见 `/help`

## 系统要求

- Android 8.0+（仅 arm64-v8a）
- 首次初始化需要网络与约 1~2 GB 存储空间/流量（使用离线预置包时无需联网）
- 一个御姐人格的misskey账号
- 一个相对稳定、已注册一段时间的QQ号

## 使用流程

1. 安装 APK（Release 产物或自行构建）
2. 打开应用，在「桥接设置」里填写 Misskey 实例地址、角色 ID、文风 ID，保存
3. 点「初始化环境」，等待环境安装完成（耗时较长，日志有进度）
4. 点「启动NapCat」，日志区出现二维码后用手机 QQ 扫码登录，或点「WebUI登录」
5. 点「启动桥接」，QQ 与 Misskey 开始互通

## 离线预置包

最终发布包默认内置全部离线资源，使初始化完全离线。大体积文件不进源码树，
存放在工作区 `../dat/preload/`（可用 `local.properties` 的 `preload.assetsDir` 改路径），
构建脚本会自动把它们并入 `assets/preload/`：

| 文件 | 说明 |
|------|------|
| `bootstrap-aarch64.zip` | Termux bootstrap |
| `debian-rootfs-aarch64-full.tar.xz` | Debian rootfs |
| `qq-libs.tar` | QQ 运行库（GTK/NSS/X11 等） |
| `linuxqq_arm64.deb` | LinuxQQ 官方安装包（版权归腾讯，请自行下载） |
| `napcat-shell.zip` | NapCat.Shell |

目录不存在（如直接 clone 本仓库）时构建照常进行，应用初始化时按在线路径自动下载。
仓库内不保存任何预置包二进制。

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）、Gradle 8.7。

### 1. 准备 local.properties

在项目根目录创建 `local.properties`，至少填写 SDK 路径：

```properties
sdk.dir=<Android SDK 路径>
```

发布包另需签名四项，密钥库文件放在仓库外：

```properties
release.storeFile=<密钥库文件路径>
release.storePassword=<keystore 口令>
release.keyAlias=<key 别名>
release.keyPassword=<key 口令>
```

### 2.（可选）准备离线预置包

按上文「离线预置包」一节，把 5 个文件放入 `dat/preload/`。该文件不是必须的，若未预置则应用初始化时需联网下载。

### 3. 编译

Windows 下直接运行仓库根目录的一键脚本：

```bat
build.bat              :: 发布包
build.bat debug        :: 调试包
```

或手动执行 Gradle：

```bash
cd qbridge_unified
gradle :app:assembleDebug     # 调试包 → app/build/outputs/apk/debug/
gradle :app:assembleRelease   # 发布包 → app/build/outputs/apk/release/
```

### 构建常见问题

- 系统里同时存在 `ANDROID_SDK_HOME` 与 `ANDROID_USER_HOME` 且指向不同目录时，AGP
  会拒绝启动，报错出现在 build 脚本第一行（`AndroidLocationsBuildService`），位置具有
  迷惑性；构建前移除其中一个变量即可。
- 项目路径包含非 ASCII 字符（如中文）时需要 `gradle.properties` 中的
  `android.overridePathCheck=true`（仓库已包含）。
- 预置包目录不在 `../dat` 时，在 `local.properties` 里用 `preload.assetsDir` 指定

更多构建细节见 [BUILD.md](BUILD.md)。

## 配置

常用配置在应用内「桥接设置」面板完成，主要字段：

| 字段 | 说明 |
|------|------|
| `platform.adapter` | `napcat` 或 `onebot` |
| `napcat.ws_url` | NapCat 正向 WS 地址，默认 `ws://127.0.0.1:3001` |
| `misskey.host` / `character_id` / `dialogue_style_id` | Misskey 实例与角色（必填） |
| `allowed_qq` / `allowed_groups` | 私聊 QQ 白名单 / 群白名单，留空不限制 |
| `admin_qq` | 全局管理员 QQ 列表 |

## 项目结构

```
app/src/main/java/com/aliya2qq/bridge/
├── core/          桥接核心：消息路由、防连发管线、去重
├── commands/      QQ 聊天指令
├── platforms/     NapCat / OneBot 协议适配器
├── misskey/       Misskey HTTP API 与流式监听
├── render/        AI 回复渲染：画图指令、表情、分段
├── db/            SQLite 持久化
├── engine/        环境安装（bootstrap/rootfs/NTQQ）、Shell 执行、NapCat 生命周期、日志泵
├── service/       前台服务
└── ui/            控制台界面
```

## 常见问题

- **初始化下载慢或失败**：可使用离线预置包，手动放置文件或启用加速器重试。
- **如何登录**：日志栏与napcat WebUI均可登录，推荐进入napcat WebUI登录。
- **QQ端无响应**：应用首次启动会申请电池优化白名单，请在系统弹窗中允许。

## 许可

本项目以 [AGPL-3.0](LICENSE) 发布。随应用分发的部分第三方二进制（bash、proot 等）
为 GPL-3.0，来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目随包分发了来自 Termux 工具链与 proot 的二进制，运行期还会下载 NapCat 等第三方组件，
许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
