# SSH 与密钥登录：设计记录

以下原始设计记录保留 alpha.3 背景；密钥生成、公钥导出及启动命令见文末更新。后续备份恢复、有口令/无口令私钥导出见 [rolling code 11 设计](backup-format.md)。

## 来源与版本

- SSHJ 固定 0.40.0：[SSHClient 内存 key provider API](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/SSHClient.java)、[HostKeyVerifier](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/transport/verification/HostKeyVerifier.java)、[PTY API](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/connection/channel/direct/Session.java)。不使用无条件接受指纹的 verifier。
- Bouncy Castle 1.86：[官方发行记录](https://www.bouncycastle.org/download/bouncy-castle-java/)。Android 的精简 BC 不够用，在 SSH 初始化时替换进程内 BC 提供者，保留 AndroidKeyStore。SSHJ 明确选用完整 BC。
- Room 2.8.4：[官方发行记录](https://developer.android.com/jetpack/androidx/releases/room)。Java entity / DAO 使用 annotationProcessor，避免为了少量数据库定义再引入 KSP。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[密码学建议](https://developer.android.com/privacy-and-security/cryptography)。

## 边界

Host 保存地址、端口、用户名与 identityId。Identity 保存名称、公开指纹、算法及密文；多台 Host 引用同一份密钥。数据库外键限制删除仍在使用的身份。

私钥从文件选择器或粘贴内容导入（上限 256 KiB），由 SSHJ 在内存中强制解析并验证口令。不会写临时明文私钥文件，也不会持久化 passphrase / 登录密码。UI 凭据不进入 SavedState；敏感对话框禁止截图。Java/Kotlin String 与第三方 key provider 无法保证内存中每份副本都可清零；对自有 byte[] / char[] 做尽力清理，不宣称防御已被控制的进程。

Keystore 创建 256-bit AES 密钥，GCM 每次随机 IV，AAD 绑定格式版本和 Identity ID。Room 只保存 `version || IV || ciphertext+tag`。设备解密密钥丢失时不重新生成密钥尝试解密，不静默回退明文。关闭备份/设备迁移，重新安装或迁移需重新导入私钥；首版未绑定生物识别/每次设备认证。

服务器 pin 按规范化的用户输入 hostname + port 保存。首次连接显示算法与 SHA256 指纹，允许取消、信任一次、信任并保存。变化时展示新旧值并明确警告；一次信任不会替换记录。确认有 90 秒超时，取消/退出关闭等待，未确认不认证。当前每个 endpoint 保存一个 key；别名不自动共享信任。

## 会话与 IO

ViewModel 持有会话；TerminalView 在 AndroidView.factory 创建，适配器仅保留弱引用，旋转保留 session。读线程负责连接、认证和持续读取；独立串行发送线程处理输入与 resize，待发送输入上限 1 MiB。主线程不进行网络或数据库访问。控制字符原样发给远端，已移除本地假回显退格逻辑。

初始 PTY 使用 xterm-256color，COLORTERM=truecolor 作为可选环境请求。窗口像素为 cell × grid；继续复用已验证的去重、就绪回放和单个 pending resize 合并逻辑。没有固定 debounce 或周期性刷新。未添加 wakelock / 前台服务；后台、熄屏和进程死亡后的连接恢复属于下一阶段。

## 有意保留的限制

单个活动 SSH session；基础 Host CRUD、身份导入/删除、固定快捷键，暂不提供密钥生成/导出、公钥安装、Mosh、SFTP、完整 Extra Keys 编辑器。关闭进程后不会恢复网络会话。指纹信任不是凭“连接成功”自动获得的。

Bouncy Castle bcpkix 中带有未使用的 EST trust-all helper（JcaJceUtils）。lint 例外仅匹配 1.86 的该依赖 JAR；应用本身与其他依赖的 TrustAllX509TrustManager 检查仍开启。版本更新需重新审查此例外。SSH 不使用该 TLS helper。


## Rolling code 10：生成密钥、公钥导出与启动命令

- 本机生成默认 Ed25519，兼容选项为 RSA 3072。使用已固定的 BC 1.86 JCA provider 和 SecureRandom；生成与解析均在后台工作线程执行。
- 生成的私钥先编码为 PKCS#8；有口令时采用 BC `JceOpenSSLPKCS8EncryptorBuilder` 的 AES-256-CBC、PBKDF2-HMAC-SHA256、210,000 次迭代及随机盐/IV。无论有无口令，持久化前都再经既有 Android Keystore AES-GCM vault 加密，外层 GCM 提供完整性保护。口令不持久化，自有私钥字节和口令数组在 finally 中清零。没有私钥文件导出或生成密钥备份功能；卸载或设备 Keystore 丢失后，生成的私钥无法恢复。
- 公钥从 SSHJ 实际解析的 KeyProvider 提取，使用 SSH wire encoding 后 Base64 编码为 `algorithm base64`，与 authorized_keys 兼容；指纹对同一 wire bytes 计算 SHA256。公钥不含私钥材料，不附加未经处理的名称/comment。
- 新生成/导入时写入独立 publicKey 字段；旧库该字段为 NULL，首次查看时解密原私钥并补提取。只有 parser 要求口令才提示；失败不写缓存。后续查看仅读取公钥，不再解密私钥。所有导入格式共用该提取路径。
- 导出仅写公钥加换行，使用 Android `CreateDocument` / Storage Access Framework 选择位置，不申请存储权限。使用 `application/octet-stream` 保留 `.pub` 扩展名；实测 `text/plain` 会由系统追加 `.txt`，行为可见 [AOSP FileUtils.splitFileName](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/os/FileUtils.java)。待导出的 Identity ID 保存在非敏感 SavedState，避免文件选择器返回时选错身份；私钥和口令不进入 SavedState。
- Room schema 3 增加 `hosts.startupCommand TEXT NOT NULL DEFAULT ''` 和 `identities.publicKey TEXT`，保留 1→2→3 与 2→3 迁移链及历史 schema；不破坏性重建或重加密旧凭据。
- 启动命令为可选单行 Shell 输入，最多 4095 UTF-8 字节，拒绝控制字符。SSH shell 建立后、发布 onReady 前发送命令与回车，因此先于用户输入；UI 重建和 PTY resize 不触发它。它不是 SSH exec channel，也不自动安装公钥。自定义登录脚本若读取或丢弃初始输入，仍可能影响远端执行；不使用猜测提示符或定时重发来规避。

参考：[BC PKCS#8 encryptor API](https://downloads.bouncycastle.org/java/docs/bcpkix-jdk18on-javadoc/org/bouncycastle/openssl/jcajce/JceOpenSSLPKCS8EncryptorBuilder.html)、[Android 创建文档](https://developer.android.com/training/data-storage/shared/documents-files)。API 签名同时核对了项目固定 1.86 JAR。
