# Rolling code 11：加密备份、恢复与私钥导出验证

## 验证结果

构建和测试在 `/dev/shm` 本地副本完成，JDK 17、Android API 35 模拟器。

- 应用 JVM 26 项、Termux emulator JVM 149 项：全部通过，零跳过。覆盖备份随机盐/nonce、密码认证、header/密文/tag 篡改、截断/扩展、载荷计数/长度/引用边界及 12 种私钥导入格式的有口令/无口令 PKCS#8 导出。导出结果由 OpenSSL 独立读取并在 ShellDeck 中还原公钥。
- 源设备完整回归 27 项：全部通过，零跳过。覆盖新设备 Keystore 重新包装、保留/覆盖/副本引用、预览后的数据变化检测、数据库写入失败回滚、设置解析边界、旧快捷键设置兼容、服务器 pin 不变、导出取消和空密码不降级明文、预览跨 Activity 重建及真实 SSH 登录。
- 双设备实测：在独立干净目标模拟器恢复源设备加密备份。目标设备在恢复前后均无法解密源设备的 vault 密文，却能用重新包装后的身份登录同一个临时 OpenSSH 服务；设置、启动命令和数据库重新打开后的身份均正确。
- 系统 DocumentsUI 实测：加密 PEM、显式无口令 PEM、`.sdbak` 均通过用户选定位置保存。两种私钥导出由 OpenSSL 从内存输入验证为同一公钥；测试导出文件随后删除。系统选择备份 → 错误密码提示 → 正确密码预览 → 导入身份副本 → 列表刷新通过。
- Debug / Release APK、lintDebug / lintRelease 通过。正式包名 `cc.cherr.shelldeck`、versionName `0.1.0`、versionCode `11`、固定正式签名和非 debuggable 校验通过。
- 发布脚本测试 3 项通过；Termux 上游完整性检查通过：48 个原样文件、1 个既有会话补丁，本次未修改 Terminal core。

## 可重复运行

完整单设备回归明确指定设备；即使连接多台模拟器，Gradle 也只使用该 serial：

```sh
python3 scripts/ssh_test_server.py -- python3 scripts/android_ssh_tests.py --serial emulator-5554
```

双设备恢复需要目标设备尚未安装 ShellDeck debug 包。脚本不会清除已有目标 App 数据，会拒绝使用非空目标；只在受控测试设备上运行：

```sh
python3 scripts/ssh_test_server.py -- python3 scripts/android_backup_tests.py \
  --source emulator-5554 --target emulator-5556
```

测试过程只在两台设备之间传输密码加密备份和源设备密文负例，不传输源设备 Keystore key。结束清理测试文件、端口转发和脚本安装的测试包。

## 已知边界

- 设备实测为 API 35，不代表所有 Android 8+ 厂商系统或第三方文件 provider 均已实测。
- 导入字体文件、服务器信任 pin、会话输出和网络会话不迁移。
- 主机与身份原子提交；设置是独立持久化步骤，失败会单独报告，可重新恢复设置。
- 无口令私钥导出是用户明确选择的文件输出例外；App 内部仍只持久化 Keystore 密文，整包备份始终加密。
- 格式、大小限制和安全设计见 [备份格式](../research/backup-format.md)。
