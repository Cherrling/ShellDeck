# 基础直连 Mosh

## 范围

Host 的 `protocol` 默认为 `ssh`，可选 `mosh`；`moshPort=0` 表示由服务器在默认 UDP 范围内选择。Room v6 将已有主机保留为 SSH。加密备份负载 v4 记录这两个字段，并继续读取 v1–v3；复制和恢复主机均保留协议。

Mosh 首版只支持手机直连服务器。不能与跳板机或 SOCKS 配置组合，保存及连接时均校验，不绕过代理直接连接。Mosh 会话暂不提供 SFTP 和端口转发，普通 SSH 会话保持原有功能。目标主要为 Linux；需要可用的 `C.UTF-8` locale。

## 连接与生命周期

1. `SshConnector` 复用 SSHJ、已有 Host Key 验证和身份认证。未知或变更的 Host Key 仍走原有确认流程。
2. SSH exec 检测 `mosh-server` 并执行启动命令，解析一条 `MOSH CONNECT`。输出上限 16 KiB，启动阶段限时 30 秒。协议密钥不进入日志、持久化或错误消息。
3. 手机 UDP 使用实际 SSH 对端的 IP，而非再次 DNS 查询，以免多地址主机启动与连接到不同机器。SSH 启动连接随后关闭。
4. App 用独立的原生 `mosh-client` 进程连接 UDP，PTY 主端接入现有 `TerminalTransport`。不改 Termux core。
5. 复用既有 resize coordinator；本地 ioctl 改变 PTY 尺寸，由 Mosh 传播远端尺寸。后台仍由 process-owned SessionManager 与已有后台策略管理。
6. 丢包、恢复和远端状态同步交给 Mosh 自身。`MOSH_ACTIVE` 只表示本地客户端已启动，不证明 UDP 已握手，具体网络状态由原生终端覆盖提示显示。持续无响应时需检查服务器 UDP 防火墙。没有额外轮询、自动创建新远端会话或自动降级 SSH。
7. 主动关闭向本地进程发送 SIGTERM，请求 Mosh 协议正常退出；2 秒后仍未结束则 SIGKILL。`waitid(WNOWAIT)` 与进程锁避免 waitpid 后 PID 被复用时误发信号。
8. 设置 `MOSH_NO_TERM_INIT=1`，避免原生客户端退出时恢复之前的备用屏幕而丢失终端内容。预测回显首版关闭，先保证中文/TUI 行为。启动命令作为远端 `/bin/sh -lc` 的单个转义参数直接启动，不使用定时模拟输入。

远端设置 `MOSH_SERVER_NETWORK_TMOUT=86400`，限制完全失联的服务端进程存活时间为 24 小时。客户端进程被杀后的会话恢复不在首版范围内；无法送达退出消息时远端可能保留至超时。Mosh 同步屏幕状态，并不保证像 SSH 字节流一样保存快速输出的每一行；完整历史仍建议在远端使用 tmux 等工具。

## 未安装服务端

通过明确的缺失标记区别 `mosh-server` 不存在与一般启动失败。展示 Debian/Ubuntu、Fedora、Alpine 安装命令，并提供复制按钮。用户选择“使用 SSH 连接”时新建普通 SSH 安装会话，不修改已保存 Host 的协议，不执行其启动命令，不自动安装或修改防火墙。密码认证需要重新输入密码。

## 原生构建与来源

版本及 SHA-256 见 `native/mosh/sources.json`：

- Mosh 1.4.0：https://github.com/mobile-shell/mosh/releases/tag/mosh-1.4.0 ，GPLv3（保留 COPYING）。
- Protocol Buffers 21.12：https://github.com/protocolbuffers/protobuf/releases/tag/v21.12 ，BSD 类许可（保留 LICENSE）。
- ncurses 6.5：https://ftp.gnu.org/gnu/ncurses/ ，保留 COPYING。
- OpenSSL 3.5.4：https://github.com/openssl/openssl/releases/tag/openssl-3.5.4 ，Apache-2.0。

