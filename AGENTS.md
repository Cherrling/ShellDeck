# ShellDeck — Agent 协作指南

## 项目定位

ShellDeck 是面向远程开发和现代 TUI 工作流的 Android SSH Terminal Client，目标是长期日常使用和持续公开维护，而非一次性 Demo。

典型链路：Android → SSH → Linux / PVE / LXC / 开发机 → tmux → Codex CLI / Claude Code / Shell / Vim / Neovim。代码、编译与开发工具主要运行在远端；手机负责终端交互、查看输出、输入 Prompt 和轻量 Review。

Terminal 是产品核心。优先保证终端兼容性、稳定性、低功耗和可定制能力；管理界面追求完整、清晰的 Android 产品体验。不是服务器监控面板，也不是手机上的 Linux userspace。

## 协作与改动原则

- 默认使用中文交流，包括设计、分析、计划、Review 和验证结果。代码、标识符、协议名等使用适当的英文。
- 重要模块遵循：研究 → 设计 → 与用户讨论 → 实现 → 测试 → Review。没有讨论确定的重大技术决策，不直接落成大量实现。
- 修改前先阅读现有代码与相关文档；保持改动小、单一职责、可验证、可回滚。不借局部修复重写无关模块。
- 对用户的技术判断有异议时，直接说明依据。明确区分事实、推断、假设和待验证事项。
- 第三方项目、API 或 Android 行为不确定时，优先查阅当前官方文档和上游源码，记录版本或 commit 及来源，不凭印象断言。
- 不机械套用 MVVM / Clean Architecture，不为架构制造没有实际价值的 abstraction。
- 不把尚未实现的功能写成已具备；报告验证范围、结果和未覆盖事项。

## 技术方向与研究边界

以下是候选方向，需要研究后确认具体版本、适用性与取舍：

| 领域 | 当前方向 |
| --- | --- |
| 语言 | Kotlin |
| 管理 UI | Jetpack Compose、Material Design 3；研究 Material 3 Expressive |
| Terminal | 优先复用 Termux `terminal-emulator` 和 `terminal-view` |
| SSH | 优先研究 SSHJ |
| 本地结构化数据 | Room |
| 凭据保护 | Android Keystore 保护加密密钥，凭据以加密 blob 存储 |
| Mosh | 后续研究，不预设具体实现 |

- 不从零实现 Terminal Emulator，也不直接 fork 整个 Termux 或 Moke 作为产品起点。
- 首先研究 Termux 当前模块结构、License、第三方集成方式、发布与依赖可用性、API 和 View / Compose 互操作。
- 上游组件足够时优先直接使用；确需修改 Terminal core 时再讨论最小 fork 和上游同步策略。
- 可以研究 Moke 的 SSH transport、Terminal adapter、Compose、IME、PTY resize 和 Mosh 集成，但保持自己的架构。
- 借鉴 Termius 的产品思路和信息架构，不复制专有代码、图标、视觉资产或实现。

## Terminal 与 Android 生命周期

- Terminal compatibility 是核心质量指标；以真实 tmux、Codex CLI、Vim / Neovim 工作流验证。
- App Theme（Material / Dynamic Color）与 Terminal Theme（ANSI palette）完全解耦。Terminal surface 应稳定、高性能、低干扰。
- 明确会话、SSH transport、Terminal emulator 与 UI 的生命周期边界，避免把连接寿命绑定到偶然的 UI 重建。
- 无关 Compose recomposition 不得销毁或重建 TerminalView。
- 认真处理 IME show/hide、WindowInsets、IME animation、AndroidView measurement、横竖屏、分屏和 configuration changes。
- 同一会话内，若新旧 rows / cols 相同，不得重复发送 PTY resize；新建连接需要单独完成初始 PTY 尺寸同步。
- 根据实测研究 resize 去重、coalescing 和布局稳定后的提交；不盲目 debounce，也不牺牲必要的交互实时性。
- 长连接、后台和熄屏行为及功耗必须验证，不能通过无依据的轮询或长期持锁掩盖生命周期问题。

## 产品与数据模型约束

### Hosts 与 Identities

- Host 与 Identity 解耦，多个 Host 可以引用同一个 Identity，禁止按 Host 复制存储同一份私钥。
- Host 设计考虑 label、hostname、port、identity、username override、group、tags、favorite、startup command/profile、terminal profile 和 keyboard profile。
- Host 管理需要增删改、复制、搜索、最近使用、收藏、分组和标签；大量 Host 场景考虑 fuzzy matching。
- Identity 设计考虑名称、用户名、认证类型、密钥及凭据引用和 metadata；支持密钥导入/生成、公钥查看/复制、指纹、passphrase、密码认证及替换/删除。
- SSH Agent、硬件凭据和 FIDO2 属于后续研究范围，不作为第一版必需功能。

### 安全

- 禁止将 private key、password、passphrase 明文保存到 Room、SharedPreferences 或普通文件，也不得输出到日志、测试产物或 Git。唯一导出例外：用户已明确要求支持无口令私钥导出；仅在用户主动选择无口令导出并通过系统文件选择器指定目标时写入该文件，不创建明文临时文件。默认导出仍使用口令保护，整包备份必须加密。
- Android Keystore 不等于任意私钥 blob 仓库。研究由 Keystore 保护 AES key、使用 AES-GCM 加密凭据的方案，结合当前 Android API 和 threat model 设计。
- 设计必须考虑备份、设备迁移、锁屏、密钥失效、未来生物识别解锁及用户主动导出凭据的边界。
- 禁止通过无条件接受 Host Key 或类似 `StrictHostKeyChecking=no` 的行为绕过验证。
- 首次连接未知服务器时，展示 Host、算法及 SHA256 指纹，提供 Cancel、Trust Once、Trust & Save。
- 维护持久化的 Host Key 信任记录；已知 Host Key 改变时必须明确警告，不能静默覆盖。

