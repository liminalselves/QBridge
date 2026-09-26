# jniLibs 可执行工具

将 ELF 以 `lib&lt;name&gt;.so` 命名放入对应 ABI 目录，运行时从
`applicationInfo.nativeLibraryDir` 执行（可绕过 filesDir 的 SELinux 限制）。

## arm64-v8a 已打包

| 文件 | 来源 |
|------|------|
| libbash.so | Termux bash（已改写 NEEDED 为 libreadline.so / libtinfo.so） |
| libreadline.so | bash 依赖 |
| libtinfo.so | bash 依赖 |
| libiconv.so | bash 依赖 |
| libandroid-support.so | bash 依赖 |

## 注意

- Android 只打包文件名形如 `lib*.so` 的内容；带版本号后缀（如 `libreadline.so.8`）不会进包。
- 因此 `libbash.so` 内 DT_NEEDED 已改为 `libreadline.so` / `libtinfo.so`（等长 `\0` 填充）。
- 运行时 `LD_LIBRARY_PATH` 指向 `nativeLibraryDir`。

## 可选后续

- `libbusybox.so`、`libproot.so`（静态）以同样方式放入，便于无网/免 bootstrap。
