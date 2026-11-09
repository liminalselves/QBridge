# QBridge

在单个 Android APK 里运行 NapCat QQ 机器人，并把 QQ 消息与 Misskey（Aliya 会话）双向桥接。

应用内置 Termux 用户态运行时与 proot Debian：QQ 跑在容器里，桥接服务与控制界面跑在主进程，
日志面板直接显示登录二维码，扫码或 WebUI 均可完成登录。

## 功能

- 一键初始化：安装 Termux bootstrap、Debian rootfs、NTQQ 并注入 NapCat，全程日志可见
- 首页按步骤引导，环境 / QQ / 桥接三段状态各自启停；登录二维码（字符画/图片）直接显示在首页
- WebUI 登录：自动捕获 NapCat WebUI Token，可复制或在内嵌 WebView 中打开
- QQ 私聊/群聊与 Misskey 智能体会话双向桥接：
  - AI 回复按分段设置拆条发送，角色表情包、全站表情转为图片，画图指令生成图片后补发
  - 主动消息完整推送（含配图），送达后自动标记已读，不会在 Misskey 网页端重复提醒
  - 会话/角色被封禁、Token 失效时在 QQ 内提示；常见错误给出中文说明
  - Misskey 通知（提及/回复/反应等）与私信转发，可分别开关
- 等待 AI 回复和生成图片期间，私聊显示 QQ「正在输入」
- 群聊绑定与权限体系（绑定者 / 群管理员名单 / 全局管理员）

## 聊天指令

直接发消息即可对话（群聊需 @机器人）。常用指令：

| 指令 | 说明 |
|------|------|
| `/bind` · `/unbind` · `/me` | 绑定（MiAuth 或 `/bind <token>`）、解绑、查看账号与会话 |
| `/retry` · `/undo` · `/stop` | 重新生成上一条、撤回上一轮、中止正在生成的回复 |
| `/new` · `/sessions` · `/switch` · `/del` · `/rename` · `/history` | 会话管理 |
| `/model` · `/style` · `/imgmodel` | 对话模型、文风、生图模型（列表后按编号选择） |
| `/memory` · `/proactive` | 长期记忆、主动消息计划 |
| `/balance` · `/checkin` | 余额、每日签到 |
| `/set` | 通知 / 私信 / 分段输出 / 群消息感知开关 |
| `/admin` · `/确认` | 群管理员名单、确认重新绑定 |

完整说明发送 `/help` 查看。

## 系统要求

- Android 8.0+（仅 arm64-v8a）
- 首次初始化需要网络与约 1~2 GB 存储空间/流量（使用离线预置包时无需联网）
- 一个御姐人格的misskey账号
- 一个相对稳定、已注册一段时间的QQ号

## 使用流程

首页会按当前状态提示下一步，依次完成即可：

1. 安装 APK（Release 产物或自行构建），首次打开允许「忽略电池优化」
2. 「设置」页填写 Misskey 实例地址、角色 ID、文风 ID，保存（会逐项校验）
3. 首次打开会自动初始化环境，也可在首页「运行环境」行手动点「初始化」
4. 「QQ（NapCat）」行点「启动」，用机器人 QQ 扫描首页出现的二维码，或点「打开 WebUI」登录
5. 「消息桥接」行点「启动」，然后在 QQ 里给机器人发 `/bind` 绑定 Misskey 账号

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

发布包签名为可选项，填写以下四项后产出已签名 APK（未填写则为未签名包），密钥库文件放在仓库外：

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

常用配置在应用内「设置」页完成，保存在应用私有目录的 `config.json`（字段示例见 `config.example.json`）：

| 字段 | 说明 |
|------|------|
| `misskey.host` / `character_id` / `dialogue_style_id` | Misskey 实例与角色（必填）；地址可带 `https://`，保存时自动规范化 |
| `allowed_qq` / `allowed_groups` | 私聊 QQ 白名单 / 群白名单，留空不限制 |
| `admin_qq` | 全局管理员 QQ 列表 |
| `command_prefix` | 指令前缀，默认 `/` |
| `platform.adapter` / `napcat.ws_url` | 「高级选项」：`napcat` 或 `onebot`；NapCat 正向 WS 默认 `ws://127.0.0.1:3001` |

桥接运行中修改设置，保存时会提示是否重启桥接使其生效。

## 项目结构

```
app/src/main/java/com/aliya2qq/bridge/
├── core/          桥接核心：消息路由、防连发管线、会话缓存、去重
├── commands/      QQ 聊天指令
├── config/        配置加载与校验
├── platforms/     NapCat / OneBot 协议适配器
├── misskey/       Misskey HTTP API、错误码中文说明、流式监听
├── render/        AI 回复渲染：画图指令、表情包/表情、分段
├── db/            SQLite 持久化
├── engine/        环境安装（bootstrap/rootfs/NTQQ）、Shell 执行、NapCat 生命周期、日志泵
├── service/       前台服务
├── util/          日志与异步工具
└── ui/            控制台界面（首页 / 设置 / 日志）
```

## 常见问题

- **初始化下载慢或失败**：可使用离线预置包，手动放置文件或启用加速器重试。
- **如何登录**：首页「QQ 登录」卡片的二维码与 NapCat WebUI 均可登录。
- **QQ 提示 Token 已失效**：在 QQ 里重新发送 `/bind`。
- **QQ端无响应**：应用首次启动会申请电池优化白名单，请在系统弹窗中允许。

## 许可

本项目以 [AGPL-3.0](LICENSE) 发布。随应用分发的部分第三方二进制（bash、proot 等）
为 GPL-3.0，来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本项目随包分发了来自 Termux 工具链与 proot 的二进制，运行期还会下载 NapCat 等第三方组件，
许可与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