从校验过的源码构建所有静态依赖，不使用第三方预编译库，不修改 Mosh 源码。自有 JNI 桥接见 `native/mosh/pty.c`。原生子进程 exec 前关闭继承的文件描述符；密钥仅通过子进程环境传入，Mosh 上游读取后 unsetenv。

NDK 固定 `29.0.14206865`，最低 API 26，构建 `arm64-v8a`、`armeabi-v7a` 和 `x86_64`。共享对象与 PIE 按 16 KiB 对齐；程序以 `libmosh-client.so` 名称打包，由系统安装时提取到 nativeLibraryDir 后执行，不从可写 App 目录下载或执行代码。

参考 Moke 的独立子进程路线（commit `8d37c5035e4ea581058ab8570e6d2e76524c0a9c`），本项目重新实现构建和 PTY 桥接，未引入其预编译静态库。

## 构建与分发

在本地磁盘/内存盘的工程副本中执行：

```sh
sdkmanager 'ndk;29.0.14206865'
python3 scripts/build_mosh.py
python3 scripts/ssh_test_server.py -- ./gradlew :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest :app:assembleDebug
```

构建主机需要 Linux x86_64、Python 3.12、C/C++ 工具链、make、CMake、Ninja、pkg-config、Perl、curl、tar 与 tic。脚本自行编译同版本 host protoc。产物在根目录 `build/mosh`，不提交 Git。原生源码指纹验证防止误打包陈旧产物。CI 按构建脚本、C 源码、依赖清单和 NDK/API 缓存最终二进制、资源及校验过的源码包；只有 main 可以写缓存。

APK 包含各依赖许可证。Release 另外附上 `ShellDeck-<tag>-mosh-sources.tar.gz`，包含实际使用的四个原始源码包；应用源码包包含构建脚本和 JNI 源码。两者共同提供对应构建来源。

## 终端兼容边界

Mosh 在远端和本地之间增加了自己的终端状态解释层，不等同于 SSH 透传。1.4.0 的 `src/terminal/terminalfunctions.cc` 支持 True Color，但 OSC dispatcher 主要处理标题及 OSC 52，不具备 SSH 路径下 Termux 的全部 OSC 能力（例如 OSC 10/11 查询、OSC 8 链接）。不能承诺所有 Codex 探测和链接行为与 SSH 相同；需要真实 TUI 验证，不能把设置 COLORTERM 当作完整修复。

## 验证

- JVM：启动命令转义、连接参数解析/异常/密钥不出现在异常中、代理/跳板机拒绝、备份 v3 兼容与 v4 往返。
- Android：v1–v5 数据库升级、真实 SSH 认证及 UDP 输出、中文、PTY resize、隐藏会话输出、退出内容保留、认证取消和本地进程回收。
- 网络夹具：`scripts/mosh_test_server.py --server <mosh-server> -- <测试命令>`。仅在临时测试服务器上中转 UDP；第 4–11 秒丢弃数据，然后更换服务端看到的源端口。测试应确认同一 shell 的环境变量保留。会话密钥仅在内存/管道中，不记录日志或文件。
- 真机的 Wi-Fi/移动网络切换、长时间熄屏、功耗、Codex/Vim 全面兼容以及 ARM 设备运行仍需要单独验证，不能由编译或模拟器结果代替。

可复现的模拟器命令（先启动 emulator-5554，并提供独立的本机 mosh-server 路径）：

```sh
SSH_TEST_MOSH_BIN=/path/to/mosh-server python3 scripts/ssh_test_server.py -- python3 scripts/android_ssh_tests.py --serial emulator-5554 --mosh-mode direct --test-class cc.cherr.shelldeck.MoshDeviceTest
python3 scripts/mosh_test_server.py --server /path/to/mosh-server -- python3 scripts/android_ssh_tests.py --serial emulator-5554 --mosh-mode roaming --test-class cc.cherr.shelldeck.MoshDeviceTest
```

未安装用例需确保测试用户默认 PATH 中无 mosh-server，使用 `--mosh-mode missing`，且不设置 `SSH_TEST_MOSH_BIN`。
