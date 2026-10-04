# Termux 组件集成与 APK 自动发布调研

调研日期：2026-10-05。状态：研究与方案建议，尚未实现或完成 Android 构建验证。

后续具体案例见 [Moke 集成调研](moke-integration.md)：它通过内置组件源码、改写 TerminalSession 和增加 TerminalTransport 解决 SSH 适配，并已有 tag 自动发布流程。

## 结论

1. Termux 确实向第三方提供 `terminal-emulator` / `terminal-view`；无需把完整 Termux App 或 Linux bootstrap 打进 ShellDeck。
2. 两个库并不是可以直接接入 SSHJ 的通用终端控件组合。当前 `TerminalView` 绑定具体的 `TerminalSession`；后者是 `final`，负责本地进程和 JNI PTY。SSH 集成需要解决这个边界。
3. 建议保持解析器、screen buffer 和 renderer 的行为不变，先设计、验证最小的会话/视图适配。是否 vendoring 或维护组件 fork，待讨论后确定。
4. GitHub Actions 可以完成检查、构建、签名和 Release 发布。建议普通提交产出测试 Artifact，版本 tag 自动产出正式签名 APK 并发布 Release。
5. 在第一次正式分发前确定 applicationId、签名密钥、版本策略和 License；这些会影响后续升级与维护。

## 研究基线与依赖可用性

源码分析固定到 Termux `master` 当次解析出的 commit：

`8629e632fcb95da272221be327db653fb24befe9`

