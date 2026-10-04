# ShellDeck

面向远程开发、tmux 和现代 TUI 工作流的 Android SSH 客户端。

当前 alpha.3 已支持真实 SSH：Host 管理、SSH 私钥文件/粘贴导入、加密 Identity、服务器指纹确认、PTY 终端与基础快捷键。优先验证 OpenSSH Ed25519 / RSA（含带口令私钥）；支持临时密码登录。当前为单会话，后台保活和完整定制功能尚未实现。

## 开发环境

- Android 8.0+（minSdk 26）。正式包名 `cc.cherr.shelldeck`；开发包名 `cc.cherr.shelldeck.debug`。
- JDK 17、Gradle 8.13（仓库附带 Wrapper）、AGP 8.13.2、Kotlin 2.2.21。
- Android SDK Platform 36、Build Tools 35.0.0；当前不需要 NDK。
- 设置 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中设置 `sdk.dir`。

```sh
python3 scripts/ssh_test_server.py -- ./gradlew :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest :app:assembleDebug
python3 -m unittest discover -s scripts -p 'test_*.py'
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。测试命令需要本机 OpenSSH sshd / ssh-keygen，只启动临时 loopback 公钥认证服务；测试密钥在退出时删除。终端模块包含上游回归测试和会话接入测试；Python 测试覆盖发布版本、包名、debuggable 和签名指纹的校验逻辑。

本仓库所在的 `~/code` 为 NFS 时，先复制源码到本地磁盘或空间充足的 `/dev/shm`，把 `GRADLE_USER_HOME` 和 Android SDK 也置于本地存储再构建。不要把构建缓存同步回源码仓库。

## 自动构建与发布

- PR、push main、手动触发 CI：检查并上传开发 APK Artifact。
- push `v*` tag：检查版本与来源，测试、lint、签名构建、验签，发布 GitHub Release。
- GitHub 仓库：[Cherrling/ShellDeck](https://github.com/Cherrling/ShellDeck)。正式发布需要完成签名 Secrets 配置。详见 [发布说明](docs/development/releases.md)。
- SSH 版验证见 [alpha.3 验证记录](docs/development/ssh-alpha3-validation.md)。
- 早期构建与检查结果见 [终端验证版记录](docs/development/terminal-lab-validation.md) 和 [初始化验证记录](docs/development/bootstrap-validation.md)。

## 技术研究

- [SSH 密钥登录与安全边界](docs/research/ssh-key-integration.md)
- [Termux 集成与 APK 构建](docs/research/termux-integration-and-release.md)
- [Moke 实现与尺寸同步分析](docs/research/moke-integration.md)
- [Agent 协作约定](AGENTS.md)

## 许可证

ShellDeck 自有代码采用 [GPL-3.0-only](LICENSE)。Termux 组件保留上游版权与许可声明，详见 [组件来源与补丁](third-party/termux/README.md)；未内置 Moke 源码。
