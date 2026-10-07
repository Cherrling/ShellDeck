# SOCKS5 代理与出口检测

研究与实现日期：2026-10-08（UTC+8）。

## 来源与范围

- [RFC 1928](https://www.rfc-editor.org/rfc/rfc1928.txt)：实现 TCP CONNECT、IPv4、IPv6 和域名地址；不提供 BIND、UDP ASSOCIATE 或 SOCKS 服务端。
- [RFC 1929](https://www.rfc-editor.org/rfc/rfc1929)：无认证或用户名密码认证。选择密码认证时只发送方法 02，不接受静默降级到无认证。用户名／密码各限制为 1–255 个 UTF-8 字节；用户名密码子协议本身不加密传输。
- [SSHJ 0.40.0 SocketClient](https://github.com/hierynomus/sshj/blob/v0.40.0/src/main/java/net/schmizz/sshj/SocketClient.java)：SocketFactory 可返回已连接 socket，connect(host, port) 在 socket 已连接时跳过本地目标 DNS／TCP 连接；随后执行 SSH 握手。
- [Cloudflare trace 官方用法](https://developers.cloudflare.com/privacy-proxy/get-started/)：读取 `ip` 字段确定此请求的出口。

## 连接路径

设置 → 连接与后台 → SOCKS 代理，集中增删改配置；服务器编辑页选择代理，默认不使用。所点击目标主机的代理仅作用于手机到第一台 SSH 服务器，后续 SSH 跳转通过 direct-tcpip 通道完成，不继承跳板主机的代理配置。编辑页展示路径。

复用现有 SSH transport 的 socket 所有权及取消逻辑；握手失败不回退直连。凭据按连接传递，不设置进程全局 Authenticator。HostTrust 始终使用配置中的实际 SSH 主机与端口，代理地址不作为信任记录的键。Shell、SFTP、本地端口转发继续共享 SSH 连接。

默认向代理发送目标域名；关闭远端 DNS 时才在本地解析目标。代理自身的域名仍由手机解析。IPv4/IPv6 字面地址直接编码，不做目标 DNS 查询。配置修改只影响新连接；删除被主机引用的代理会失败。

## 检测

- `https://cp.cloudflare.com/generate_204`：GET，要求 204；用单调时钟测量从开始建连到收到响应头的时间，包含代理协商、TLS、HTTP，不包含排队时间。
- `https://www.cloudflare.com/cdn-cgi/trace`：独立 GET，要求 200，解析唯一的 `ip=` 字段并验证 IP 字面格式。
- 当前网络检测不指定 SOCKS，仍遵循 Android 系统 VPN／路由；不能称为运营商真实 IP。
- 每个代理的检测请求经过该代理；出口是访问 Cloudflare 时的出口，不能推断分流后的 SSH 或其他网站出口。
- 两项结果独立，204 失败也尝试 trace；认证、超时、异常状态码、IP 获取失败不会伪装为成功。不跟随重定向、不回退直连、不自动重试、不忽略 TLS 证书或主机名验证。
- 页面手动触发，最多两个检测任务并发。每个 HTTP 请求设 10 秒 socket 截止时间与读超时。响应头及分块元数据总量不超过 32 KiB，响应体不超过 16 KiB。
- 离开页面／应用后台取消正在执行和排队任务；网络或链路属性变化时取消检测并标记旧结果待刷新。旧任务不能覆盖新结果，凭据和检测 socket 在结束后释放，无后台轮询。
- IP 与检测结果只保存在页面内存，不写日志或备份；检测时间明确标记 UTC+8。

系统阻塞式 DNS 解析不保证能被线程中断立即停止；取消会立即失效结果并关闭已创建 socket，DNS 返回后也不能继续建立连接。代理 IP 字面地址不涉及这个解析等待。第一版使用固定 Cloudflare 检测端点。

## 存储与备份

Room v5 新增 proxies 表及 hosts.proxyId；v1–v4 沿增量迁移链升级，已有主机 proxyId 为 null。代理用户名和密码一起使用 CredentialVault 加密，AAD 使用 `proxy:<id>`，不与 SSH 身份共用命名空间。

备份负载 v3 包含代理及其凭据；仍由外层密码加密包裹，不生成明文临时文件。兼容读取 v1/v2 备份。恢复预览支持代理保留／覆盖／导入副本，重新用目标设备 Keystore 加密，并映射主机引用。原有服务器指纹信任记录恢复规则不变。

## 验证

自动测试覆盖握手分片、域名不在本地解析、IPv4/IPv6 编码、密码认证与降级拒绝、代理拒绝、超时／取消、HTTP 长度与分块边界、旧备份兼容与代理引用。实际临时 SSH 服务验证 SOCKS → SSH 和 SOCKS → SSH 跳板机 → SSH，以及 SFTP、端口转发和 Host Key 拒绝。

设备验证另覆盖数据库 v1–v4 升级、跨 Keystore 恢复、配置页操作。`ProxyProbeDeviceTest` 仅在 instrumentation 参数 `externalProbe=true` 时执行 Cloudflare 实网检测，普通离线 CI 不依赖第三方服务。

本轮 Android 15 模拟器共 10 项代理／备份／迁移测试通过，包括直连和带用户名密码 SOCKS5 的 Cloudflare 实网检测。无用户真实代理凭据或生产服务器参与验证。
