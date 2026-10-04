# ShellDeck

面向远程开发、tmux 和现代 TUI 工作流的 Android SSH 客户端。

当前开发版本 alpha.5 已加入多会话、设置页、两行可编辑快捷键及字体管理，暂未推送或发布。公开版本仍为 [alpha.4](https://github.com/Cherrling/ShellDeck/releases/tag/v0.1.0-alpha.4)。

- 首页显示活动会话；终端返回先收起键盘，再返回首页，保持连接。关闭按钮只关闭指定会话，同一 Host 可开多条连接。
- 两行快捷键整体横向滑动；设置页可编辑标签、字符、特殊键/组合键、修饰键、转义序列、宏、宽度和位置。Shift / Ctrl / Alt 支持一次性、按住及长按锁定，切换会话清理状态。
- 内置 Maple Mono NF CN Regular，支持系统字体和导入 TTF / OTF、重命名、删除、字号及预览。
- App 支持跟随系统/明亮/深色与动态配色；终端默认前景/背景独立设置。
- 继续支持加密 Identity、OpenSSH / PEM / PKCS#8 导入、按需口令和服务器指纹验证。当前会话由根 ViewModel 管理，应用内导航与配置变化保持连接；尚无前台服务、后台保活、网络自动重连或进程死亡恢复。


## 开发环境

- Android 8.0+（minSdk 26）。正式包名 `cc.cherr.shelldeck`；开发包名 `cc.cherr.shelldeck.debug`。
- JDK 17、Gradle 8.13（仓库附带 Wrapper）、AGP 8.13.2、Kotlin 2.2.21。
- Android SDK Platform 36、Build Tools 35.0.0；当前不需要 NDK。
- 设置 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中设置 `sdk.dir`。

```sh
python3 scripts/ssh_test_server.py -- ./gradlew :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

真 SSH 设备测试（需要已启动的模拟器和 `adb`）：

```sh
python3 scripts/ssh_test_server.py -- python3 scripts/android_ssh_tests.py --serial emulator-5554
```

该入口仅使用临时测试密钥，通过 adb reverse 连接 loopback sshd；结束时清理测试密钥和端口转发。普通 connectedDebugAndroidTest 未提供测试服务时会跳过两组真实 SSH 设备用例，不应算作完整验证。

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。测试命令需要本机 OpenSSH sshd / ssh-keygen 和 OpenSSL，只启动临时 loopback 公钥认证服务；测试密钥在退出时删除。终端模块包含上游回归测试和会话接入测试；Python 测试覆盖发布版本、包名、debuggable 和签名指纹的校验逻辑。

本仓库所在的 `~/code` 为 NFS 时，先复制源码到本地磁盘或空间充足的 `/dev/shm`，把 `GRADLE_USER_HOME` 和 Android SDK 也置于本地存储再构建。不要把构建缓存同步回源码仓库。

## 自动构建与发布

- PR、push main、手动触发 CI：检查并上传开发 APK Artifact。
- push `v*` tag：检查版本与来源，测试、lint、签名构建、验签，发布 GitHub Release。
- GitHub 仓库：[Cherrling/ShellDeck](https://github.com/Cherrling/ShellDeck)。后续正式发布使用已配置的固定签名 Secrets。详见 [发布说明](docs/development/releases.md)。
- 本地 alpha.5 实现和验证见 [会话与设置记录](docs/development/sessions-settings-alpha5.md)。
- SSH 版验证见 [alpha.3 验证记录](docs/development/ssh-alpha3-validation.md)。
- 早期构建与检查结果见 [终端验证版记录](docs/development/terminal-lab-validation.md) 和 [初始化验证记录](docs/development/bootstrap-validation.md)。

## 技术研究

- [SSH 密钥登录与安全边界](docs/research/ssh-key-integration.md)
- [Termux 集成与 APK 构建](docs/research/termux-integration-and-release.md)
- [Moke 实现与尺寸同步分析](docs/research/moke-integration.md)
- [Agent 协作约定](AGENTS.md)

## 许可证

ShellDeck 自有代码采用 [GPL-3.0-only](LICENSE)。Termux 组件保留上游版权与许可声明，详见 [组件来源与补丁](third-party/termux/README.md)；未内置 Moke 源码。