### Extra Keys

- 多行、自定义布局和可视化编辑器是核心方向，支持增删、拖拽、跨行移动、宽度、自定义标签、action 和长按 action。
- 使用结构化 `KeyAction`，不能把所有按键简单存成字符串。候选类型：`Character`、`SpecialKey`、`Modifier`、`EscapeSequence`、`Macro`、`ToggleKeyboard`、`Function`。
- 支持 Default、tmux、Vim、Codex 和 Custom Keyboard Profiles，并允许 Host 引用配置。

### 字体与主题

- 支持用户导入 `.ttf` / `.otf`，以及删除、显示名称修改、预览和选择。
- 研究字号、行距、字距、bold behavior、weight 和 Primary / Fallback Font 的可行性，不假定上游已支持全部选项。
- 重点验证 Maple Mono、Nerd Font、中文、Unicode 宽度及终端网格对齐；支持自定义 terminal color palette。

## 已有排查背景：不要重复错误归因

以下是用户提供的历史实测背景，不代表已定位根因；新的复测应记录客户端和远端工具版本。

- 同一远端 Codex CLI 在 Termux 和 Moke 中 composer 背景及相关颜色正常；Termius 中部分背景和 status line 颜色缺失。
- Termius 已通过 ANSI 256 色前景/背景、232–255 灰阶、True Color 背景、reverse、dim、dim + reverse、BCE 和 `CSI K` 测试。
- 手动设置 `TERM=xterm-256color` 与 `COLORTERM=truecolor` 后重启 Codex，仍不能恢复 composer 背景。因此不能归因于“完全不支持背景色”或“只是缺少 COLORTERM”。
- `OSC 10` / `OSC 11`、capability probing、响应时序及控制序列支持是待验证方向，不是确定根因。
- Moke 呼出/收起软键盘时曾出现反复闪屏。IME → layout → Terminal resize → PTY resize → 远端 redraw → 再次 layout 的反馈链只是待验证假设，研究时需确认真实调用链。

## 构建与验证

- `~/code` 是指向 NFS 的符号链接。尽量不要在 NFS checkout 中编译、运行高磁盘负载测试或写入大量构建缓存。
- 需要密集构建时，在本地存储的 home 临时目录创建独立 worktree / 副本；内存充足时优先考虑 `/dev/shm`，使用前检查容量并防止占满。
- 保留原工作区和用户改动，仅同步需要的源码改动；任务完成后清理不再需要的临时工作区与产物。
- 按改动风险选择验证：逻辑测试、集成测试、模拟器或真机测试。涉及 IME、渲染、后台、熄屏或功耗时，不能仅凭编译成功声称通过。
- Terminal 回归重点覆盖颜色与查询响应、Unicode、TUI 重绘、输入、PTY resize、会话恢复以及前后台切换；逐步建立可复现用例。
- 尚未建立构建系统时，不编造构建或测试命令；随着项目落地更新本文件中的实际工作约定。

## Git 与 Pull Requests

- Commit message 遵循 Conventional Commits：`<type>[optional scope][!]: <description>`，subject、body、footer 全部使用英文。
- 使用 `gh` 创建 PR 时，标题和正文使用中文，保留代码标识符、命令等技术字面量。
- 提交保持范围清晰；不提交凭据、本地配置、构建产物或无关改动。

## 已落地的工程约定

- 正式 applicationId：`cc.cherr.shelldeck`（用户持有 cherr.cc），开发版加 `.debug`。
- 初始工程采用 minSdk 26、compileSdk / targetSdk 36；工具版本在 `gradle/libs.versions.toml` 和 Gradle Wrapper 中固定。
- Debug 验证：`python3 scripts/ssh_test_server.py -- ./gradlew :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest :app:assembleDebug`。终端模块包含上游与会话接入测试；不能把 app 的 NO-SOURCE 当成测试通过。
- 发布校验测试：`python3 -m unittest discover -s scripts -p 'test_*.py'`。
- 版本统一维护在 `version.properties`；发布前递增 versionCode，tag 与 versionName 严格对应。
- Release 必须使用显式签名配置，禁止回退 debug key；详细说明见 `docs/development/releases.md`。
- 已支持 SOCKS5 第一跳代理与 Cloudflare 手动检测；Room v5、加密备份负载 v3（兼容读取 v1/v2），设计见 `docs/research/socks-proxy.md`。
- 已集成 Termux、SSHJ、Host/Identity 管理与加密密钥登录。安全设计见 docs/research/ssh-key-integration.md。上游来源与补丁见 third-party/termux/README.md；修改组件时必须更新补丁记录并运行 scripts/check_termux.py。

## 日常分发约定

- 用户只使用正式签名的 `cc.cherr.shelldeck`，不要再让用户安装 Dev APK。
- main CI 通过后自动更新 rolling Release；内部 debug 构建仅用于验证。
- 应用代码更新推送前递增 versionCode，保持同一正式签名。rolling tag 是唯一允许自动移动的发布 tag；`v*` tag 仍保持不可变。

- 以后发布标题、说明、build-info.json 与应用内构建时间统一使用 UTC+8（Asia/Shanghai），显示时明确标注 `UTC+8`。历史 Release 不追溯改写。
