# Moke 的 Termux、SSH、IME 与发布实现

调研日期：2026-10-05。只做源码阅读，未运行 Moke，也未复现用户的闪屏。

研究仓库：[briqt/moke](https://github.com/briqt/moke)。固定 commit：`2aee58b3159b91517632eaac981205fd5b692459`（2026-09-27，`prepare v0.1.21-rc.1`）。当前源码不一定对应用户实际安装版本。

## 核心结论

Moke 没有直接使用原版 Termux AAR 接入 SSH。它把 `terminal-emulator` 和 `terminal-view` 源码放进仓库，以本地 Gradle module 引入，改写 `TerminalSession`，新增 `TerminalTransport`。因此它是有组件级修改的源码复用，而不是证明原版 Termux API 可以直接换成 SSH 流。

这为 ShellDeck 提供了一个具体可行的适配边界：保留 View 所依赖的会话 API，在组件内部把本地进程实现替换为 transport。采用前仍需设计自己的生命周期、并发与尺寸同步机制，不直接复制整个 Moke。

## 会话与传输

```text
Compose TerminalScreen
  └─ AndroidView → Termux TerminalView
                     └─ 改写后的 TerminalSession
                          ├─ TerminalEmulator
                          └─ TerminalTransport
                               ├─ SshTransport → SSHJ → 远端 PTY
                               ├─ MoshTransport → 本地 mosh-client → UDP
                               └─ LocalDemoTransport
```

[TerminalTransport.java](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/terminal-emulator/src/main/java/com/termux/terminal/TerminalTransport.java) 定义 `start`、`write`、`updateSize`、`close`，另有用于 tmux 等操作的 `exec` 扩展。

[TerminalSession.java](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java) 接收 transport、scrollback 行数与 client，移除原本启动本地 shell 的职责；保留 `getEmulator`、`updateSize`、`write`、`writeCodePoint` 等 View 使用的 API。类也不再是 `final`，但关键手段是替换实现，并不是继承未修改的 upstream 类。

数据路径：

- 远端输出 → `processToEmulator()` → 64 KiB `ByteQueue` → 主线程 `Handler` → `TerminalEmulator.append()` → client 的 `onTextChanged()` → View 刷新。
- 按键及终端查询应答 → `TerminalSession.write()` → transport。查询应答不会因为只接了键盘路径而遗漏。
- SSH 使用后台连接/读取线程和单线程写 executor。[SshTransport.kt](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/app/src/main/java/com/briqt/moke/terminal/SshTransport.kt) 明确分配 `xterm-256color` PTY，再启动 shell 或带 PTY 的 exec。

[版本目录](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/gradle/libs.versions.toml) 当前配置 SSHJ 0.38.0，以及 Bouncy Castle 和 EdDSA 库。这只说明 Moke 的选择，不代表 ShellDeck 应照抄这些版本。

## Compose 与 IME 的实际链路

[TerminalScreen.kt](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/app/src/main/java/com/briqt/moke/ui/TerminalScreen.kt)：

- `remember(ts.id)` 创建/保留 View，同一 composition 的无关重组不直接新建它。
- `DisposableEffect(ts.id)` 绑定 controller 和 session，退出时解绑 UI 回调，不关闭网络会话。这里的 remember 不是所有历史会话 View 的永久缓存，切换或离开 composition 仍可能重建 View。
- `AndroidView(factory = { view })` 嵌入已创建的 View。ShellDeck 可参考生命周期划分，但按 Android 官方建议在 factory 内创建 View。
- 布局使用 `padding(padding)` → `consumeWindowInsets(padding)` → `imePadding()`；终端占剩余高度。
- 字体、字号和间距通过按配置值作为 key 的 `LaunchedEffect` 更新，而不是每次普通重组都执行。

Manifest 设置 `adjustResize`，并自行处理一组 configuration changes；不能仅凭 `adjustResize` 和 `imePadding` 同时存在就认定 inset 被重复应用。

确认的尺寸链路是：

```text
IME / 可用布局高度变化
  → TerminalView.onSizeChanged()
  → TerminalView.updateSize() 计算 rows/cols 并去重
  → TerminalSession.updateSize()
  → emulator.resize() + SshTransport.updateSize()
  → 写 executor → SSH window-change
```

[TerminalView.java](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/terminal-view/src/main/java/com/termux/view/TerminalView.java) 在新旧 rows/cols 不同时才调用 session。正常输出经 [TerminalController](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/app/src/main/java/com/briqt/moke/terminal/TerminalController.kt) 到 `onScreenUpdated()`，主要调用 `invalidate()`，没有在这条路径直接 `requestLayout()`。

因此，尚未证明“远端重绘 → 再次布局 → 无限 resize”的反馈环。更具体的候选是 IME 动画期间连续跨过行高边界，产生多个不同 rows 的 window-change；当前 transport 没有合并待发送尺寸，每次提交一个任务。是否造成用户所见反复闪屏，必须记录真实事件时序并复现。

## 可从源码定位的尺寸问题

以下是静态分析发现，不是已通过真机复现的闪屏根因。

### 1. 建连期间尺寸更新可能丢失

`start()` 后台连接使用最初捕获的 rows/cols；`updateSize()` 在 `sshSession == null` 时直接返回。session 层却已经更新本地 emulator 尺寸。

例如：80×30 开始连接 → 键盘弹出变为 80×15 → SSH 尚未就绪，更新被丢弃 → SSH 按初始 80×30 建 PTY。连接成功路径未看到补发最新尺寸；`onEstablished` 在 SessionManager 中用于自动端口转发，也没有尺寸补发。没有后续布局变化时，本地与远端可能一直不一致。

ShellDeck 应分别记录 desired size 与 last sent size，在通道建立后补发最新值；去重不能只比较 emulator 当前尺寸。

### 2. SSH 像素参数单位不匹配

`allocatePTY()` 和 `changeWindowDimensions()` 均直接接收 `cellWidthPixels`、`cellHeightPixels`。但 [SSHJ 0.38.0 的 API](https://github.com/hierynomus/sshj/blob/v0.38.0/src/main/java/net/schmizz/sshj/connection/channel/direct/Session.java) 要求的是整个终端窗口的像素宽高。

若已知单元格尺寸，通常应传 `columns × cellWidth` 与 `rows × cellHeight`，或使用正确的终端内容区像素尺寸。这是明确的参数语义问题；许多 TUI 主要使用行列数，所以不能据此断言它是闪屏根因。

### 3. 已连接状态的尺寸变化没有合并

View 有行列去重，session 和 SSH transport 没有额外的 last-sent 去重或 latest-pending 合并机制。IME 动画产生真实不同的行数时，会排队发送中间状态。ShellDeck 应先测量请求数量和延迟，再决定按帧合并或动画结束校正，不直接套固定长 debounce。

复现建议：在用户实际使用的 Moke 版本记录单调时间戳、View 实例 ID、IME inset、View 高度、rows/cols、连接状态、window-change 提交/实际发送计数和远端 `stty size`；不采集真实终端内容或凭据。区分布局变化过多、远端尺寸滞后、绘制问题和真正的反馈环。

## Mosh 与组件修改范围

[MoshTransport.kt](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/app/src/main/java/com/briqt/moke/terminal/MoshTransport.kt) 先通过 SSH 启动远端 mosh-server、解析端口与密钥，再用 Termux JNI 在本地 PTY 中启动随 APK 分发的 `libmosh-client.so`。这个文件用 `.so` 命名，但作为独立可执行进程运行；不是 Java 的 Mosh 协议实现。

所以 SSH 路径虽然不再依赖本地 shell，整个 Moke 产品仍因 Mosh 保留 JNI/PTY 能力与 native 打包。`useLegacyPackaging = true` 用于将 native 文件解压到可供启动的 nativeLibraryDir；目前 app 只配置 arm64-v8a。

两个终端模块也并非只修改了 session：View/renderer 有行距、字距、滚动策略和选择菜单调整；emulator 有鼠标跟踪、bracketed-paste 查询及通知序列扩展。模块 README 的修改清单没有完全覆盖当前代码，例如实际 emulator 还存在通知处理，不能把注释中的“其余未修改”当成完整 diff 证据。

本次未在模块 README 与第三方声明中找到明确的 Termux 原始 commit，不能精确计算它相对所取上游版本的补丁大小。ShellDeck 应记录 upstream URL、commit、许可文件和逐项补丁。

## 功耗相关观察

SSH transport 配置 30 秒 keepalive，另外还有一个 RTT 线程，在每次探测后 sleep 4 秒继续请求，用于显示延迟。这两个机制并存，RTT 循环本身没有前后台可见性判断。

这是额外网络活动的代码事实，不是耗电量测量。ShellDeck 不宜为常驻延迟数字默认采用这种策略，应把连接保活与用户主动诊断分开设计，并实测熄屏功耗。

## APK 与 GitHub Releases

[release.yml](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/.github/workflows/release.yml) 的流程：

1. push `v*` tag 触发，检查格式及其与 versionName 一致。
2. 安装 JDK 17、Android SDK、NDK r29，配置 Gradle cache。
3. 脚本构建 arm64-v8a 的 Mosh native；下载 maple flavor 字体。
4. 从 GitHub Secrets 解码签名 keystore 到 runner 临时目录。
5. `assembleRelease` 构建 standard 和 maple 两个 flavor。
6. 检查 APK 包含两个 native 文件及 maple 字体，重命名 APK。
7. 用 `softprops/action-gh-release` 自动发布，带 SemVer 后缀的版本标记 prerelease。

需要注意：Mosh 脚本还下载 rjyo/mosh-android 的预编译静态库，不是所有 native 依赖都在该 workflow 从源码重建。[构建脚本](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/scripts/build-mosh-native.sh)

[ci.yml](https://github.com/briqt/moke/blob/2aee58b3159b91517632eaac981205fd5b692459/.github/workflows/ci.yml) 单独构建 standard debug、运行单元测试并上传 Artifact。Release workflow 本身没有显式测试/lint 步骤，也没有在该文件中声明依赖 CI 成功；仓库外部保护规则未查询，不能据此推断实际发布完全没有门禁。

值得借鉴 tag 发布、稳定签名和产物内容检查。ShellDeck 应补充签名指纹、包名/版本检查、校验和及发布 commit 上的验证。Moke 本地缺少签名环境变量时允许 release 回退 debug 签名；我们的正式发布流程应缺失签名就失败。

## 许可证观察

Moke 根目录有 GPLv3 LICENSE，但其第三方声明把 Termux 两模块标为 Apache-2.0。这里只记录声明，不替其确认授权链。它不能替代对 Termux 实际源文件来源、修改历史和许可的核对，也不改变前一份调研的待确认项。

## 对 ShellDeck 的建议

- 采用“组件源码有明确基线 + session/transport 最小适配”的方向，比强行围绕原版 final 类搭本地进程桥接更直接。
- 第一步限定为 SSH，暂不引入 Mosh native 构建复杂度。
- 保留 raw-byte 双向路径、既有 IME/选择能力与 emulator 测试；先不同时修改颜色、Unicode 和渲染核心。
- 尺寸同步明确区分 desired、applied、sent；通道就绪后重放最新尺寸，并统一单位。
- 会话寿命独立于 View；无关重组保持 View 稳定，正常配置重建不靠泄漏旧 View 实现保活。
- 发布可以借鉴 Moke 的 workflow 结构，但工具链版本、签名门禁和测试策略由我们自己验证。

本次仅新增研究文档，没有复制 Moke 源码进入产品、修改其代码、构建 APK 或提交上游 Issue。
