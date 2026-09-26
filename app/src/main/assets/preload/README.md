# assets/preload 离线预置目录

源码树内只保留本说明与 `napcat-install.sh`。五个大体积离线包存放在
工作区 `dat/preload/`（不入库），构建脚本 `copyPreloadAssets` 会自动把它们
并入 `assets/preload/` 后打包：

- `bootstrap-aarch64.zip` — Termux bootstrap
- `debian-rootfs-aarch64-full.tar.xz` — Debian rootfs
- `qq-libs.tar` — QQ 运行库
- `linuxqq_arm64.deb` — LinuxQQ 官方安装包（版权归腾讯，请自行下载）
- `napcat-shell.zip` — NapCat.Shell

预置目录不存在时构建照常进行（产物不含离线包，应用初始化走在线下载）；
预置目录路径可用 `local.properties` 的 `preload.assetsDir` 覆盖。
