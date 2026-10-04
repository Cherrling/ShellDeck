# alpha.3 验证记录

## 本地验证

本地内存盘副本构建，JDK 17、SDK 36。执行 debug / release assemble 与 lint、Android test APK 构建。

- 149 个 Termux 上游和接入测试通过。
- 6 个应用 JVM 测试通过，没有跳过：包含加密认证/身份绑定、指纹接受策略，以及真实 OpenSSH 集成。
- 隔离 sshd 仅监听 127.0.0.1，禁用密码和键盘交互认证。运行时生成并删除测试密钥，不使用开发者或用户私钥。
- 实测无口令/带口令 Ed25519、RSA 共四种认证组合；错误口令、无效格式、未授权 key 和拒绝 Host Key 均失败。
- 真实 PTY 验证中文输出、中文退格、100×35 resize 和主动关闭。使用 UTF-8 Bash，不加载开发机的个人交互式 shell 配置。
- Android 15 / API 35 模拟器：1 个设备测试通过。完整 BC 初始化后，真实 AndroidKeyStore AES-GCM 加解密、错误 AAD 拒绝、Room 关闭重开后解密，以及 Host → Identity 删除约束均通过。
- 模拟器安装启动成功，首页可见，无应用崩溃记录。
- Python 发布校验 3 组测试、Termux 来源检查（48 个未修改文件）和 actionlint 通过。

## 签名与分发

固定发布身份存于仓库外的受限目录。正式包名 cc.cherr.shelldeck；开发包名 cc.cherr.shelldeck.debug。最终包还需由 scripts/release.py verify-apk 校验，产物 SHA256 以 dist 文件为准。

CI 包含真实 sshd 测试；启用时应用测试强制执行，不从 Gradle build cache 复用结果。暂时无测试服务的普通 IDE 单测会跳过四个集成测试，不应把它们当成已验证。

## 尚未覆盖

用户服务器的 tmux / Codex 版本、真实手机 IME、长时间后台与熄屏功耗、网络切换和进程死亡恢复。当前版本未提供后台保活服务，也不承诺锁屏持续连接。模拟器的基本运行不替代真机体验测试。
