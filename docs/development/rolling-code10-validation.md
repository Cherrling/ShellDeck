# Rolling code 10：启动命令与 SSH 公钥管理

## 功能与边界

- 主机编辑新增可选启动命令，默认留空；只在新 SSH Shell 建立后发送一次。示例：`tmux new-session -A -s codex`。复制主机保留命令，切换页面、旋转和 PTY resize 不重放。
- 设置 → SSH 身份与密钥 → ＋，可生成 Ed25519 / RSA 3072 或导入私钥。生成允许可选口令，私钥始终通过现有 Keystore vault 加密持久化。
- 每把密钥支持查看/复制 OpenSSH 公钥，以及系统文件选择器保存 `.pub`。已有身份首次提取公钥时按需询问口令；缓存成功后无需再次解锁私钥。
- 本次没有私钥导出、自动安装公钥、自动/手动重连。断线仍保留旧终端内容，由用户新建连接。

## 本地验证

在本地 tmpfs 副本构建，JDK 17、API 35 Android 模拟器；未在 NFS checkout 编译。

- 应用 JVM 23 项、Termux emulator JVM 149 项：全部通过，零跳过。真实 loopback OpenSSH 服务验证生成的两种算法（有/无口令）均可认证；OpenSSH 独立验证导出公钥指纹。12 种导入格式与 OpenSSH/OpenSSL 生成的对照公钥一致。错误口令、认证拒绝、取消握手和 PTY 输入/resize 回归通过。
- Android 设备测试 22 项：全部通过，零跳过。覆盖密钥生成界面、复制、文件写出、Activity 重建、旧加密密钥首次解锁/错误口令/缓存后不再提示；schema 1→3 和 2→3 保留凭据、Host 引用、收藏、历史和指纹；真实 SSH 启动计数在字体/IME尺寸变化与 Activity 重建后仍为 1。
- 补充实测系统 DocumentsUI：生成密钥 → 导出 → Downloads 保存，实际得到 `.pub`，内容逐字匹配界面公钥并通过 `ssh-keygen -lf`。发现并修正 `text/plain` 自动追加 `.txt` 的问题，改用 `application/octet-stream` 后重新构建、lint 和验签通过。
- Debug / Release 构建、lintDebug / lintRelease 通过；正式 APK 校验包名 `cc.cherr.shelldeck`、versionName `0.1.0`、versionCode `10`、非 debuggable、固定签名一致。
- 发布脚本 Python 测试 3 项通过；上游完整性校验通过：48 个原样文件、1 个既有会话补丁，本次未修改 Termux core。

覆盖限制：本次设备验证为 API 35 模拟器，不代表所有 Android 8+ 厂商系统、第三方文档 provider 或自定义远端登录脚本均已实测。私钥导出/迁移未实现，不能用公钥恢复私钥。