[固定版本源码](https://github.com/termux/termux-app/tree/8629e632fcb95da272221be327db653fb24befe9)。官方 Releases API 当次返回的最新正式版为 [v0.118.3](https://github.com/termux/termux-app/releases/tag/v0.118.3)，发布日期 2025-05-22。**下面的当前源码分析不等同于 v0.118.3 的逐行审查。**

[官方库集成说明](https://github.com/termux/termux-app/wiki/Termux-Libraries) 说明支持 JitPack，`terminal-view` 传递依赖 `terminal-emulator`。独立 SSH 客户端不需要为使用终端而额外引入 `termux-shared`。

当次实际访问结果：

| Maven 坐标 / 产物 | 结果 |
| --- | --- |
| `com.termux.termux-app:terminal-view:0.118.0` POM | HTTP 200 |
| `com.termux.termux-app:terminal-view:0.118.3` POM | HTTP 404 |
| `com.termux.termux-app:terminal-emulator:0.118.3` POM | HTTP 404 |
| `com.github.termux.termux-app:terminal-view:0.118.3` POM | HTTP 200 |
| `com.github.termux.termux-app:terminal-view:v0.118.3` POM 和 AAR | HTTP 200 |
| `com.github.termux.termux-app:terminal-emulator:v0.118.3` AAR | HTTP 200 |

这是当次网络观测，不代表某一命名空间永久不可用。官方说明也指出不同 groupId 与带/不带 `v` 的版本可能对应不同构建。

下载并检查 AAR ZIP，确认均有 `classes.jar`：

| AAR | 大小 | SHA256 |
| --- | ---: | --- |
| `terminal-view-v0.118.3.aar` | 42,419 bytes | `adb476c5002fa4a1c99f324d9e3b48846e0cb132434e9000d065f8a8f3aea441` |
| `terminal-emulator-v0.118.3.aar` | 83,791 bytes | `cbb915c4d7c51883f85397b46b74bf4b82fd32a8bbf0baeba1e0bc6d990d3e8a` |

示例配置只用于解释依赖方式，尚不是 ShellDeck 的最终选型或已通过的构建配置：

```kotlin
// settings.gradle.kts 的 dependencyResolutionManagement.repositories 内
maven("https://jitpack.io") {
    content { includeGroup("com.github.termux.termux-app") }
}

// 使用原版组件时的模块依赖
implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
```

正式使用要固定版本/commit、验证完整 Gradle 依赖图并记录校验值，禁止使用 `master-SNAPSHOT`。能下载 AAR 并不证明它能直接接入 SSH 或兼容我们的最终工具链。

## 模块职责与 SSH 适配边界

| 对象 | 上游职责 | ShellDeck 的使用方式 |
| --- | --- | --- |
| `TerminalEmulator` | 解析字节流、控制序列，维护终端状态 | 优先保持原样 |
| `TerminalOutput` | 应答、鼠标、粘贴等输出，以及标题/颜色等回调 | 连接 SSH 写入队列和 UI 回调 |
| `TerminalRenderer` / `TerminalView` | 网格绘制、触摸、选择、按键和 IME | 尽量复用，处理会话类型耦合 |
| `TerminalSession` | 本地 subprocess、JNI PTY、读写线程与生命周期 | 不能直接作为 SSH session |
| `termux-shared` / `app` | Termux 应用与插件的共享能力、产品层 | 当前不引入 |

关键源码：

- [TerminalSession](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)：`final` 类；首次 `updateSize()` 会进入 emulator 初始化与 `JNI.createSubprocess()`，后续 resize 调用本地 PTY JNI。不能用子类覆写 I/O。
- [TerminalView](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java)：也是 `final`；`attachSession()` 要求具体的 `TerminalSession`，输入、尺寸和 emulator 获取均依赖它。
- [TerminalViewClient](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalViewClient.java) 与 [TerminalSessionClient](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSessionClient.java) 也有具体会话类型，不能只修改一个构造参数便宣称完成解耦。

可选路径：

| 路径 | 代价 | 判断 |
| --- | --- | --- |
| 原版两个 AAR + 本地进程桥接 SSH | 增加本地进程、PTY 与 native 维护 | 不作为首选 |
| 原版 emulator + 最小适配的 view/session 边界 | 维护少量有来源的补丁；需审查全部类型耦合 | 优先做验证 |
| 对两个组件维护最小 fork | 构建可控，可系统性抽出 transport 边界；持续同步成本更高 | 如果上一条受 API 耦合限制，再讨论采用 |
| 重写 View、IME、选择和 renderer | 兼容性与测试成本高 | 不建议 |

“优先 upstream”仍然成立，但现有源码已经表明：**完整不改地复用这两个组件与直接使用 SSHJ 之间存在适配缺口。** 这不意味着必须修改 ANSI parser，也不意味着要 fork 整个 Termux 产品。

建议的逻辑链路（不是承诺的最终模块拆分）：

```text
Compose TerminalScreen
        │ AndroidView
适配后的 Termux TerminalView / Renderer
        │
ShellDeck terminal session ── TerminalEmulator
        │                         │
        └──── 输入 / 查询应答 ─────┘
        │
SSH transport（候选 SSHJ）
        │
远端 PTY → tmux / TUI
```

适配必须做到：

- SSH 收到的原始 bytes 进入 `TerminalEmulator.append()`，不要先按 chunk 转成 String，以免破坏跨包 UTF-8 或控制序列。
- 网络读写放在 I/O 线程；emulator 状态与 View 访问采用明确的串行所有权。初始实现可沿用上游主线程更新策略，合并刷新通知、限制队列，不按每个字节触发 Compose 重组。
- 用户输入和 emulator 查询应答都通过有序写入路径回到 SSH。不能只实现“读 stdout + 写键盘”。
- 显式申请 `xterm-256color` PTY，再启动 shell；SSHJ 的 `allocateDefaultPTY()` 默认是 `vt100`，不适合直接照用。
- 初始 PTY 尺寸与后续窗口变化分开；使用 `changeWindowDimensions()` 发送变化并对相同行列去重。SSH 参数中的像素宽高是窗口尺寸，不能误传单元格尺寸。
- 网络断开、关闭、重连与 UI detach 分开处理；重建连接时重置发送尺寸状态。

[SSHJ Session API 源码](https://github.com/hierynomus/sshj/blob/master/src/main/java/net/schmizz/sshj/connection/channel/direct/Session.java) 支持显式 PTY 分配与窗口尺寸变化。这里只确认接口能力，尚未验证 SSHJ 的 Android crypto provider、认证与 R8 兼容性。

## 与已知兼容问题相关的发现

[TerminalEmulator 源码](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java) 明确处理 `OSC 10/11/12` 的 `?` 查询，读取当前颜色后通过 `TerminalOutput.write()` 回复。我们的回传路径必须覆盖它；这不能反向证明 Termius 的根因。

上游 `TerminalView.updateSize()` 已比较新旧 rows/cols，相同则不调用 session resize。因此不能把“Moke 闪屏”简单归因于 Termux 完全没有尺寸去重。实际尺寸在 IME 动画期间反复变化、重复 inset 消费或视图重建，仍需通过日志和真机验证。

Compose 使用 [AndroidView 官方互操作方式](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose)：在 `factory` 创建 View，`update` 只应用有变化的配置。会话状态可以比 View 活得更久，但不能用长期持有 Activity Context 的 View 来实现保活。配置变化后可重建 View 并绑定既有会话。

源码提供 `setTypeface()` 与 `setTextSize()`，但任意行距、字距和自定义 fallback 链仍需单独评估。Extra Keys 编辑器由 ShellDeck 自己实现，不要求引入完整 Termux UI。

## License

[固定版本 LICENSE.md](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md) 声明仓库采用 **GPLv3 only**，同时指出两个终端模块使用了 Apache 2.0 的 Android Terminal Emulator 代码。

不能把这段例外解释成“这两个模块全部都是 Apache 2.0”。建议 ShellDeck 按 GPL-3.0-only 方向讨论许可证，并在确定实际纳入的文件后核对版权与例外声明。分发前规划对应源码、构建脚本、依赖版本、补丁与许可声明的提供方式；本次不擅自创建项目 LICENSE，也不把尚未完成的逐文件审查描述成已经完成。

## 从源码到可安装 APK 需要什么

| 工作 | 具体内容 |
| --- | --- |
| Android 工程 | Gradle Wrapper、settings、版本目录、app module、Manifest、入口 Activity、Compose 配置与资源 |
| 应用身份 | 长期稳定的 applicationId、名称、图标、minSdk / targetSdk / compileSdk |
| 工具链 | 固定兼容的 JDK、Gradle、AGP、Kotlin / Compose 和 Android SDK Build Tools |
| 终端与 SSH | 组件依赖/补丁、session adapter、INTERNET 权限、Host Key 验证和最小连接流程 |
| native（有条件） | 若编译或保留 JNI / 将来加入 Mosh，固定 NDK、ABI 与 native 构建配置 |
| Release 配置 | release signing、versionCode / versionName、是否开启 R8、许可声明 |
| 验证 | 单元测试、lint、APK 签名和 metadata 检查、安装启动及真机终端回归 |

不需要安装 Android Studio 才能在 CI 编译；可以使用 SDK 命令行工具与 Gradle Wrapper。常见任务是 `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug` 和 `:app:assembleRelease`；必须以未来实际工程任务为准。[Android 命令行构建文档](https://developer.android.com/build/building-cmdline)

工具链不要直接照抄 Termux 整个 app 的配置。当次固定 commit 的 `gradle.properties` 使用 compileSdk 36、targetSdk 28、minSdk 21，包含 Termux 自身的兼容背景，不能据此把 ShellDeck 也设为 targetSdk 28。[上游配置](https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/gradle.properties)

JDK 17 可作为初始候选；AGP / Gradle 必须按 [官方兼容表](https://developer.android.com/build/releases/gradle-plugin) 成套选择，随后验证上游库源码构建兼容性。本次不把当前最新 AGP 直接定为项目版本。

### NDK 与 APK 架构

实测 v0.118.3 emulator AAR 包含四种 ABI 的 `libtermux.so`：arm64-v8a、armeabi-v7a、x86、x86_64；view AAR 不含 `.so`。

- 使用预编译 AAR 一般不需要自行编译它的 JNI；但 APK 仍可能包含这些 native 库。
- 上游 emulator 源码 Gradle 配置包含 `ndkBuild`，直接从源码构建原模块需要 NDK。
- 如果最终使用纯 SSH session 且彻底不调用本地 TerminalSession/JNI，可评估按官方说明排除 `libtermux.so`；必须验证所有调用路径，再做排除。
- 首版建议优先单一 universal APK。如果最终没有 native 依赖，就没有按 CPU 拆包的必要。
- 保留 native 库时检查 16 KB page size 兼容性，不把旧 AAR 或升级打包工具本身当成保证。[Android 16 KB 支持文档](https://developer.android.com/guide/practices/page-sizes)

## GitHub Actions 与 Release 方案

建议分成两个 workflow：

| 触发 | 流程 | 结果 |
| --- | --- | --- |
| PR / push main | checkout → 工具链 → 测试/lint → debug APK | Actions Artifact，供验证 |
| push `v*` tag | 校验版本与来源 → 测试/lint → release 构建与签名 → 验签 → 发布 | GitHub Release + APK + 校验值 |

使用 `actions/checkout`、`actions/setup-java` 和 [gradle/actions/setup-gradle](https://github.com/gradle/actions/tree/main/setup-gradle)，固定 Action commit SHA 和构建依赖。runner 固定 Ubuntu 系列版本，并显式安装需要的 SDK 包，避免依赖镜像偶然预装的版本。

发布步骤建议：

1. 严格校验 tag 格式，确认其 commit 来自允许发布的分支；从同一个 commit 构建，校验 `versionName` 与 tag 一致。
2. 维护单调增加的 `versionCode`。初期可在仓库中显式维护，避免仅凭 tag 文本或 workflow 重跑次数推断。[Android 版本规则](https://developer.android.com/studio/publish/versioning)
3. 在该 commit 上运行测试与 lint；构建 release。是否启用 R8 要经 release 真机验证，不能只测试 debug。
4. 仅可信发布 job 获取签名 Secrets，恢复 keystore 到 runner 临时目录，签名完成后清理。不要把密钥写入源码、缓存或 Artifact。
5. 用 `apksigner verify --verbose --print-certs` 验证 APK 和预期签名证书指纹，再提取版本与包名核对。[apksigner 文档](https://developer.android.com/tools/apksigner)
6. 生成 `SHA256SUMS`，准备版本说明与对应源码/构建资料。校验和用于完整性检查，不能代替 APK 签名。
7. 发布 job 使用 `GITHUB_TOKEN` 和 `contents: write`；构建 job 默认 `contents: read`。同仓库发布通常不需要额外 PAT。[GitHub Token 文档](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)
8. 使用 `gh release create` 上传精确匹配的 APK 与校验文件，并加 `--verify-tag`，避免 tag 缺失时自动指向默认分支。预发布版本标记 prerelease。[GitHub CLI 文档](https://cli.github.com/manual/gh_release_create)
9. 同一 tag 的发布串行化。重跑遇到已发布版本时应核对/停止，不静默替换不同签名或不同内容的 APK。

计划中的产物示例：

```text
GitHub Release v0.1.0
├── ShellDeck-0.1.0-universal.apk
├── SHA256SUMS
└── 对应源码与构建资料（含组件版本、补丁和许可证）
```

如果组件采用 submodule，GitHub 自动生成的源码 ZIP 不应被当成已经包含子模块内容；需要单独准备完整源码包或明确可获取的固定版本来源。

### 签名密钥

APK 发布签名 keystore 与 App 内用于保护 SSH 凭据的 Android Keystore 是两件不同的事。

建议一次生成长期使用的 release 签名密钥，自己保留安全备份，再配置以下 GitHub Secrets：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Base64 仅是传输编码。密码与文件都应受保护，不能在每次 Actions 构建时重新生成 release 密钥。对直接分发 APK 的项目，稳定签名身份是持续覆盖升级的关键。[Android 签名文档](https://developer.android.com/studio/publish/app-signing)、[GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)

PR workflow 不使用 release Secrets；不通过 `pull_request_target` 执行未受信任的 PR 代码并暴露凭据。开发版本建议使用 `.debug` applicationId 后缀，允许与正式版共存。临时 runner 产生的 debug 签名不能保证跨运行覆盖升级，需要持续安装的测试版可后续设计单独的稳定签名渠道。

## 推荐实施顺序与验收

1. **先确认基础选择**：applicationId、最低 Android 版本、许可证方向，以及接受哪个最小组件适配方案。建议 minSdk 26 作为讨论起点，不视为既定需求。
2. **工程与构建闭环**：建立最小 Compose App 和 CI，先产出可安装的测试 APK；这一步可独立于复杂终端适配验证工具链。
3. **终端适配验证**：固定上游基线，列出修改文件及理由，先用可控字节流验证输入、OSC 回复、字体、选择与 resize，再接 SSH。保留上游测试与补丁记录。
4. **真实 SSH 验证**：Host Key 确认、认证、PTY、tmux / Codex / Vim；覆盖分包 UTF-8、颜色查询、重复尺寸、IME、旋转、关闭与重连。日志只记录必要事件与计数，不记录凭据或默认采集终端内容。
5. **Release 闭环**：配置长期签名 Secrets，发布首个预发布版；真机验证安装，以及下一版本覆盖升级后数据保留。

本次已完成官方资料和固定源码研究、POM 可访问性检查及两个 AAR 的下载/ZIP 检查；未运行 Gradle 构建、Android 测试、真机安装、SSH 连接或 Actions。没有创建远程仓库、生成密钥或发布 Release。
