# 基础 Mosh 本地验证（2026-10-08，UTC+8）

本轮为本地实现验证，未推送或更新 rolling Release。发布前仍须递增 versionCode。

## 构建及回归

- 在 `/dev/shm` 工程副本中，从 SHA-256 固定的源码构建 Mosh 1.4.0、protobuf 21.12、ncurses 6.5、OpenSSL 3.5.4，NDK 29.0.14206865，API 26。
- arm64-v8a、armeabi-v7a、x86_64 均构建成功；各 ABI 的 Mosh PIE 与 JNI 库 ELF LOAD 对齐均为 0x4000。APK 中检查到二进制、terminfo 和四份依赖许可证。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 通过。
- App JVM 测试 47 项通过，包含临时 OpenSSH 服务的真实 SSH 认证、连接及相关功能回归和新增 Mosh/备份测试；Termux 上游/接入测试 153 项通过（未改动模块复用有效 Gradle 缓存）。
- Python 发布校验 4 项通过；`scripts/check_termux.py`、`actionlint`、`git diff --check` 通过。

## Android 15 x86_64 模拟器

独立 OpenSSH 仅允许临时测试公钥；Mosh 服务端仅解包到本机内存盘，通过临时测试用户 PATH 提供，不改变系统安装。

已通过：

- Mosh 真实公钥认证和 Host Key 确认、接收远端 UDP 输出。
- 远端 PTY 跟随 resize 到 93×31、隐藏会话持续接收输出、中文输出、远端 shell 退出后保留内容。
- Host Key 确认期间取消、已连接会话主动关闭、本地原生进程清理。
- 远端缺少 mosh-server 时结束为 `MOSH_MISSING`，未自动安装或降级。
- UDP 第 4–11 秒完全丢包，恢复时更换服务器看到的源端口：期间会话不结束，恢复后原 shell 环境变量仍存在，输入得到执行。测试不保存或输出 Mosh 协议密钥。
- Room v1、v2、v3、v4、v5 均无损升级到 v6，旧主机默认 SSH，身份密文和 known_hosts pin 保持。

UI 与数据操作另外 7 项通过：Mosh 协议/UDP 端口表单保存和复制、安装命令复制与显式 SSH 选择、4 项备份恢复回归和原有主机管理回归。截图测试改为选择 dialog 根节点；模拟器 System UI 曾出现 ANR 导致剪贴板读取被拒绝，重启模拟器后完整批次通过，没有绕过系统剪贴板权限。

修复了实测发现的退出屏幕丢失：原生客户端默认切换备用屏幕，使用上游 `MOSH_NO_TERM_INIT=1` 保留会话显示；没有改动 Termux core。

## 尚未覆盖

ARM 真机运行、Android 8 最低版本运行、16 KiB 页设备实际运行、真实 Wi-Fi/移动网络切换、厂商后台限制、长时间熄屏和功耗，以及 Codex/Vim 全面兼容。不要把 ELF 对齐或模拟器 UDP 恢复等同于这些验证。Mosh 1.4.0 的 OSC 能力限制见 `docs/research/mosh.md`。

GitHub Actions 配置仅完成本地语法检查；本轮未触发云端构建。未签名或分发 Dev APK 给用户。
