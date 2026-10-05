# Rolling code 14：移动输入、文件管理与 SSH 网络能力

## 行为与边界

- Prompt 编辑器可从终端长按菜单、会话工具菜单或自定义快捷键打开。草稿每会话独立，只在进程内保存，旋转/导航保留，关闭会话清除；不写入磁盘或备份。最多 128K 字符，使用终端 bracketed paste 路径，不追加回车。未启用 bracketed paste 的远端仍可能执行文本中的换行，界面有说明。
- SSH 保活探测可关闭或选择 60/120 秒，新连接生效。使用 SSHJ KEEP_ALIVE（需响应），连续 3 个探测请求未响应后结束连接。它不保证 Doze 网络可用，不持有持续 wake lock，不自动重连。设置显示系统电池优化豁免状态，并可打开系统设置。
- 文件浏览入口移至会话卡片的工具菜单，避免窄屏被图标挤占。SFTP 增加创建目录、无覆盖重命名、删除普通文件/链接/空目录。删除链接使用 lstat，不跟随链接递归删除。
- 上传默认不覆盖；用户选择允许覆盖并再次确认后，先上传同目录临时文件，使用 ATOMIC + OVERWRITE rename。服务器不支持时失败，不退化为删除旧文件再上传。取消关闭独立 SFTP 通道，不关闭终端；断网或取消时可能留下 .part 文件。大小已知的传输显示比例，其他情况显示字节计数。
- 本地转发监听 127.0.0.1（不暴露到局域网），目标从最终 SSH 主机访问。本地 UI 端口 1024–65535，每会话最多 8 个转发，每转发最多 8 个并发流。监听持续到用户停止或会话结束；关闭会终止已接受的数据流。目标连接是按浏览器等客户端的实际请求建立。暂不保存转发模板、不支持远程转发/SOCKS。
- 主机可引用其他已保存主机作跳板，最多四层；每跳独立校验指纹和认证，支持密码和加密私钥按需询问。只执行最终主机启动命令。循环、悬空引用或过深链路拒绝连接，绝不退回直接连接。被引用的跳板不可直接删除。
- Room v4 新增可空 jumpHostId，1→2→3→4 非破坏性升级。备份外层加密格式不变，内部 payload v2 增加跳板引用；兼容读取 v1，恢复副本时重映射跳板 id，并校验合并后无循环。

## 研究依据

固定使用 SSHJ 0.40.0：
- [SocketClient.connectVia](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/SocketClient.java) / [SSHClient.newDirectConnection](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/SSHClient.java)：目标握手通过跳板 direct-tcpip 通道执行，不使用远端 shell 拼接命令。
- [KeepAliveRunner](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/keepalive/KeepAliveRunner.java)：有界未响应队列；interval 必须在 connect 前设置，SSHClient.onConnect 才会启动线程。SSH 关闭中断线程。
- [SFTPEngine.rename](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/sftp/SFTPEngine.java)：旧协议通过 posix-rename 扩展实现原子覆盖，无支持则拒绝。
- [Android Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)：前台服务不代表 Doze 网络豁免，后台可靠性需要按设备测试。

## 验证

- 全量 JVM：App 32 项、terminal-emulator 149 项通过，无失败或跳过。半开网络用例通过代理丢弃数据而不关闭 TCP，30 秒探测间隔下约 120 秒触发一次结束，无自动重连。
- 完整 Android 15 模拟器回归 36 项通过，无失败或跳过；含 Activity 销毁后保留连接、通知划除不补发、IME、Prompt 旋转/导航、数据库 v1/v2/v3 升级、跳板副本恢复和 SFTP。
- 最后针对转发的 2 项 JVM 用例再次通过，验证小包立即传递、监听停止、本地连接关闭、远端收到 EOF（不是读取超时）、SSH Shell/SFTP 仍可继续使用。
- assembleDebug / assembleRelease / assembleDebugAndroidTest、lintDebug / lintRelease 通过。正式 APK 验证 cc.cherr.shelldeck 0.1.0 (14) 与既有发布证书一致。
- Python 发布校验 4 项通过；Termux 上游完整性检查通过，未新增核心补丁；git diff --check 通过。

回归中修复：转发数据泵未及时 flush；停止时中断正在打开的 SSH 通道导致远端未及时释放；新增会话图标挤占窄屏点击区域。

模拟器可验证服务和 Activity 生命周期；厂商后台限制、移动网络切换、过夜熄屏以及实际耗电仍需真机，不能据此宣称功耗改善百分比或永不断线。
