# 第三方组件声明

本项目的构建与运行依赖以下第三方组件，在此致谢并声明其许可信息。

## 随应用分发的二进制

`app/src/main/jniLibs/arm64-v8a/` 下的文件来自 Termux 打包的 GNU/开源工具链与 proot，
以 `lib*.so` 形式重命名以便随 APK 分发（Android 只打包该命名形式的文件）：

| 文件 | 来源项目 | 许可 |
|------|----------|------|
| libbash.so | GNU Bash（经 Termux 打包） | GPL-3.0 |
| libreadline.so | GNU Readline（经 Termux 打包） | GPL-3.0 |
| libtinfo.so | ncurses（经 Termux 打包） | MIT-style（ncurses 许可） |
| libiconv.so | libiconv（经 Termux 打包） | LGPL-2.1 |
| libandroid-support.so | Termux android-support | BSD-3-Clause |
| libandroid-glob.so | Termux libandroid-glob | BSD-3-Clause |
| libandroid-selinux.so | Android libselinux | Public Domain |
| libproot.so / libprootloader.so / libprootloader32.so | proot（经 Termux 打包） | GPL-3.0 |
| libtermux-exec.so | Termux termux-exec | GPL-3.0 |
| libssl.so / libcrypto.so | OpenSSL | Apache-2.0 |
| libcurl.so | curl | curl 许可（MIT 派生） |
| libxml2.so | libxml2 | MIT |
| liblzma.so | xz utils | Public Domain |
| liblz4.so | lz4 | BSD-2-Clause |
| libzstd.so | zstd | BSD-3-Clause |
| libbz2.so | bzip2 | BSD-style（bzip2 许可） |
| libtalloc.so | talloc（Samba） | LGPL-3.0+ |
| libacl.so / libattr.so | acl / attr | LGPL-2.1+ |
| libtar.so | libtar（经 Termux 打包） | BSD-3-Clause |

其中 GPL 组件要求分发时提供对应源代码。Termux 打包产物与补丁可从
https://github.com/termux/termux-packages 获取，proot 源码见 https://proot-me.github.io/。

## 运行期使用的第三方组件

应用初始化时会按需获取以下内容，均不在本仓库内：

- **Termux bootstrap**（termux-packages 发布的 bootstrap zip）
- **Debian rootfs**（proot-distro 发行方式）
- **NapCat**（https://github.com/NapNeko/NapCat）
- **Linux QQ**（腾讯官方 arm64 deb 包）：版权归腾讯所有，本项目不存储、不分发该二进制；
  开源构建通过在线下载获取，离线构建需使用者自行放入预置目录。

## 依赖库

| 组件 | 用途 | 许可 |
|------|------|------|
| OkHttp | HTTP 与 WebSocket 客户端 | Apache-2.0 |
| Java-WebSocket | 反向 WebSocket 服务端 | MIT |
| NanoHTTPD | HTTP 事件接收 | Apache-2.0（BSD-like） |
| commons-compress + xz | 离线解包 deb / tar.xz | Apache-2.0 / Public Domain |
| Kotlin / kotlinx-coroutines | 语言与协程 | Apache-2.0 |
| AndroidX / Material Components | 界面框架 | Apache-2.0 |

## 其他

- 设计时参考了 Termux（https://github.com/termux/termux-app，Apache-2.0）的 bootstrap
  与路径约定，未直接复用其源码。
- NTQQ 安装与 NapCat 注入流程参考了 NapCat-Installer
  （https://github.com/NapNeko/NapCat-Installer）的实现方式。
