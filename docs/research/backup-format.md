# 备份、恢复与私钥导出（rolling code 11）

## 产品范围

设置 → 备份与恢复，可备份身份、主机、启动命令、收藏/历史和设置。身份保存原有私钥文件内容，因此原文件的口令仍然有效；不会要求批量解锁内层私钥。文件选择器是唯一的文件读写入口。导入字体文件、服务器信任 pin、会话输出与运行中的网络连接不包含在备份内。缺失字体在预览中提示，选择恢复设置时回退到内置 Maple Mono；本机 pin 保持原样，新设备按正常首次连接流程确认指纹。

身份菜单或公钥详情可以导出私钥。默认使用至少 8 位的新导出密码；用户也可显式关闭“使用口令保护”，导出标准无口令 PKCS#8 PEM。UI 明确说明无口令文件的用途和风险。文件仅写到用户通过系统选择器指定的 URI，不产生明文临时文件；整包备份不提供无口令选项。此例外由用户明确要求，并记录在 AGENTS.md。

## 独立于设备的备份加密

`.sdbak` v1 二进制布局：

| 字段 | 长度 | 内容 |
| --- | --- | --- |
| magic | 8 字节 | ASCII `SHLDECKB` |
| version | 1 字节 | `01` |
| salt | 16 字节 | SecureRandom，每次重新生成 |
| nonce | 12 字节 | SecureRandom，每次重新生成 |
| ciphertext + tag | 可变 | AES-256-GCM，128-bit tag |

密码通过 PBKDF2-HMAC-SHA256、600,000 次迭代派生 256-bit AES key。整个 37-byte header 为 AAD。算法和迭代次数固定在 v1 格式中，不接受文件指定的无上限工作量参数。未来参数升级使用新格式版本。未知版本、截断、扩展、认证失败均在解析 payload / 写数据库前拒绝。

使用平台 JCA `PBKDF2WithHmacSHA256` 和 `AES/GCM/NoPadding`，不指定 BC provider。盐、nonce 和派生密钥不复用。整个文件认证成功后才交给 payload decoder；不把未经认证的流式解密结果送往数据库。

最大明文载荷 8 MiB，最大文件 8 MiB + 53 字节。读取、编码及解析均有大小限制；工作在后台线程，执行过程中使用事件通知 UI，没有定时轮询。

参考：[Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)、[PBEKeySpec](https://developer.android.com/reference/javax/crypto/spec/PBEKeySpec)、[Android 系统文件选择器](https://developer.android.com/training/data-storage/shared/documents-files)。

## 载荷 v1 / v2

使用 DataOutputStream / DataInputStream 的大端整数；文本为 `int32 UTF-8字节长度 + bytes`，普通文本上限 16 KiB、设置 JSON 上限 1 MiB。私钥为独立二进制字段，不经过 Base64 / JSON 字符串。顺序为：

1. `int32 payloadVersion`（当前写入 2，兼容读取 1），设置 JSON。
2. 身份数量（0–1000）；每个身份为 id、label、algorithm、fingerprint、publicKey 是否存在及可选文本、私钥长度和内容（1–256 KiB）。
3. 主机数量（0–5000）；每个主机为 id、label、hostname、int32 port、username、identityId 是否存在及可选文本、v2 增加 jumpHostId 是否存在及可选文本，随后为 startupCommand、boolean favorite、int64 lastUsedAt。

拒绝重复 id、悬空 identityId、非法端口、控制字符启动命令和尾部多余数据。设置验证版本、取值范围、快捷键数据结构；额外限制 JSON 嵌套深度，避免递归解析资源耗尽。本机旧设置仍使用兼容读取规则，新备份使用严格校验。

备份只在内存中解开源设备 vault，并通过独立备份密码重新加密。恢复预览前，载荷中的私钥已用当前设备 Keystore 包装，预览状态不保留明文私钥。自己的 byte[] / char[]、可控编码缓冲区在完成、取消、失败和销毁时尽力清零，包括扩容前的旧缓冲区；不声称能清除 JVM、系统 provider 和第三方私钥对象的所有副本。

## 恢复冲突与提交

优先用 id 匹配。身份再尝试唯一指纹、唯一名称；主机再尝试唯一 endpoint+username、唯一名称。多个候选不任意选一个覆盖。预览展示来源和本机的身份指纹、主机地址及启动命令。默认保留匹配到的本机记录，可逐项选择使用备份或导入副本；新条目直接新增。

- 保留身份时，导入主机引用对应的本机身份。
- 副本获得新的 id，私钥重新绑定新 id 加密，导入主机同步映射。
- 覆盖采用 Room Upsert，不采用会删除原记录、触发外键问题的 SQL REPLACE。
- 恢复前必须关闭活动连接。主机与身份在一个 Room 事务中写入；写入失败则整体回滚。预览后本机主机/身份有变化时拒绝执行，请用户重新预览；多个来源同时覆盖同一目标也拒绝。
- 设置是可选恢复项，默认关闭。由于它保存在独立 SharedPreferences，设置提交在数据库成功提交之后执行，显式检查 commit 结果。设置写入失败会明确报告“主机与身份已保存，但设置失败”，不会谎报整个恢复失败。若进程恰在两者之间被终止，可重新预览、保留已有记录并恢复设置。
- 恢复不会自动连接服务器、运行启动命令、安装公钥或替换 known_hosts pin。

## 单把私钥导出

SSHJ 完成原私钥解析，需要原口令时才提示。复用固定 BC 1.86 编码为标准 PKCS#8 PEM：有口令时 PBES2 / PBKDF2-HMAC-SHA256（210,000 次）/ AES-256-CBC；显式无口令时输出 `BEGIN PRIVATE KEY`。新口令只用于导出文件，不修改本机保存的原私钥。OpenSSH、传统 PEM、PKCS#8 导入的私钥共用同一路径。

该格式可由 ShellDeck、OpenSSL 和支持 PKCS#8 的工具读取；不承诺每个工具原生支持所有算法的 PKCS#8 格式。GCM 完整性认证适用于整包备份，不把标准 CBC 加密 PEM 宣称为认证加密。

系统选择器使用 `application/octet-stream`，避免默认添加 `.txt`。待导出字节只在 ViewModel 所属对象中保存：默认是密文，显式无口令导出时为临时内存中的明文；不放入 SavedState。旋转保留流程，进程死亡后要求重新导出；文件选择器取消会清理字节，不写文件。

从 rolling code 13 起，settings JSON 写入版本 2，包含可空 terminalTheme（21 个不透明 RGB 色）。读取继续兼容 settings v1；整包备份外层和 payload 版本均不变。含 v2 设置的备份需要新版 App 恢复。

Rolling code 14 起 payload v2 保存跳板主机引用，恢复副本会重新映射引用并验证合并后的主机链。旧版 App 无法读取 v2 备份，新版继续读取 v1。
