# alpha.4：密钥导入与键盘 resize

## 反馈与定位

用户报告 `BEGIN PRIVATE KEY`、无口令的 PEM 无法导入。该文件头表示 PKCS#8，不能据此确定算法。SSHJ 0.40.0 的 PKCS8KeyFile 仅列出 RSA / DSA / EC OID，没有 Ed25519，且 EC 路径假定私钥携带公钥点。RSA PKCS#8 的原有路径通过测试；未取得用户密钥算法或复现样本，不声称已完全确定用户这把 key 的根因。

通过已有 BC 1.86 解析 PKCS#8（含加密版本），使用库 API 导出 SSHJ 支持的内存格式和公钥。存储仍加密保存原始输入，不写中间明文文件，不自行实现密码算法。清理可控口令数组及中间编码数组；Java 字符串与库密钥对象无法保证立即擦除。

连接原先无条件弹出口令框。现在只有解析器确实需要解密时调用口令 UI；格式错误或服务器认证拒绝不会被当作“需要口令”。口令不持久化，取消会断开，等待超时 90 秒，晚到的输入会清理，旧连接不能弹出新连接的口令框。

原布局使用 imePadding，跟随动画逐帧缩放，TerminalView → TerminalSession → PTY resize。已有相同网格去重无法过滤不同中间尺寸。终端现使用 imeAnimationTarget，在动画目标确定时直接应用最终尺寸；管理页保持普通 IME insets。未加入 debounce，未修改 Termux core，未重建 TerminalView。该链路能解释连续 resize；用户 Codex 闪屏是否完全消失仍待真机确认。

## 验证

- 7 个应用 JVM 测试通过，无跳过；包括 12 种真实 OpenSSH 认证样例：OpenSSH Ed25519 / RSA 各有无口令、传统 RSA PEM 有无口令、RSA / Ed25519 PKCS#8 各有无口令、ECDSA PEM、缺少内嵌公钥点的 ECDSA PKCS#8。
- 无口令密钥的口令回调次数为 0，加密密钥为 1；取消、无效格式、错误口令、未授权 key 和服务器指纹拒绝仍被拒绝。
- 149 个终端测试通过；Termux 来源检查通过，48 个上游文件未修改。
- API 35 模拟器 3 个设备测试通过。新增真实 IME 测试通过实际 TerminalView / TerminalSession 统计传输边界，三轮 show/hide 每次恰好一次 resize，收起后恢复原始 rows。
- Android 上 OpenSSH Ed25519、RSA PEM、Ed25519 / RSA PKCS#8 导入和签名验签通过，并断言无口令样例不会请求口令。真实 Keystore / Room 测试继续通过。
- Debug 构建、lint、Python 发布校验通过。测试工具链和产物位于本地内存盘，未在 NFS 上构建。

## 真机复测

用原失败的 PEM 导入并登录；无口令 Identity 直接连接；Codex 不经过 tmux 时多次开合键盘。若仍失败，需要不含秘密的算法信息、错误文字和复现步骤，不需要私钥正文。

## 来源

- [SSHJ v0.40.0 PKCS8KeyFile](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/userauth/keyprovider/PKCS8KeyFile.java)
- [BC 1.86 OpenSSHPrivateKeyUtil](https://github.com/bcgit/bc-java/blob/r1rv86/core/src/main/java/org/bouncycastle/crypto/util/OpenSSHPrivateKeyUtil.java)
- [Android IME insets](https://developer.android.com/develop/ui/compose/system/insets)
