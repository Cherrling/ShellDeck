# ShellDeck

面向远程开发、tmux 和现代 TUI 工作流的 Android SSH 客户端。

安装入口统一为 [滚动更新 Release](https://github.com/Cherrling/ShellDeck/releases/tag/rolling)：只分发正式签名的 `cc.cherr.shelldeck` APK，沿用原签名并递增 versionCode，可直接覆盖更新。main 的 CI 通过后自动更新该入口。

- 主界面为服务器、会话、设置三个底部页面。点击主机卡片连接，编辑/复制/删除在卡片菜单；密钥管理放在设置中的 SSH 身份与密钥。终端返回先收键盘，再回会话页并保持连接。
- 终端支持长按选区复制；多行或长文本粘贴先预览确认，保留 bracketed paste。Ctrl+Shift+V、鼠标中键和输入法粘贴统一处理；超大内容建议使用文件上传。
- 会话卡片的工具菜单打开 SFTP，可浏览远端目录、上传和下载文件；复用当前 SSH 连接。返回页面不停止传输，关闭连接会停止；上传默认不覆盖，支持二次确认后的原子覆盖，并支持新建目录、重命名和删除空目录/文件。
- 终端可见时按帧合并刷新，隐藏窗口停止 View 刷新但继续解析；会话标题在 App 后台停止订阅。
- 两行快捷键整体横向滑动；编辑器支持长按拖拽排序、跨行移动和边缘自动滚动，点击按键编辑内容。可调行高及每行显示数量，默认单行 38 dp、显示 7 个标准宽度按键，文字 12 sp；超出的整体横向滑动。Shift / Ctrl / Alt 支持一次性、按住及长按锁定，切换会话清理状态。
- 主机支持名称、地址、用户名及端口搜索，兼容中文和缩写；提供全部 / 收藏 / 最近筛选。默认收藏置前，同组按最近使用排列；最近记录的是主动发起连接的时间，失败尝试也计入。编辑保留收藏和使用记录，复制共用原 Identity，副本不继承收藏和历史。
- 会话列表第一行显示主机名和递增数字编号，第二行显示远端终端标题或连接状态。按开启顺序排列；点击卡片进入，右侧 × 确认关闭。标题展示采用事件驱动的 500ms 合并更新，隐藏卡片取消订阅，无空闲轮询；终端正文持续解析，View 刷新另按界面可见性调度。
- 断线后仅保留旧终端内容供查看，不提供手动重连入口。需要继续操作时，由用户回服务器页面新建连接，旧会话保持独立。连接尚未就绪时重复点击会选中已有尝试；已连接后允许另开会话。
- 内置 Maple Mono NF CN Regular，支持系统字体和导入 TTF / OTF、重命名、删除、字号及预览。终端内可用音量 ＋ / − 调整字号。
- 正常连接时隐藏独立标题栏，快捷键占满整行，右下角不再放管理按钮。系统栏图标跟随实际页面/终端背景，深色终端使用浅色图标。
- App 支持跟随系统/明亮/深色与动态配色；终端独立编辑 ANSI 16 色、前景、背景、光标和选区颜色，提供真实终端预览、JSON 导入导出和常见 Termux colors.properties 导入。保存后应用到现有与新建会话，自定义配色随加密备份保存。
- Prompt 编辑器支持每会话内存草稿、多行中文输入、旋转保留和不追加回车的发送；入口在终端长按菜单、会话工具菜单或自定义快捷键。
- 主机可配置跳板机（最多四层），逐跳校验指纹并独立认证；最终 SSH 连接支持本地端口转发，仅监听手机 127.0.0.1，关闭会话自动停止。
- SSH 保活探测支持关闭/60秒/120秒，新连接生效；无自动重连或持续唤醒锁。设置可查看系统电池优化状态。
- 设置页“关于 ShellDeck”显示版本号、版本码、UTC 构建时间和提交短 SHA；发布标题/说明带同一时间戳，并提供 build-info.json。固定 APK 下载链接不变。
- SSH 身份支持本机生成 Ed25519（默认）或 RSA 3072，可选私钥口令；也支持 OpenSSH / PEM / PKCS#8 导入。每把密钥均可查看、复制公钥或通过系统文件选择器导出 OpenSSH `.pub`。旧身份首次提取公钥时仅在需要解密的情况下询问口令，之后查看公钥无需再次解锁。私钥始终经 Keystore 加密保存。
- 设置中提供加密备份与恢复，包含主机、身份、启动命令和设置；支持恢复预览及逐项选择保留本机、覆盖或导入副本。恢复用新设备的 Keystore 重新加密私钥。导入字体文件和服务器信任记录不迁移。
- 私钥支持标准 PKCS#8 PEM 导出，默认使用口令保护，也允许用户显式选择无口令导出。原私钥口令只在确实需要解锁时询问；不修改本机原密钥。整包备份始终要求密码。
- 主机可配置单行启动命令，例如 `tmux new-session -A -s codex`。默认留空，仅在每次新建 SSH Shell 后发送一次；切换页面、旋转、键盘和窗口尺寸变化不会重复执行。复制主机时保留命令。
- 继续支持按需口令和服务器指纹验证。会话由应用级管理器与可选前台服务管理，支持关闭后台保持、静默通知和尽量常驻通知；划除不补发，最后一个连接结束即停止服务。仍不提供网络自动重连或进程死亡恢复，厂商后台限制与长期熄屏仍需真机验证。


默认快捷键布局（已有布局不会被覆盖，可在编辑器中恢复默认并保存）：

```text
ESC   /     -    PGUP   ↑   PGDN  SHIFT
TAB  CTRL  ALT    ←     ↓    →    键盘
```

数据库版本 4 增加跳板主机引用；支持版本 1 / 2 / 3 非破坏性升级，保留身份密文、主机引用、收藏、最近使用和服务器指纹。按照 [Room 迁移说明](https://developer.android.com/training/data-storage/room/migrating-db-versions) 保留历史 schema 并验证旧库迁移，不使用破坏性重建。

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

该入口仅使用临时测试密钥，通过 adb reverse 连接 loopback sshd；结束时清理测试密钥和端口转发。普通 connectedDebugAndroidTest 未提供测试服务时会跳过真实 SSH 设备用例，不应算作完整验证。

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。测试命令需要本机 OpenSSH sshd / ssh-keygen 、OpenSSL 和 tmux，只启动临时 loopback 公钥认证服务；测试密钥在退出时删除。终端模块包含上游回归测试和会话接入测试；Python 测试覆盖发布版本、包名、debuggable 和签名指纹的校验逻辑。

本仓库所在的 `~/code` 为 NFS 时，先复制源码到本地磁盘或空间充足的 `/dev/shm`，把 `GRADLE_USER_HOME` 和 Android SDK 也置于本地存储再构建。不要把构建缓存同步回源码仓库。

## 自动构建与发布

- PR、push main、手动触发 CI：执行检查和内部 debug 构建，不上传 Dev APK。
- main CI 成功：Release 工作流检出已验证的精确提交，构建并验签，更新固定 rolling Release、源码包及校验文件。旧提交重跑不会覆盖更新的 main。
- push `v*` tag：检查版本与来源，测试、lint、签名构建、验签，发布 GitHub Release。
- GitHub 仓库：[Cherrling/ShellDeck](https://github.com/Cherrling/ShellDeck)。后续正式发布使用已配置的固定签名 Secrets。详见 [发布说明](docs/development/releases.md)。
- [后台会话与通知](docs/development/background-connections.md)。
- [Rolling code 14 输入、文件和 SSH 网络能力](docs/development/rolling-code14-validation.md)。
- [Rolling code 13 配色与版本追踪验证](docs/development/rolling-code13-validation.md)。
- [Rolling code 12 终端交互、刷新与 SFTP 验证](docs/development/rolling-code12-validation.md)。
- [Rolling code 11 本地验证](docs/development/rolling-code11-validation.md)。
- [Rolling code 10 本地验证](docs/development/rolling-code10-validation.md)。
- [Rolling code 6 本地验证](docs/development/rolling-code6-validation.md)。
- 初始多会话实现和验证见 [会话与设置记录](docs/development/sessions-settings-alpha5.md)。
- SSH 版验证见 [alpha.3 验证记录](docs/development/ssh-alpha3-validation.md)。
- 早期构建与检查结果见 [终端验证版记录](docs/development/terminal-lab-validation.md) 和 [初始化验证记录](docs/development/bootstrap-validation.md)。

## 技术研究

- [备份格式、恢复语义与私钥导出](docs/research/backup-format.md)
- [SSH 密钥登录与安全边界](docs/research/ssh-key-integration.md)
- [Termux 集成与 APK 构建](docs/research/termux-integration-and-release.md)
- [Moke 实现与尺寸同步分析](docs/research/moke-integration.md)
- [Agent 协作约定](AGENTS.md)

## 许可证

ShellDeck 自有代码采用 [GPL-3.0-only](LICENSE)。Termux 组件保留上游版权与许可声明，详见 [组件来源与补丁](third-party/termux/README.md)；未内置 Moke 源码。
